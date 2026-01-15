// =============== ecobridge-rust/src/economy/pricing.rs ===============

/// 内部核心逻辑：具备行为经济学感知的定价引擎
/// 核心：通过 trade_amount 判断交易方向，实现“价格下行粘性”
fn compute_price_behavioral_core(
    base_price: f64,
    n_eff: f64,
    trade_amount: f64,        // 交易增量：正数为卖出(供应增)，负数为买入(需求增)
    lambda: f64,
    epsilon: f64,
) -> f64 {
    // 1. 安全性检查：防止 NaN 污染
    if !base_price.is_finite() || !n_eff.is_finite() || 
       !lambda.is_finite() || !epsilon.is_finite() {
        return 0.01;
    }

    // 2. 非对称灵敏度 (Asymmetric Sensitivity)
    // 心理逻辑：损失厌恶。卖出时降低灵敏度(0.6x)，让价格下跌产生“粘性”；
    // 买入时维持原灵敏度，让价格回升更快。
    let adj_lambda = if trade_amount > 0.0 {
        lambda * 0.6 
    } else {
        lambda
    };

    // 3. 计算包含本次交易冲击的有效累积量
    let total_n = n_eff + trade_amount;

    // 4. 指数演算与软限幅 (Soft Clamping)
    let raw_exponent = (-adj_lambda * total_n).clamp(-100.0, 100.0);
    
    // 使用 tanh 平滑极端波动，防止价格因大规模工业产出瞬间归零
    let clamped_exponent = 10.0 * (raw_exponent / 10.0).tanh();
    
    let final_price = base_price * epsilon * clamped_exponent.exp();
    
    // 5. 价格地板保护
    final_price.max(0.01)
}

// ==================== 修复 unresolved imports 的兼容性接口 ====================

/// 兼容旧代码：计算当前静态物理价格 (trade_amount 默认为 0)
#[no_mangle]
pub fn compute_price_final_internal(
    base_price: f64,
    n_eff: f64,
    lambda: f64,
    epsilon: f64,
) -> f64 {
    compute_price_behavioral_core(base_price, n_eff, 0.0, lambda, epsilon)
}

/// 兼容旧代码：高级价格预测逻辑
#[no_mangle]
pub fn predict_price_advanced(
    base_price: f64,
    n_eff_current: f64,
    trade_amount_amount: f64,
    lambda: f64,
    eps_future: f64,
) -> f64 {
    compute_price_behavioral_core(base_price, n_eff_current, trade_amount_amount, lambda, eps_future)
}

// ==================== FFI 导出层 (供 Java NativeBridge 调用) ====================

/// 具备人性化调节的 FFI 入口
#[no_mangle]
pub fn compute_price_humane_internal(
    base_price: f64,
    n_eff: f64,
    trade_amount: f64,
    base_lambda: f64,
    epsilon: f64,
) -> f64 {
    compute_price_behavioral_core(base_price, n_eff, trade_amount, base_lambda, epsilon)
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_behavioral_consistency() {
        let base = 100.0;
        let lambda = 0.01;
        
        // 同样的交易量，卖出(下跌)的幅度应小于买入(上涨)的幅度，体现粘性
        let p_sell = compute_price_behavioral_core(base, 0.0, 10.0, lambda, 1.0);
        let p_buy = compute_price_behavioral_core(base, 0.0, -10.0, lambda, 1.0);
        
        let drop = base - p_sell;
        let rise = p_buy - base;
        
        assert!(drop < rise, "下跌应比上涨更平缓 (损失厌恶保护)");
    }
}