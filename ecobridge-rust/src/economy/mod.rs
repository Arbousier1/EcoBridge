// ==================================================
// FILE: ecobridge-rust/src/economy/mod.rs (v1.7.0)
// ==================================================

pub mod control;
pub mod environment;

#[path = "forecast.rs"]
pub mod forecast;

#[path = "kalman.rs"]
pub mod kalman;

pub mod pricing;

#[path = "summation.rs"]
pub mod summation;

#[path = "volatility.rs"]
pub mod volatility;

// Re-export stateful modules for cross-module usage
pub use volatility::garch_volatility_multiplier;

// 重新导出数据模型 (SSoT v1.6.0 - Precision Hardened)
pub use crate::models::{PidState, MarketConfig, TradeContext, HistoryRecord};

// 重新导出核心计算函数
pub use control::{
    compute_pid_adjustment_internal,
    validate_pid_params
};

pub use environment::{
    calculate_epsilon_internal
};

pub use pricing::{
    compute_price_final_internal,
    compute_price_humane_internal,
    compute_tier_price_internal,
    compute_price_bounded_internal
};

pub use summation::{
    query_neff_internal
};

// -----------------------------------------------------------------------------
// 默认参数定义
// -----------------------------------------------------------------------------
pub const DEFAULT_LAMBDA: f64 = 0.01;
pub const DEFAULT_TAU: f64 = 7.0;
pub const MIN_PHYSICAL_PRICE: f64 = 0.01;

#[inline]
pub fn get_default_params() -> (f64, f64) {
    (DEFAULT_LAMBDA, DEFAULT_TAU)
}

#[inline]
pub fn validate_params(lambda: f64, tau: f64) -> bool {
    lambda.is_finite() && lambda > 0.0 &&
    tau.is_finite() && tau > 0.0
}

#[cfg(test)]
mod tests {
    use super::*;

    const MICROS: i64 = 1_000_000;

    #[test]
    fn test_params_safety_check() {
        assert!(validate_params(0.01, 7.0));
        assert!(!validate_params(0.0, 7.0));
        assert!(!validate_params(f64::NAN, 7.0));
    }

    #[test]
    fn test_economic_pipeline_integration() {
        // 1. 初始化 PID 状态
        let mut pid = PidState::default(); 
        assert!(validate_pid_params(&pid)); 

        let config = MarketConfig::default();
        let ctx = TradeContext {
            // [Precision Fix]: 字段名已修改为 base_price_micros，类型为 i64
            base_price_micros: 100 * MICROS, 
            current_timestamp: 1736851200000,
            newbie_mask: 1,
            inflation_rate: 0.02,
            market_heat: 0.05,
            ..Default::default()
        };

        // 2. 测试 Epsilon 环境因子计算
        let eps = calculate_epsilon_internal(&ctx, &config);
        assert!(eps > 0.1 && eps < 10.0);

        // 3. 模拟有效物品供应量 (Neff)
        let vol = 36.5; 

        // 4. 测试 PID 调节计算 (保持 f64 接口进行 PID 运算)
        let adjustment = compute_pid_adjustment_internal(
            &mut pid, 
            100.0, 
            95.0, 
            1.0, 
            ctx.inflation_rate,
            ctx.market_heat
        );
        assert!(adjustment.is_finite());

        // 5. 测试最终价格演算 (物品数量驱动)
        // [Precision Fix]: 第一个参数现在要求 i64 Micros
        let final_price = compute_price_final_internal(100 * MICROS, vol, 0.01, eps);
        assert!(final_price > MIN_PHYSICAL_PRICE);
    }

    #[test]
    fn test_extreme_clamping_logic() {
        let base_price_micros = 100 * MICROS;
        let infinite_vol = 1e18; // 模拟极端物品产出冲击
        let lambda = 0.5;
        let eps = 1.0;
        
        // [Precision Fix]: 传入 i64 基础价格
        let price = compute_price_final_internal(base_price_micros, infinite_vol, lambda, eps);
        
        // 验证软限幅逻辑：即使供应量无限，价格也不应跌破硬底线
        assert!(price >= MIN_PHYSICAL_PRICE);
    }
}