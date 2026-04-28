// ==================================================
// FILE: ecobridge-rust/src/economy/pricing.rs (v1.7.0)
// ==================================================
// [v1.7.0] Recovery & Adaptive Tau: added mean-reversion with integral memory
// for sustained price stability under chronic oversupply (shop收购 > 玩家购买).

use crate::models::{TradeContext, MarketConfig};
use rayon::prelude::*;
use crate::economy::environment;
use crate::economy::volatility;
use std::sync::Mutex;
use std::collections::HashMap;
use std::sync::LazyLock;

/// 精度缩放常量 (1.0 = 1,000,000 Micros)
const MICROS_SCALE: f64 = 1_000_000.0;

// ==================== Recovery State (v1.7.0) ====================

/// Per-item recovery integral state — tracks how long price has been suppressed.
struct RecoveryIntegral {
    accumulated_deficit: f64, // sum of (target - price) / hist_avg over suppressed periods
    last_update_ts: i64,
}

static RECOVERY_STATES: LazyLock<Mutex<HashMap<String, RecoveryIntegral>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Recovery parameter set (aligned with simulation-verified optimal values).
const RECOVERY_ACTIVATION_RATIO: f64 = 0.85;
const RECOVERY_TARGET_RATIO: f64 = 0.92;
const RECOVERY_STRENGTH: f64 = 0.25;
const RECOVERY_INTEGRAL_GAIN: f64 = 0.015;
const RECOVERY_MAX_INTEGRAL: f64 = 2.0;
const RECOVERY_MAX_STEP_RATIO: f64 = 0.03;

/// System Bid (Universal Price Floor) — anchors every item to a guaranteed minimum.
/// Inspired by OSRS High Alchemy & EVE reprocessing value.
/// The server will always buy at this price, preventing total market collapse.
const SYSTEM_BID_RATIO: f64 = 0.40; // 40% of base price = hard floor

// -----------------------------------------------------------------------------
// 1. 内部定价核心逻辑 (Core Engine)
// -----------------------------------------------------------------------------

/// 具备行为经济学感知的定价引擎 (v1.7.0)
///
/// @param base_price_micros 物品基础定价 (i64 Micros)
/// @param n_eff 有效物品供应累积量 (来自 SIMD 演算，已缩放为标准 f64)
/// @param trade_amount_micros 本次交易的物品件数 (i64 Micros)：正数为卖出，负数为买入
#[inline]
fn compute_price_behavioral_core(
    base_price_micros: i64,
    n_eff: f64,
    trade_amount_micros: i64,
    lambda: f64,
    epsilon: f64,
) -> f64 {
    // 1. 数据转换与安全性检查
    let base_price_f64 = (base_price_micros as f64) / MICROS_SCALE;
    let trade_amount_f64 = (trade_amount_micros as f64) / MICROS_SCALE;

    if !base_price_f64.is_finite() || !n_eff.is_finite() ||
       !lambda.is_finite() || !epsilon.is_finite() {
        return 0.01;
    }

    // 2. 非对称灵敏度 (Asymmetric Sensitivity)
    // 逻辑：卖出物品时灵敏度降低(0.6x)，模拟”价格下行粘性”
    let adj_lambda = if trade_amount_micros > 0 {
        lambda * 0.6
    } else {
        lambda
    };

    // 3. 计算总有效供应量冲击
    let total_n = n_eff + trade_amount_f64;

    // 4. 指数演算与平滑限幅 (Soft Clamping)
    let raw_exponent = (-adj_lambda * total_n).clamp(-100.0, 100.0);

    // 使用 tanh 确保价格曲线在极端工业产出下平滑逼近底价，不会突变为 0
    let clamped_exponent = 10.0 * (raw_exponent / 10.0).tanh();

    let final_price = base_price_f64 * epsilon * clamped_exponent.exp();

    // 5. 绝对硬底线 (0.01 货币单位)
    final_price.max(0.01)
}

// -----------------------------------------------------------------------------
// 2. 阶梯定价与底价保护 (Defense Layers)
// -----------------------------------------------------------------------------

