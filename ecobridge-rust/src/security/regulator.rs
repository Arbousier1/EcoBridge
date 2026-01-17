use crate::models::{TransferContext, TransferResult, RegulatorConfig};

// 状态码常量
pub const CODE_NORMAL: i32 = 0;
pub const CODE_WARNING_HIGH_RISK: i32 = 1;
pub const CODE_BLOCK_REVERSE_FLOW: i32 = 2;
pub const CODE_BLOCK_INJECTION: i32 = 3;
pub const CODE_BLOCK_INSUFFICIENT_FUNDS: i32 = 4;
pub const CODE_BLOCK_VELOCITY_LIMIT: i32 = 5; // [New] 触发频率限制拦截

/// 增强型交易审计逻辑 (v0.9.0 - Behavioral Audit)
pub fn compute_transfer_check_internal(
    ctx: &TransferContext,
    cfg: &RegulatorConfig,
) -> TransferResult {
    let amount = ctx.amount.max(0.0);
    let sender_bal = ctx.sender_balance.max(0.0);
    let receiver_bal = ctx.receiver_balance.max(0.0);
    
    // 1. 基础余额硬检查
    if amount > sender_bal {
        return TransferResult {
            final_tax: 0.0,
            is_blocked: 1,
            warning_code: CODE_BLOCK_INSUFFICIENT_FUNDS,
        };
    }

    // 2. 行为速率审计 (Behavioral Velocity Audit)
    // 利用 Java 层传来的 Activity Score 和近期交易频率进行交叉比对
    // ctx.sender_activity_score: 0.0 ~ 1.0 (来自 ActivityCollector)
    // ctx.sender_velocity: 近期单位时间内的交易次数
    
    // 计算“傀儡因子”：频率越高且活跃度越低，分值越高
    let puppet_factor = if ctx.sender_activity_score < 0.1 {
        ctx.sender_velocity as f64 * 2.0 // 极低活跃度账号高频操作，风险翻倍
    } else {
        ctx.sender_velocity as f64 / ctx.sender_activity_score.max(0.1)
    };

    // 如果傀儡因子超过阈值（例如 20.0），直接判定为洗钱拦截
    if puppet_factor > cfg.velocity_threshold {
        return TransferResult {
            final_tax: 0.0,
            is_blocked: 1,
            warning_code: CODE_BLOCK_VELOCITY_LIMIT,
        };
    }

    // 3. 逆向流转拦截 (基于时间快照)
    let newbie_threshold_sec = cfg.newbie_hours * 3600.0;
    let veteran_threshold_sec = cfg.veteran_hours * 3600.0;
    
    if (ctx.sender_play_time as f64) < newbie_threshold_sec 
        && (ctx.receiver_play_time as f64) > veteran_threshold_sec 
        && amount > ctx.newbie_limit 
    {
        return TransferResult {
            final_tax: 0.0,
            is_blocked: 1,
            warning_code: CODE_BLOCK_REVERSE_FLOW
        };
    }

    // 4. 动态风险评估 (Warning Code)
    let mut warning_code = CODE_NORMAL;
    let risk_ratio = amount / sender_bal.max(1.0);
    
    // 如果傀儡因子较高但未达拦截线，标记为高风险交易
    if risk_ratio > cfg.warning_ratio || puppet_factor > (cfg.velocity_threshold * 0.7) {
        warning_code = CODE_WARNING_HIGH_RISK;
    }

    // 5. 自适应税收计算 (Adaptive Behavioral Tax)
    let inflation_adj = 1.0 + ctx.inflation_rate.max(0.0);
    
    // 基础税 + 通胀调节
    let mut tax = amount * cfg.base_tax_rate * inflation_adj;

    // 惩罚性频率税：对抗账户拆分 (Split-Transaction Defense)
    // 税率随频率非线性增长：Tax = Tax * (1 + velocity_factor)
    let behavioral_penalty = (ctx.sender_velocity as f64 * 0.05).exp(); // 指数增长惩罚
    tax *= behavioral_penalty;

    // 奢侈税叠加
    if amount > cfg.luxury_threshold {
        let excess = amount - cfg.luxury_threshold;
        tax = excess.mul_add(cfg.luxury_tax_rate, tax);
    }

    // 贫富调节税
    if sender_bal < cfg.poor_threshold && receiver_bal > cfg.rich_threshold {
        let gap_tax = amount * cfg.wealth_gap_tax_rate;
        tax = tax.max(gap_tax);
    }

    // 税收封顶修正 (最高不准超过交易额的 80%，行为税极其严厉)
    let tax_clamped = tax.min(amount * 0.8);

    TransferResult {
        final_tax: tax_clamped,
        is_blocked: 0,
        warning_code,
    }
}

/// 判断演算结果是否属于高风险交易
/// 被 mod.rs 调用并导出给 FFI 逻辑使用
pub fn is_high_risk_transfer(result: &crate::models::TransferResult) -> bool {
    // 满足以下任一条件即视为高风险：
    // 1. 交易被直接拦截 (is_blocked == 1)
    // 2. 触发了高风险预警状态码
    result.is_blocked == 1 || result.warning_code == CODE_WARNING_HIGH_RISK
}