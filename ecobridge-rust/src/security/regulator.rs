// ==================================================
// FILE: ecobridge-rust/src/security/regulator.rs
// ==================================================

use crate::models::{TransferContext, TransferResult, RegulatorConfig};

// 状态码常量
pub const CODE_NORMAL: i32 = 0;
pub const CODE_WARNING_HIGH_RISK: i32 = 1;
pub const CODE_BLOCK_REVERSE_FLOW: i32 = 2;
pub const CODE_BLOCK_INJECTION: i32 = 3;
pub const CODE_BLOCK_INSUFFICIENT_FUNDS: i32 = 4;
pub const CODE_BLOCK_VELOCITY_LIMIT: i32 = 5; 
pub const CODE_BLOCK_QUANTITY_LIMIT: i32 = 6;

/// 精度缩放常量 (1.0 = 1,000,000 Micros)
const MICROS_SCALE: f64 = 1_000_000.0;

// [v2.0] to_micros_saturating is shared from crate root (lib.rs)

/// 增强型交易审计逻辑 (v1.6.0 - Precision Hardened)
/// 
/// 该版本已全面适配 i64 Micros 定点数协议，彻底解决 IEEE 754 累积误差。
pub fn compute_transfer_check_internal(
    ctx: &TransferContext,
    cfg: &RegulatorConfig,
) -> TransferResult {
    // 1. 基础数据转换 (Micros i64 -> f64 用于数学运算)
    let amount_f64 = (ctx.amount_micros as f64) / MICROS_SCALE;
    let sender_bal_f64 = (ctx.sender_balance as f64) / MICROS_SCALE;
    let receiver_bal_f64 = (ctx.receiver_balance as f64) / MICROS_SCALE;
    
    // ============================================================
    // 1. 动态数量限额演算 (平方根递减模型)
    // ============================================================
    let play_hours = (ctx.sender_play_time as f64) / 3600.0;
    
    // 参数缩放：i64 Micros -> f64
    let base_limit = (ctx.item_base_limit as f64) / MICROS_SCALE;
    let max_limit = (ctx.item_max_limit as f64) / MICROS_SCALE;
    let growth_rate = ctx.item_growth_rate; // 系数保持 f64

    let calculated_limit = base_limit + (growth_rate * play_hours.sqrt());
    let final_limit = calculated_limit.min(max_limit);

    // 拦截判定：比较原始 i64 Micros 以确保绝对精确
    let final_limit_micros = crate::crate::to_micros_saturating(final_limit);
    if ctx.amount_micros > final_limit_micros && final_limit_micros > 0 {
        return TransferResult {
            final_tax_micros: 0,
            is_blocked: 1,
            warning_code: CODE_BLOCK_QUANTITY_LIMIT,
        };
    }

    // ============================================================
    // 2. 行为速率审计 (Behavioral Velocity Audit)
    // ============================================================
    let puppet_factor = if ctx.sender_activity_score < 0.1 {
        ctx.sender_velocity * 2.0 
    } else {
        ctx.sender_velocity / ctx.sender_activity_score.max(0.1)
    };

    if puppet_factor > cfg.velocity_threshold {
        return TransferResult {
            final_tax_micros: 0,
            is_blocked: 1,
            warning_code: CODE_BLOCK_VELOCITY_LIMIT,
        };
    }

    // ============================================================
    // 3. 动态风险评估 (Warning Code)
    // ============================================================
    let mut warning_code = CODE_NORMAL;
    
    if ctx.amount_micros > (final_limit_micros * 85 / 100) || puppet_factor > (cfg.velocity_threshold * 0.7) {
        warning_code = CODE_WARNING_HIGH_RISK;
    }

    // ============================================================
    // 4. 自适应税收计算 (Adaptive Behavioral Tax)
    // ============================================================
    let inflation_adj = 1.0 + ctx.inflation_rate.max(0.0);
    
    // 基础税 + 通胀调节 (基于 f64 运算)
    let mut tax_f64 = amount_f64 * cfg.base_tax_rate * inflation_adj;

    // 惩罚性频率税：指数增长惩罚
    let behavioral_penalty = (ctx.sender_velocity * 0.05).exp(); 
    tax_f64 *= behavioral_penalty;

    // 奢侈税叠加 (i64 Micros -> f64 转换计算)
    let luxury_threshold_f64 = (cfg.luxury_threshold as f64) / MICROS_SCALE;
    if amount_f64 > luxury_threshold_f64 {
        let excess = amount_f64 - luxury_threshold_f64;
        tax_f64 = excess.mul_add(cfg.luxury_tax_rate, tax_f64);
    }

    // 贫富调节税
    let poor_threshold_f64 = (cfg.poor_threshold as f64) / MICROS_SCALE;
    let rich_threshold_f64 = (cfg.rich_threshold as f64) / MICROS_SCALE;
    if sender_bal_f64 < poor_threshold_f64 && receiver_bal_f64 > rich_threshold_f64 {
        let gap_tax = amount_f64 * cfg.wealth_gap_tax_rate;
        tax_f64 = tax_f64.max(gap_tax);
    }

    // 税收封顶修正 (80%)
    let tax_clamped = tax_f64.min(amount_f64 * 0.8);

    TransferResult {
        // 结果转换回 i64 Micros 传回 Java
        final_tax_micros: crate::crate::to_micros_saturating(tax_clamped),
        is_blocked: 0,
        warning_code,
    }
}