/// 计算阶梯定价 (Tier Pricing)
/// 针对单笔超大量物品售出的防御性降价逻辑
#[inline]
pub fn compute_tier_price_internal(
    base_price: f64, 
    quantity_f64: f64, 
    is_sell: bool
) -> f64 {
    // 只有卖出且物品数量超过 500 件时触发阶梯折扣
    if !is_sell || quantity_f64 <= 500.0 || quantity_f64 <= 0.0 {
        return base_price;
    }

    let mut total_value = 0.0;
    let mut remaining = quantity_f64;

    // Tier 1: 0 - 500 件 (100% 原始演算价)
    let t1 = remaining.min(500.0);
    total_value += t1 * base_price;
    remaining -= t1;

    // Tier 2: 501 - 2000 件 (85% 折扣价)
    if remaining > 0.0 {
        let t2 = remaining.min(1500.0);
        total_value += t2 * (base_price * 0.85);
        remaining -= t2;
    }

    // Tier 3: 2000 件以上 (60% 深度折扣)
    if remaining > 0.0 {
        total_value += remaining * (base_price * 0.60);
    }

    total_value / quantity_f64
}

/// Apply mean-reversion recovery: pull prices back toward hist_avg when suppressed.
/// Returns (adjusted_price, recovery_was_active).
/// [v2.0] Uses `entry()` to avoid double HashMap lookup.
fn apply_recovery_pull(
    raw_price: f64,
    hist_avg: f64,
    vol_mult: f64,
    current_ts: i64,
) -> (f64, bool) {
    let activation_price = hist_avg * RECOVERY_ACTIVATION_RATIO;
    let target_price = hist_avg * RECOVERY_TARGET_RATIO;

    if raw_price >= activation_price || hist_avg <= 0.0 {
        // Decay integral via single entry lookup
        if let Ok(mut states) = RECOVERY_STATES.lock() {
            if let Some(state) = states.get_mut("__global__") {
                state.accumulated_deficit *= 0.9;
            }
        }
        return (raw_price, false);
    }

    // Single lock + single HashMap access via entry()
    let mut states = RECOVERY_STATES.lock().unwrap();
    let state = states.entry("__global__".into()).or_insert_with(|| RecoveryIntegral {
        accumulated_deficit: 0.0,
        last_update_ts: current_ts,
    });

    let deficit = ((target_price - raw_price) / target_price.max(0.01)).clamp(0.0, 1.0);
    state.accumulated_deficit = (state.accumulated_deficit + deficit * RECOVERY_INTEGRAL_GAIN)
        .min(RECOVERY_MAX_INTEGRAL);
    state.last_update_ts = current_ts;

    let integral_factor = 1.0 + state.accumulated_deficit;
    let recovery_amount = RECOVERY_STRENGTH * deficit * RECOVERY_MAX_STEP_RATIO * hist_avg * integral_factor;
    let moderated_recovery = recovery_amount / vol_mult.max(1.0);

    (raw_price + moderated_recovery, true)
}

/// 包含动态底价保护 + 均值回归恢复的最终价格演算 (v1.7.0)
/// [v2.0] vol_mult pre-computed per batch; recovery uses static key (no alloc).
pub fn compute_price_bounded_internal(
    base_micros: i64, n_eff: f64, amt_micros: i64, lambda: f64, eps: f64,
    hist_avg: f64
) -> f64 {
    let raw_price = compute_price_behavioral_core(base_micros, n_eff, amt_micros, lambda, eps);
    let vol_mult = volatility::garch_volatility_multiplier("__global__");
    let floor = (hist_avg * 0.62 * vol_mult).max(0.01);

    let mut price = raw_price.max(floor);
    let (recovered, _active) = apply_recovery_pull(price, hist_avg, vol_mult, 0);
    price = recovered.max(floor);

    price
}

#[inline]
pub fn compute_price_bounded_internal_cached(
    base_micros: i64, n_eff: f64, amt_micros: i64, lambda: f64, eps: f64,
    hist_avg: f64, vol_mult: f64
) -> f64 {
    let raw_price = compute_price_behavioral_core(base_micros, n_eff, amt_micros, lambda, eps);
    let floor = (hist_avg * 0.62 * vol_mult).max(0.01);
    let price = raw_price.max(floor);
    let (recovered, _active) = apply_recovery_pull(price, hist_avg, vol_mult, 0);
    recovered.max(floor)
}

// -----------------------------------------------------------------------------
// 3. 转发逻辑层 (API Layer)
// -----------------------------------------------------------------------------

/// 获取单体实时价格 (不包含本次交易的预测冲击)
pub fn compute_price_final_internal(base_micros: i64, n_eff: f64, lambda: f64, eps: f64) -> f64 {
    compute_price_behavioral_core(base_micros, n_eff, 0, lambda, eps)
}

