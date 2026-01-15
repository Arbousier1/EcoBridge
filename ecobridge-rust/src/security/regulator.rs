// =============== ecobridge-rust/src/security/regulator.rs ===============
use crate::models::{TransferContext, TransferResult, RegulatorConfig};

// 状态码常量 (保持 pub 以便其他 Rust 模块引用)
pub const CODE_NORMAL: i32 = 0;
pub const CODE_WARNING_HIGH_RISK: i32 = 1;
pub const CODE_BLOCK_REVERSE_FLOW: i32 = 2;
pub const CODE_BLOCK_INJECTION: i32 = 3;
pub const CODE_BLOCK_INSUFFICIENT_FUNDS: i32 = 4;

/// 纯 Rust 实现的交易审计逻辑
pub fn compute_transfer_check_internal(
    ctx: &TransferContext,
    cfg: &RegulatorConfig,
) -> TransferResult {
    let amount = ctx.amount.max(0.0);
    let sender_bal = ctx.sender_balance.max(0.0);
    let receiver_bal = ctx.receiver_balance.max(0.0);
    
    // 时间转换：秒
    let s_seconds = ctx.sender_play_time as f64;
    let r_seconds = ctx.receiver_play_time as f64;
    
    let newbie_threshold_sec = cfg.newbie_hours * 3600.0;
    let veteran_threshold_sec = cfg.veteran_hours * 3600.0;
    
    // 动态限制
    let newbie_limit = if ctx.newbie_limit > 0.0 {
        ctx.newbie_limit
    } else {
        5000.0
    };

    // 1. 余额硬检查
    if amount > sender_bal {
        return TransferResult {
            final_tax: 0.0,
            is_blocked: 1,
            warning_code: CODE_BLOCK_INSUFFICIENT_FUNDS,
        };
    }

    // 2. 逆向流转拦截 (新手 -> 老手 的大额转账)
    if s_seconds < newbie_threshold_sec && r_seconds > veteran_threshold_sec && amount > newbie_limit {
        return TransferResult {
            final_tax: 0.0,
            is_blocked: 1,
            warning_code: CODE_BLOCK_REVERSE_FLOW
        };
    }

    // 3. 资金注入拦截 (老手 -> 新手 的超额注入)
    if s_seconds > veteran_threshold_sec && r_seconds < newbie_threshold_sec {
        if (receiver_bal + amount) > cfg.newbie_receive_limit {
            return TransferResult {
                final_tax: 0.0,
                is_blocked: 1,
                warning_code: CODE_BLOCK_INJECTION
            };
        }
    }

    // 4. 风险评级 (Warning)
    let mut warning_code = CODE_NORMAL;
    let risk_ratio = amount / sender_bal.max(1.0);
    let dynamic_warning_min = cfg.warning_min_amount * (1.0 + ctx.inflation_rate.max(0.0));
    
    if risk_ratio > cfg.warning_ratio && amount > dynamic_warning_min {
        warning_code = CODE_WARNING_HIGH_RISK;
    }

    // 5. 动态税收计算
    let inflation_adj = 1.0 + ctx.inflation_rate.max(0.0);
    let mut base_plus_luxury_tax = amount * cfg.base_tax_rate * inflation_adj;

    // 奢侈税
    if amount > cfg.luxury_threshold {
        let excess = amount - cfg.luxury_threshold;
        base_plus_luxury_tax = excess.mul_add(cfg.luxury_tax_rate, base_plus_luxury_tax);
    }

    let mut final_tax = base_plus_luxury_tax;

    // 贫富调节税 (穷 -> 富 额外征税)
    if sender_bal < cfg.poor_threshold && receiver_bal > cfg.rich_threshold {
        let gap_tax = amount * cfg.wealth_gap_tax_rate;
        final_tax = final_tax.max(gap_tax);
    }

    // 税收封顶 (防止税收超过转账额 50%)
    let final_tax_clamped = final_tax.min(amount * 0.5);

    TransferResult {
        final_tax: final_tax_clamped,
        is_blocked: 0,
        warning_code,
    }
}

// 辅助函数：判断是否高风险
pub fn is_high_risk_transfer(result: &TransferResult) -> bool {
    result.is_blocked == 1 || result.warning_code == CODE_WARNING_HIGH_RISK
}