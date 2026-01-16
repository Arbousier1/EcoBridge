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
    
    // 5. 绝对硬底线 (0.01)
    final_price.max(0.01)
}

// ==================== [新增] 阶梯定价与底价保护 ====================

/// [新增] 计算阶梯定价 (Tier Pricing)
/// 逻辑完全复刻 Java 版本，但由编译器优化分支预测
pub fn compute_tier_price_internal(
    base_price: f64, 
    quantity: f64, 
    is_sell: bool
) -> f64 {
    // 只有卖出 (is_sell=true) 且数量 > 500 才触发阶梯
    // 或者根据你的业务逻辑调整
    if !is_sell || quantity <= 500.0 || quantity <= 0.0 {
        return base_price;
    }

    let mut total_value = 0.0;
    let mut remaining = quantity;

    // Tier 1: 0 - 500 (100%)
    let t1 = remaining.min(500.0);
    total_value += t1 * base_price;
    remaining -= t1;

    // Tier 2: 501 - 2000 (85%)
    if remaining > 0.0 {
        let t2 = remaining.min(1500.0);
        total_value += t2 * (base_price * 0.85);
        remaining -= t2;
    }

    // Tier 3: 2000+ (60%)
    if remaining > 0.0 {
        total_value += remaining * (base_price * 0.60);
    }

    total_value / quantity
}

/// [增强] 包含动态底价保护的最终价格计算
/// 
/// 这个函数整合了核心定价逻辑 + 动态地板价检查。
/// hist_avg: 7日历史均价 (从 Java 传入)
pub fn compute_price_with_floor(
    base: f64, n_eff: f64, trade_amt: f64, lambda: f64, epsilon: f64, 
    hist_avg: f64
) -> f64 {
    // 调用核心行为定价逻辑
    let raw_price = compute_price_humane_internal(base, n_eff, trade_amt, lambda, epsilon);
    
    // 动态地板价逻辑: Max(历史均价 * 20%, 0.01)
    let floor = (hist_avg * 0.2).max(0.01);
    
    if raw_price < floor {
        floor
    } else {
        raw_price
    }
}

// ==================== 兼容性接口 ====================

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

    #[test]
    fn test_tier_pricing() {
        let base = 10.0;
        
        // Case 1: <= 500 (No tier)
        assert_eq!(compute_tier_price_internal(base, 100.0, true), 10.0);
        
        // Case 2: 1000 (500@10.0 + 500@8.5) / 1000 = (5000 + 4250) / 1000 = 9.25
        let p2 = compute_tier_price_internal(base, 1000.0, true);
        assert!((p2 - 9.25).abs() < 1e-6);
        
        // Case 3: Buy (No tier)
        assert_eq!(compute_tier_price_internal(base, 1000.0, false), 10.0);
    }

    #[test]
    fn test_floor_protection() {
        // [Fix] 删除未使用的 base 变量
        // let base = 10.0; <--- 原代码此处报警
        
        let hist_avg = 50.0; // Floor = 50.0 * 0.2 = 10.0
        
        // Case 1: Normal price (e.g. 12.0) > Floor (10.0) -> Return 12.0
        // Simulate by setting lambda=0 so calculation returns base input
        let p1 = compute_price_with_floor(12.0, 0.0, 0.0, 0.0, 1.0, hist_avg);
        assert_eq!(p1, 12.0);
        
        // Case 2: Crash price (e.g. 5.0) < Floor (10.0) -> Return 10.0
        let p2 = compute_price_with_floor(5.0, 0.0, 0.0, 0.0, 1.0, hist_avg);
        assert_eq!(p2, 10.0);
    }
}