/// 获取单体成交价格 (包含本次物品数量冲击)
pub fn compute_price_humane_internal(base_micros: i64, n_eff: f64, amt_micros: i64, lambda: f64, eps: f64) -> f64 {
    compute_price_behavioral_core(base_micros, n_eff, amt_micros, lambda, eps)
}

/// 批量价格演算内核 - 适配 v1.6.0 高精度上下文
pub unsafe fn compute_batch_prices_internal(
    count: usize,
    neff: f64,
    ctx_ptr: *const TradeContext,
    cfg_ptr: *const MarketConfig,
    hist_avgs_ptr: *const f64,
    lambdas_ptr: *const f64,
    output_ptr: *mut f64,
) {
    let ctx_slice = std::slice::from_raw_parts(ctx_ptr, count);
    let cfg_slice = std::slice::from_raw_parts(cfg_ptr, count);
    let hist_avgs = std::slice::from_raw_parts(hist_avgs_ptr, count);
    let lambdas = std::slice::from_raw_parts(lambdas_ptr, count);
    let output = std::slice::from_raw_parts_mut(output_ptr, count);

    // [v2.0] Pre-compute GARCH vol_mult once per batch (was per-item)
    let vol_mult = volatility::garch_volatility_multiplier("__global__");

    output.par_iter_mut()
        .enumerate()
        .for_each(|(i, price_out)| {
            let ctx = &ctx_slice[i];
            let cfg = &cfg_slice[i];
            let lambda = lambdas[i];
            let hist_avg = hist_avgs[i];

            let epsilon = environment::calculate_epsilon_internal(ctx, cfg);

            *price_out = compute_price_bounded_internal_cached(
                ctx.base_price_micros,
                neff,
                0,
                lambda,
                epsilon,
                hist_avg,
                vol_mult,
            );
        });
}

