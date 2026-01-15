// =============== ecobridge-rust/src/economy/pricing.rs ===============

/// 纯 Rust 实现的最终价格公式
/// Formula: P = P0 * ε * exp(-λ * Neff)
pub fn compute_price_final_internal(
    base_price: f64,
    n_eff: f64,
    lambda: f64,
    epsilon: f64,
) -> f64 {
    // 安全性检查：防止 NaN 污染扩散
    if !base_price.is_finite() || !n_eff.is_finite() ||
       !lambda.is_finite() || !epsilon.is_finite() {
        return 0.01; // 安全回退值
    }

    // 指数部分计算 (带软限幅)
    let raw_exponent = (-lambda * n_eff).clamp(-100.0, 100.0);
    // 使用 tanh 进行平滑限制，防止极端价格波动
    let clamped_exponent = 10.0 * (raw_exponent / 10.0).tanh();
    
    let final_price = base_price * epsilon * clamped_exponent.exp();
    
    // 价格地板保护 (0.01)
    if final_price.is_finite() && final_price > 0.0 {
        final_price.max(0.01)
    } else {
        0.01
    }
}

/// 高级价格预测 (可选功能，供未来 Rust 内部调用)
pub fn predict_price_advanced(
    base_price: f64,
    n_eff_current: f64,
    delta_amount: f64,
    lambda: f64,
    eps_future: f64,
) -> f64 {
    let n_eff_future = n_eff_current + delta_amount;
    compute_price_final_internal(base_price, n_eff_future, lambda, eps_future)
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_nan_resilience() {
        let p = compute_price_final_internal(f64::NAN, 100.0, 0.1, 1.0);
        assert_eq!(p, 0.01, "Should return safe fallback for NaN input");
    }

    #[test]
    fn test_tanh_soft_clamping() {
        let base = 100.0;
        // 模拟极端抛售
        let p = compute_price_final_internal(base, -1_000_000.0, 1.0, 1.0);
        let limit = base * 10.0f64.exp();
        assert!(p < limit); // 价格不应爆炸
        assert!(p > base);
    }
}