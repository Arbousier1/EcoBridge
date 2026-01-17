//! EcoBridge Economy System - Integrated Data Models (SSoT v0.9.1)
//! 
//! # 核心设计准则 (Single Source of Truth)
//! 1. **C-ABI 兼容**: 强制使用 `#[repr(C)]`，禁止编译器重排字段。
//! 2. **扁平化布局**: 确保 Java FFM 映射时的确定性。
//! 3. **显式对齐**: 所有结构体大小均为 8 字节倍数，防止跨平台填充差异。

use libc::{c_double, c_int, c_longlong};

// ==================== 1. 物理控制器状态 (State) ====================

/// 工业级 PID 控制器状态 (72 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct PidState {
    pub kp: c_double,                // Offset 0
    pub ki: c_double,                // Offset 8
    pub kd: c_double,                // Offset 16
    pub lambda: c_double,            // Offset 24
    pub integral: c_double,          // Offset 32
    pub prev_pv: c_double,           // Offset 40
    pub filtered_d: c_double,        // Offset 48
    pub integration_limit: c_double, // Offset 56
    pub is_saturated: c_int,         // Offset 64
    pub _padding: c_int,             // Offset 68
}

impl Default for PidState {
    fn default() -> Self {
        Self {
            kp: 0.5, ki: 0.1, kd: 0.05, lambda: 0.01,
            integral: 0.0, prev_pv: 0.0, filtered_d: 0.0,
            integration_limit: 30.0, is_saturated: 0,
            _padding: 0,
        }
    }
}

// ==================== 2. 交易记录模型 (Records) ====================

/// 单条历史交易快照 (16 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct HistoryRecord {
    pub timestamp: c_longlong, // Offset 0
    pub amount: c_double,      // Offset 8
}

// ==================== 3. 业务演算上下文 (Contexts) ====================

/// 交易定价演算上下文 (64 bytes)
/// 严格对齐 Java 侧 NativeBridge.Layouts.TRADE_CONTEXT
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct TradeContext {
    pub base_price: c_double,          // 0
    pub current_amount: c_double,      // 8
    pub inflation_rate: c_double,      // 16
    pub current_timestamp: c_longlong, // 24
    pub play_time_seconds: c_longlong, // 32
    pub timezone_offset: c_int,        // 40
    pub newbie_mask: c_int,            // 44 (bit0:新手, bit1:节日)
    // --- 新增：宏观调控因子 ---
    pub market_heat: c_double,         // 48: 货币流速 (V)
    pub eco_saturation: c_double,      // 56: 生态饱和度 (0.0-1.0)
}

/// 转账风控审计上下文 (72 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct TransferContext {
    pub amount: c_double,               // 0
    pub sender_balance: c_double,       // 8
    pub receiver_balance: c_double,     // 16
    pub inflation_rate: c_double,       // 24
    pub newbie_limit: c_double,         // 32
    pub sender_play_time: c_longlong,   // 40
    pub receiver_play_time: c_longlong, // 48
    pub sender_activity_score: c_double, // 56
    pub sender_velocity: c_int,         // 64
    pub _padding: c_int,                // 68
}

// ==================== 4. 环境配置模型 (Configs) ====================

/// 市场动态定价配置 (72 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct MarketConfig {
    pub base_lambda: c_double,          // 0
    pub volatility_factor: c_double,    // 8
    pub seasonal_amplitude: c_double,   // 16
    pub weekend_multiplier: c_double,   // 24
    pub newbie_protection_rate: c_double, // 32
    pub seasonal_weight: c_double,      // 40
    pub weekend_weight: c_double,       // 48
    pub newbie_weight: c_double,        // 56
    pub inflation_weight: c_double,     // 64
}

impl Default for MarketConfig {
    fn default() -> Self {
        Self {
            base_lambda: 0.1, volatility_factor: 1.0,
            seasonal_amplitude: 0.15, weekend_multiplier: 1.2,
            newbie_protection_rate: 0.2,
            seasonal_weight: 0.25, weekend_weight: 0.25,
            newbie_weight: 0.25, inflation_weight: 0.25,
        }
    }
}

/// 审计监管与计税配置 (96 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct RegulatorConfig {
    pub base_tax_rate: c_double,       // 0
    pub luxury_threshold: c_double,    // 8
    pub luxury_tax_rate: c_double,     // 16
    pub wealth_gap_tax_rate: c_double, // 24
    pub poor_threshold: c_double,      // 32
    pub rich_threshold: c_double,      // 40
    pub newbie_receive_limit: c_double,// 48
    pub warning_ratio: c_double,       // 56
    pub warning_min_amount: c_double,  // 64
    pub newbie_hours: c_double,        // 72
    pub veteran_hours: c_double,       // 80
    pub velocity_threshold: c_double,  // 88
}

impl Default for RegulatorConfig {
    fn default() -> Self {
        Self {
            base_tax_rate: 0.05, luxury_threshold: 100_000.0,
            luxury_tax_rate: 0.10, wealth_gap_tax_rate: 0.20,
            poor_threshold: 10_000.0, rich_threshold: 1_000_000.0,
            newbie_receive_limit: 50_000.0, warning_ratio: 0.9,
            warning_min_amount: 50_000.0, newbie_hours: 10.0, veteran_hours: 100.0,
            velocity_threshold: 20.0,
        }
    }
}

// ==================== 5. 演算结果集 (Results) ====================

/// 转账演算最终结果 (16 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct TransferResult {
    pub final_tax: c_double,    // 0
    pub is_blocked: c_int,      // 8
    pub warning_code: c_int,    // 12
}

impl TransferResult {
    pub fn error(code: i32) -> Self {
        Self { final_tax: 0.0, is_blocked: 1, warning_code: code }
    }
}

// ==================== 6. 静态布局一致性测试 ====================

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem;

    #[test]
    fn verify_ssot_alignment() {
        assert_eq!(mem::size_of::<PidState>(), 72);
        assert_eq!(mem::size_of::<TradeContext>(), 64); // 更新: 48 -> 64 bytes
        assert_eq!(mem::size_of::<TransferContext>(), 72);
        assert_eq!(mem::size_of::<MarketConfig>(), 72); 
        assert_eq!(mem::size_of::<RegulatorConfig>(), 96);
        assert_eq!(mem::size_of::<TransferResult>(), 16);
        
        assert_eq!(mem::offset_of!(TradeContext, market_heat), 48);
        assert_eq!(mem::offset_of!(TradeContext, eco_saturation), 56);
        assert_eq!(mem::offset_of!(TransferContext, sender_activity_score), 56);
        assert_eq!(mem::offset_of!(RegulatorConfig, velocity_threshold), 88);
    }
}