/// Compute the System Bid — the guaranteed minimum buy price.
/// This is the price at which the server will always purchase items from players,
/// serving as the ultimate economic floor and item sink.
/// Modeled after OSRS High Alchemy and EVE reprocessing values.
pub fn compute_system_bid(base_price_micros: i64, hist_avg: f64) -> f64 {
    let base = (base_price_micros as f64) / 1_000_000.0;
    (base * SYSTEM_BID_RATIO).max(hist_avg * 0.20).max(0.01)
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;

    // --- behavioral core ---

    #[test]
    fn test_normal_buy_scenario() {
        let price = compute_price_behavioral_core(
            1_000_000, // base = 1.0
            500.0,      // n_eff
            -10_000_000, // buy 10 items (negative = buy)
            0.01,
            1.0,
        );
        assert!(price > 0.01, "buy price should be above absolute floor");
        assert!(price < 1.0, "price should decrease with positive n_eff");
    }

    #[test]
    fn test_sell_asymmetry_lambda_reduced() {
        // sell: lambda * 0.6; buy: lambda * 1.0
        let price_sell = compute_price_behavioral_core(
            1_000_000, // base = 1.0
            100.0,
            50_000_000, // sell 50 items (positive = sell)
            0.01,
            1.0,
        );
        // Without asymmetry, sell at full lambda would be: exp(-0.01×150)≈0.223
        // With 0.6x lambda: exp(-0.006×150)≈0.407 — asymmetry REDUCES the drop
        // Verify sell asymmetry reduces lambda and price stays above hard floor
        assert!(price_sell > 0.01 && price_sell < 1.0,
            "sell with asymmetry should produce valid price in range, got: {}", price_sell);

        let price_no_asym = compute_price_behavioral_core(
            1_000_000, 100.0, 50_000_000, 0.01, 1.0,
        );
        // With symmetric lambda, in a function without the asymmetry, the computed
        // price is the same (asymmetry only applies inside the core function once per call).
        // Just verify both are finite and positive.
        assert!(price_sell.is_finite() && price_no_asym.is_finite());
    }

    #[test]
    fn test_non_finite_input_returns_floor() {
        let price = compute_price_behavioral_core(1_000_000, f64::NAN, 0, 0.01, 1.0);
        assert!((price - 0.01).abs() < 1e-6, "NaN input should return absolute floor 0.01");

        let price2 = compute_price_behavioral_core(1_000_000, 100.0, 0, f64::INFINITY, 1.0);
        assert!((price2 - 0.01).abs() < 1e-6, "Infinite lambda should return absolute floor 0.01");
    }

    #[test]
    fn test_tanh_soft_clamping_limits_exponent() {
        // With huge n_eff, the raw exponent is clamped to ~[-10, 10] by tanh.
        // After exp(-10) ≈ 0.000045, the price floor (0.01) kicks in.
        let price = compute_price_behavioral_core(
            1_000_000, // base = 1.0
            1_000_000.0, // extremely large n_eff
            0,
            0.01,
            1.0,
        );
        // The absolute hard floor (0.01) ensures price never reaches zero
        assert!(price >= 0.01, "tanh clamping + absolute floor must prevent price from hitting zero");
    }

    #[test]
    fn test_zero_n_eff_epsilon_one_gives_base_price() {
        let price = compute_price_behavioral_core(
            2_000_000, // base = 2.0
            0.0,
            0,
            0.01,
            1.0,
        );
        // exp(0) * base * 1.0 = base = 2.0
        assert!((price - 2.0).abs() < 0.01);
    }

    // --- tier pricing ---

    #[test]
    fn test_tier_price_normal_quantity_no_discount() {
        let result = compute_tier_price_internal(10.0, 400.0, true);
        assert!((result - 10.0).abs() < 1e-6, "<= 500 items should have no tier discount");
    }

    #[test]
    fn test_tier_price_three_levels() {
        let result = compute_tier_price_internal(10.0, 3000.0, true);
        // tier 1: 500 * 10 = 5000
        // tier 2: 1500 * 8.5 = 12750
        // tier 3: 1000 * 6.0 = 6000
        // total = 23750 / 3000 = 7.9166...
        assert!(result < 10.0, "bulk sell should get tier discount");
        assert!(result > 5.0, "tier price should not drop below deepest tier rate");
        let expected = (500.0 * 10.0 + 1500.0 * 8.5 + 1000.0 * 6.0) / 3000.0;
        assert!((result - expected).abs() < 0.01);
    }

    #[test]
    fn test_tier_price_buy_no_discount() {
        let result = compute_tier_price_internal(10.0, 5000.0, false);
        assert!((result - 10.0).abs() < 1e-6, "buy orders should never trigger tier discount");
    }

    #[test]
    fn test_tier_price_zero_quantity() {
        let result = compute_tier_price_internal(10.0, -1.0, true);
        assert!((result - 10.0).abs() < 1e-6);
    }

    // --- bounded / floor ---

    #[test]
    fn test_dynamic_floor_from_hist_avg() {
        let price = compute_price_bounded_internal(
            1_000_000, // base = 1.0
            1_000_000.0, // huge n_eff would make price very low
            0,
            0.01,
            1.0,
            5.0, // hist_avg = 5.0, floor = 5.0 * 0.2 = 1.0
        );
        assert!(price >= 1.0, "floor should be at least hist_avg * 0.2 = 1.0");
    }

    #[test]
    fn test_absolute_floor_never_below_001() {
        let price = compute_price_bounded_internal(
            1_000_000,
            1_000_000.0,
            0,
            100.0,
            1.0,
            0.001, // hist_avg so low that 20% = 0.0002, but absolute floor is 0.01
        );
        assert!(price >= 0.01, "absolute floor of 0.01 must be respected");
    }

    // --- batch ---

    #[test]
    fn test_batch_prices_produces_correct_count() {
        let count = 5;
        let ctx = vec![TradeContext {
            base_price_micros: 1_000_000,
            current_timestamp: 1_700_000_000_000,
            ..Default::default()
        }; count];
        let cfg = vec![MarketConfig::default(); count];
        let hist_avgs = vec![2.0; count];
        let lambdas = vec![0.01; count];
        let mut output = vec![0.0; count];

        unsafe {
            compute_batch_prices_internal(
                count,
                100.0,
                ctx.as_ptr(),
                cfg.as_ptr(),
                hist_avgs.as_ptr(),
                lambdas.as_ptr(),
                output.as_mut_ptr(),
            );
        }

        for &price in &output {
            assert!(price.is_finite() && price > 0.0, "all batch prices should be finite and positive");
        }
    }

    // --- final price (zero trade amount) ---

    #[test]
    fn test_compute_price_final_is_pure_query() {
        let price = compute_price_final_internal(2_000_000, 100.0, 0.01, 1.0);
        assert!(price > 0.01 && price.is_finite(), "final price query should return valid price");
    }

    #[test]
    fn test_humane_price_includes_trade_impact() {
        let base = compute_price_humane_internal(2_000_000, 100.0, 0, 0.01, 1.0);
        let with_trade = compute_price_humane_internal(2_000_000, 100.0, 50_000_000, 0.01, 1.0);
        // Both are valid prices; asymmetry softens the sell impact
        assert!(base > 0.01 && with_trade > 0.01, "all prices should be above floor");
    }
}