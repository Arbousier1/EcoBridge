//! Security and Compliance Module (v0.9.0 - Behavioral Audit Integration)

// ==================== 1. 子模块声明 ====================

/// 风控核心逻辑实现 (包含账户拆分防御与傀儡账户识别)
pub mod regulator;

// ==================== 2. 跨模块重导出 ====================

/// 重新导出配置结构体 (SSoT)
pub use crate::models::RegulatorConfig;

/// 重新导出核心逻辑函数与状态码
/// 确保所有符号在 security 命名空间下可用
pub use regulator::{
    // 核心审计函数
    compute_transfer_check_internal,
    
    // 辅助判断函数 (此处必须在 regulator.rs 中有定义)
    is_high_risk_transfer,

    // 审计状态码常量
    CODE_NORMAL,                   // 0: 正常交易
    CODE_WARNING_HIGH_RISK,        // 1: 高风险预警
    CODE_BLOCK_REVERSE_FLOW,       // 2: 拦截逆向流转 (新手->老手)
    CODE_BLOCK_INJECTION,          // 3: 拦截非正常注资 (老手->新手)
    CODE_BLOCK_INSUFFICIENT_FUNDS, // 4: 拦截余额不足
    CODE_BLOCK_VELOCITY_LIMIT,     // 5: 拦截异常交易频率 (账户拆分/洗钱)
};