/// 判断演算结果是否属于高风险或拦截交易
pub fn is_high_risk_transfer(result: &crate::models::TransferResult) -> bool {
    result.is_blocked == 1
    || result.warning_code == CODE_WARNING_HIGH_RISK
    || result.warning_code == CODE_BLOCK_QUANTITY_LIMIT
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_ctx(amount_micros: i64, sender_balance: i64, sender_play_time: i64,
                 sender_velocity: f64, sender_activity: f64) -> TransferContext {
        TransferContext {
            amount_micros,
            sender_balance,
            receiver_balance: 500_000_000_000,
            inflation_rate: 0.02,
            item_base_limit: 2_364_000_000,
            item_growth_rate: 16.0,
            item_max_limit: 4_728_000_000,
            sender_play_time,
            receiver_play_time: 100_000,
            sender_activity_score: sender_activity,
            sender_velocity,
            ..Default::default()
        }
    }

    fn default_cfg() -> RegulatorConfig {
        RegulatorConfig::default()
    }

    #[test]
    fn test_normal_transfer_passes() {
        let ctx = make_ctx(1_000_000_000, 10_000_000_000, 500_000, 1.0, 0.8);
        let result = compute_transfer_check_internal(&ctx, &default_cfg());
        assert_eq!(result.is_blocked, 0, "normal transfer should not be blocked");
        assert_eq!(result.warning_code, CODE_NORMAL);
    }

    #[test]
    fn test_quantity_limit_blocked() {
        let ctx = make_ctx(100_000_000_000_000, 10_000_000_000, 500_000, 1.0, 0.8);
        let result = compute_transfer_check_internal(&ctx, &default_cfg());
        assert_eq!(result.is_blocked, 1);
        assert_eq!(result.warning_code, CODE_BLOCK_QUANTITY_LIMIT);
    }

    #[test]
    fn test_quantity_limit_respects_playtime_growth() {
        let ctx_veteran = make_ctx(4_500_000_000, 10_000_000_000, 3_600_000, 1.0, 0.8);
        let result_veteran = compute_transfer_check_internal(&ctx_veteran, &default_cfg());
        assert_eq!(result_veteran.is_blocked, 0, "veteran with 1000h should have higher limit");

        let ctx_newbie = make_ctx(4_500_000_000, 10_000_000_000, 36_000, 1.0, 0.8);
        let result_newbie = compute_transfer_check_internal(&ctx_newbie, &default_cfg());
        assert_eq!(result_newbie.is_blocked, 1, "newbie with 10h should be blocked at same amount");
    }

    #[test]
    fn test_puppet_detection_high_frequency() {
        let ctx = make_ctx(1_000_000_000, 10_000_000_000, 500_000, 50.0, 0.05);
        let result = compute_transfer_check_internal(&ctx, &default_cfg());
        assert_eq!(result.is_blocked, 1, "puppet-like behavior should be blocked");
        assert_eq!(result.warning_code, CODE_BLOCK_VELOCITY_LIMIT);
    }

    #[test]
    fn test_normal_user_not_puppet_flagged() {
        let ctx = make_ctx(1_000_000_000, 10_000_000_000, 500_000, 5.0, 1.0);
        let result = compute_transfer_check_internal(&ctx, &default_cfg());
        assert_eq!(result.warning_code, CODE_NORMAL, "normal user should not be flagged");
    }

    #[test]
    fn test_luxury_tax_applied_above_threshold() {
        let ctx = make_ctx(200_000_000_000, 10_000_000_000, 500_000, 1.0, 0.8);
        let result = compute_transfer_check_internal(&ctx, &default_cfg());
        assert!(result.final_tax_micros > 0, "luxury tax should be applied");
    }

    #[test]
    fn test_wealth_gap_tax_applied() {
        let ctx = TransferContext {
            amount_micros: 5_000_000_000,
            sender_balance: 5_000_000_000,
            receiver_balance: 2_000_000_000_000,
            ..make_ctx(5_000_000_000, 5_000_000_000, 500_000, 1.0, 0.8)
        };
        let result = compute_transfer_check_internal(&ctx, &default_cfg());
        assert!(result.final_tax_micros > 0, "wealth gap tax should be applied");
    }

    #[test]
    fn test_tax_capped_at_80_percent() {
        let mut cfg = default_cfg();
        cfg.base_tax_rate = 2.0;
        cfg.luxury_tax_rate = 2.0;
        let ctx = make_ctx(200_000_000_000, 10_000_000_000, 500_000, 100.0, 0.01);
        let result = compute_transfer_check_internal(&ctx, &cfg);
        let tax_f64 = (result.final_tax_micros as f64) / MICROS_SCALE;
        let amount_f64 = (ctx.amount_micros as f64) / MICROS_SCALE;
        assert!(tax_f64 <= amount_f64 * 0.8 + 1e-6, "tax must be capped at 80% of transfer amount");
    }

    #[test]
    fn test_high_risk_warning_near_limit() {
        let ctx = make_ctx(2_000_000_000, 10_000_000_000, 500_000, 1.0, 0.8);
        let result = compute_transfer_check_internal(&ctx, &default_cfg());
        if result.warning_code == CODE_WARNING_HIGH_RISK {
            assert!(is_high_risk_transfer(&result));
        }
    }

    #[test]
    fn test_transfer_result_codes() {
        assert_eq!(CODE_NORMAL, 0);
        assert_eq!(CODE_WARNING_HIGH_RISK, 1);
    }

    #[test]
    fn test_to_micros_saturating_normal() {
        assert_eq!(crate::to_micros_saturating(1.0), 1_000_000);
        assert_eq!(crate::to_micros_saturating(0.5), 500_000);
        assert_eq!(crate::to_micros_saturating(0.0), 0);
    }

    #[test]
    fn test_to_micros_saturating_nan_and_inf() {
        assert_eq!(crate::to_micros_saturating(f64::NAN), 0);
        assert_eq!(crate::to_micros_saturating(f64::INFINITY), i64::MAX);
        assert_eq!(crate::to_micros_saturating(f64::NEG_INFINITY), i64::MIN);
    }
}
