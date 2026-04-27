// ==================================================
// FILE: ecobridge-rust/src/economy/environment.rs
// ==================================================

//! Environmental Factor Engine (v1.6.0 - Precision Aligned)
//! 
//! 本模块计算定价公式中的 Epsilon (ε) 因子。
//! Epsilon 是一个综合乘数，包含了季节性波动、周末效应、新手保护和通胀反馈。
//! 
//! # 变更记录
//! - [v1.6.0] 语义化对齐：适配 i64 定点数协议上下文，确保与 models.rs 兼容。
//! - [v1.0.0] 引入渐进式新手保护模型（100小时线性衰减）。

use crate::models::{TradeContext, MarketConfig};

// ==================== 时间常量 ====================
const SECONDS_PER_DAY: f64 = 86400.0;
const SECONDS_PER_WEEK: f64 = 604800.0;
const SECONDS_PER_MONTH: f64 = 2592000.0;

// ==================== 辅助数学函数 ====================

/// Sigmoid 函数：用于在特定阈值附近平滑触发反馈逻辑
#[inline]
fn sigmoid(x: f64) -> f64 {
    1.0 / (1.0 + (-x * 10.0).exp())
}

// ==================== 核心逻辑实现 ====================

/// 纯 Rust 实现的环境因子计算 (v1.6.0)
/// 
/// 该函数输出一个 f64 乘数（通常在 0.5 到 2.0 之间），直接作用于 base_price。
pub fn calculate_epsilon_internal(
    ctx: &TradeContext,
    cfg: &MarketConfig,
) -> f64 {
    // 1. 时间轴对齐 (UTC -> Local)
    let ts_sec_utc = (ctx.current_timestamp as f64) / 1000.0;
    let offset_sec = ctx.timezone_offset as f64;
    let ts_sec_local = ts_sec_utc + offset_sec;
    
    let safe_ln = |factor: f64| factor.max(0.01).ln();

    // 2. 季节性因子 (Seasonal Factor)
    // 使用复合正弦波模拟日、周、月的周期性波动
    let day_wave = (ts_sec_local * 2.0 * std::f64::consts::PI / SECONDS_PER_DAY).sin();
    let week_wave = (ts_sec_local * 2.0 * std::f64::consts::PI / SECONDS_PER_WEEK).sin();
    let month_wave = (ts_sec_local * 2.0 * std::f64::consts::PI / SECONDS_PER_MONTH).sin();
    
    let seasonal_factor = 0.6 * day_wave + 0.3 * week_wave + 0.1 * month_wave;
    let mut f_sea = 1.0 + cfg.seasonal_amplitude * seasonal_factor;
    
    // 节庆模式 (Festival Mode)：检查位掩码 bit1
    if (ctx.newbie_mask >> 1) & 1 == 1 {
        f_sea *= 1.15; 
    }

    // 3. 周末因子 (Weekend Factor)
    let day_index = (ts_sec_local / SECONDS_PER_DAY).floor() as i64;
    let day_of_week = (day_index + 4).rem_euclid(7); // 0=周一, 6=周日
    let f_wk = if day_of_week >= 5 { cfg.weekend_multiplier } else { 1.0 };

    // 4. 渐进式优待因子 (Dynamic Protection Factor)
    // 逻辑：优待随 play_time_seconds 增加而线性衰减，100小时后完全消失
    let play_hours = (ctx.play_time_seconds as f64) / 3600.0;
    let protection_decay = (1.0 - (play_hours / 100.0)).clamp(0.0, 1.0); 
    
    // 优待表现为价格折扣 (f_nb < 1.0)
    let f_nb = 1.0 - (cfg.newbie_protection_rate * protection_decay);

    // 5. 通胀反馈因子 (Inflation Feedback)
    // 当通胀率超过 5% 时，加速推高物价以模拟货币贬值
    let sigmoid_trigger = sigmoid(ctx.inflation_rate - 0.05);
    let f_inf = 1.0 + (ctx.inflation_rate * 0.2 * sigmoid_trigger);

    // 6. 对数加权合成最终 Epsilon (Geometric Mean Approximation)
    let log_eps = 
          cfg.seasonal_weight   * safe_ln(f_sea)
        + cfg.weekend_weight    * safe_ln(f_wk)
        + cfg.newbie_weight     * safe_ln(f_nb)
        + cfg.inflation_weight  * safe_ln(f_inf);

    let mut epsilon = log_eps.exp();

    // 7. 市场波动率非线性增强
    if cfg.volatility_factor > 1.001 {
        epsilon = 1.0 + (epsilon - 1.0) * cfg.volatility_factor;
    }

    // 安全阀：严禁环境因子导致价格归零或爆炸
    epsilon.clamp(0.1, 10.0)
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_v1_6_progressive_protection() {
        let mut cfg = MarketConfig::default();
        cfg.newbie_protection_rate = 0.2; // 20% 满额优待
        cfg.newbie_weight = 1.0;
        // 隔离其它变量
        cfg.seasonal_weight = 0.0; cfg.weekend_weight = 0.0; cfg.inflation_weight = 0.0;
        cfg.volatility_factor = 1.0;

        // Case A: 萌新 (0h) -> 0.8x 价格优待
        let ctx_new = TradeContext { play_time_seconds: 0, ..Default::default() };
        let eps_new = calculate_epsilon_internal(&ctx_new, &cfg);
        assert!((eps_new - 0.8).abs() < 1e-4);

        // Case B: 中坚玩家 (50h) -> 0.9x 价格优待 (衰减一半)
        let ctx_mid = TradeContext { play_time_seconds: 50 * 3600, ..Default::default() };
        let eps_mid = calculate_epsilon_internal(&ctx_mid, &cfg);
        assert!((eps_mid - 0.9).abs() < 1e-4);

        // Case C: 资深玩家 (200h) -> 无优待 (1.0x)
        let ctx_pro = TradeContext { play_time_seconds: 200 * 3600, ..Default::default() };
        let eps_pro = calculate_epsilon_internal(&ctx_pro, &cfg);
        assert!((eps_pro - 1.0).abs() < 1e-4);
    }

    #[test]
    fn test_seasonal_cycle_produces_valid_range() {
        let cfg = MarketConfig {
            seasonal_weight: 1.0,
            weekend_weight: 0.0,
            newbie_weight: 0.0,
            inflation_weight: 0.0,
            ..MarketConfig::default()
        };
        // test over multiple timestamps to ensure seasonality is smooth
        for day in 0..365 {
            let ctx = TradeContext {
                current_timestamp: (day * 86_400) * 1000,
                ..Default::default()
            };
            let eps = calculate_epsilon_internal(&ctx, &cfg);
            assert!(eps >= 0.1 && eps <= 10.0, "epsilon should always be within clamped bounds");
            assert!(eps.is_finite(), "epsilon should be finite");
        }
    }

    #[test]
    fn test_weekend_factor_applied() {
        let mut cfg = MarketConfig {
            weekend_weight: 1.0,
            seasonal_weight: 0.0,
            newbie_weight: 0.0,
            inflation_weight: 0.0,
            weekend_multiplier: 1.2,
            ..MarketConfig::default()
        };
        cfg.volatility_factor = 1.0;

        // 2026-04-25 is a Saturday
        let sat_ts = 1_745_568_000_000i64;
        let ctx_sat = TradeContext { current_timestamp: sat_ts, ..Default::default() };
        let eps_sat = calculate_epsilon_internal(&ctx_sat, &cfg);

        // Monday
        let mon_ts = 1_745_740_800_000i64;
        let ctx_mon = TradeContext { current_timestamp: mon_ts, ..Default::default() };
        let eps_mon = calculate_epsilon_internal(&ctx_mon, &cfg);

        // Weekend epsilon should be > weekday (weekend multiplier = 1.2)
        assert!(eps_sat > eps_mon,
            "weekend epsilon ({}) should exceed weekday epsilon ({})", eps_sat, eps_mon);
    }

    #[test]
    fn test_epsilon_clamped_to_0_1_to_10() {
        let mut cfg = MarketConfig::default();
        cfg.volatility_factor = 1000.0; // extreme
        cfg.seasonal_amplitude = 100.0; // extreme

        let ctx = TradeContext { current_timestamp: 1_000_000_000_000, ..Default::default() };
        let eps = calculate_epsilon_internal(&ctx, &cfg);
        assert!(eps >= 0.1 && eps <= 10.0, "epsilon must be clamped to [0.1, 10.0]");
    }

    #[test]
    fn test_inflation_feedback_triggers_above_5_percent() {
        let mut cfg = MarketConfig {
            inflation_weight: 1.0,
            seasonal_weight: 0.0,
            weekend_weight: 0.0,
            newbie_weight: 0.0,
            ..MarketConfig::default()
        };
        cfg.volatility_factor = 1.0;

        let ctx_low = TradeContext {
            inflation_rate: 0.01,
            current_timestamp: 1_000_000_000_000,
            ..Default::default()
        };
        let ctx_high = TradeContext {
            inflation_rate: 0.20,
            current_timestamp: 1_000_000_000_000,
            ..Default::default()
        };

        let eps_low = calculate_epsilon_internal(&ctx_low, &cfg);
        let eps_high = calculate_epsilon_internal(&ctx_high, &cfg);
        assert!(eps_high > eps_low, "high inflation should produce larger epsilon");
    }
}