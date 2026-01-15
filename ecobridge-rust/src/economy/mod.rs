// =============== ecobridge-rust/src/economy/mod.rs ===============

pub mod control;
pub mod environment;
pub mod pricing;
pub mod summation;

// 重新导出数据模型，方便其他模块引用
pub use crate::models::{PidState, MarketConfig, TradeContext, HistoryRecord};

// 重新导出核心计算函数 (使用新的 _internal 命名)
pub use control::{
    compute_pid_adjustment_internal,
    validate_pid_params
};

pub use environment::{
    calculate_epsilon_internal
};

pub use pricing::{
    compute_price_final_internal,
    predict_price_advanced
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

    #[test]
    fn test_params_safety_check() {
        assert!(validate_params(0.01, 7.0));
        assert!(!validate_params(0.0, 7.0));
        assert!(!validate_params(f64::NAN, 7.0));
    }

    #[test]
    fn test_economic_pipeline_integration() {
        // 1. 测试 PID 状态重置
        // [修复] 移除冗余的重复赋值，只保留初始化
        let mut pid = PidState::default(); 
        
        // validate_pid_params 返回 bool
        assert!(validate_pid_params(&pid)); 

        let config = MarketConfig::default();
        let ctx = TradeContext {
            base_price: 100.0,
            current_timestamp: 1736851200000,
            newbie_mask: 1,
            inflation_rate: 0.02,
            ..Default::default()
        };

        // 2. 测试 Epsilon 计算
        let eps = calculate_epsilon_internal(&ctx, &config);
        assert!(eps > 0.1 && eps < 10.0);

        // 3. 测试 Neff (Mock)
        // 注意：单元测试中很难测 query_neff_internal 因为它依赖数据库
        // 这里我们可以直接 mock 一个 neff 值传给下一步
        let vol = 36.5; 

        // 4. 测试 PID 计算
        let adjustment = compute_pid_adjustment_internal(&mut pid, 100.0, 95.0, 1.0, ctx.inflation_rate);
        assert!(adjustment.is_finite());

        // 5. 测试最终价格
        let final_price = compute_price_final_internal(100.0, vol, 0.01, eps);
        assert!(final_price > MIN_PHYSICAL_PRICE);
    }

    #[test]
    fn test_extreme_clamping_logic() {
        let base_price = 100.0;
        let infinite_vol = 1e18;
        let lambda = 0.5;
        let eps = 1.0;
        
        let price = compute_price_final_internal(base_price, infinite_vol, lambda, eps);
        
        // 价格不应崩盘
        assert!(price >= MIN_PHYSICAL_PRICE);
    }
}