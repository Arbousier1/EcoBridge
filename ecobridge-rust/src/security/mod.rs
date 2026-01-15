// =============== ecobridge-rust/src/security/mod.rs ===============

//! Security and Compliance Module (v0.8 Internal)
//!
//! # 模块概述
//!
//! 本模块实现了经济系统的风控与合规引擎。
//! 
//! v0.8 架构变更：
//! - FFI 接口已移至 lib.rs。
//! - 本模块只暴露纯 Rust 实现的 `compute_transfer_check_internal`。

// ==================== 1. 子模块声明 ====================

/// 风控核心逻辑实现
pub mod regulator;

// ==================== 2. 跨模块重导出 ====================

/// 重新导出配置结构体 (SSoT)
pub use crate::models::RegulatorConfig;

/// 重新导出核心逻辑函数与状态码
pub use regulator::{
    // [修复] 使用新的内部函数名
    compute_transfer_check_internal,
    
    // 辅助判断函数
    is_high_risk_transfer,

    // 错误码常量
    CODE_NORMAL,
    CODE_WARNING_HIGH_RISK,
    CODE_BLOCK_REVERSE_FLOW,
    CODE_BLOCK_INJECTION,
    CODE_BLOCK_INSUFFICIENT_FUNDS,
};