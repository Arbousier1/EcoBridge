use crate::models::{TradeContext, MarketConfig};
use rayon::prelude::*; 
// 修复：使用正确的模块路径导入环境计算逻辑
use crate::economy::environment;

/// 内部核心逻辑：具备行为经济学感知的定价引擎
/// 核心：通过 trade_amount 判断交易方向，实现"价格下行粘性"
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
    // 心理逻辑：损失厌恶。卖出时降低灵敏度(0.6x)，让价格下跌产生"粘性"；
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

// ==================== 阶梯定价与底价保护 ====================

/// 计算阶梯定价 (Tier Pricing)
/// 逻辑复刻 Java 版本，由编译器进行分支预测优化
pub fn compute_tier_price_internal(
    base_price: f64, 
    quantity: f64, 
    is_sell: bool
) -> f64 {
    // 只有卖出 (is_sell=true) 且数量 > 500 才触发阶梯
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

/// 包含动态底价保护的最终价格计算
/// hist_avg: 7日历史均价 (从 Java 传入)
pub fn compute_price_with_floor(
    base: f64, n_eff: f64, trade_amt: f64, lambda: f64, epsilon: f64, 
    hist_avg: f64
) -> f64 {
    // 调用核心行为定价逻辑
    let raw_price = compute_price_behavioral_core(base, n_eff, trade_amt, lambda, epsilon);
    
    // 动态地板价逻辑: Max(历史均价 * 20%, 0.01)
    let floor = (hist_avg * 0.2).max(0.01);
    
    if raw_price < floor {
        floor
    } else {
        raw_price
    }
}

// ==================== 兼容性与 FFI 接口 ====================

#[no_mangle]
pub extern "C" fn ecobridge_compute_price_final(
    base_price: f64,
    n_eff: f64,
    lambda: f64,
    epsilon: f64,
) -> f64 {
    compute_price_behavioral_core(base_price, n_eff, 0.0, lambda, epsilon)
}

#[no_mangle]
pub extern "C" fn ecobridge_compute_price_humane(
    base_price: f64,
    n_eff: f64,
    trade_amount: f64,
    lambda: f64,
    epsilon: f64,
) -> f64 {
    compute_price_behavioral_core(base_price, n_eff, trade_amount, lambda, epsilon)
}

#[no_mangle]
pub extern "C" fn ecobridge_compute_tier_price(
    base_price: f64,
    quantity: f64,
    is_sell: bool,
) -> f64 {
    compute_tier_price_internal(base_price, quantity, is_sell)
}

#[no_mangle]
pub extern "C" fn ecobridge_compute_price_bounded(
    base: f64,
    neff: f64,
    amt: f64,
    lambda: f64,
    eps: f64,
    hist_avg: f64,
) -> f64 {
    compute_price_with_floor(base, neff, amt, lambda, eps, hist_avg)
}

// ==================== 批量价格演算内核 ====================

/// 批量价格演算内核 - 支持并行与 SIMD 加速
/// 该函数由 FFI 调用，内部使用并行迭代器处理大规模商品定价快照
#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_batch_prices(
    count: usize,
    neff: f64,
    ctx_ptr: *const TradeContext,
    cfg_ptr: *const MarketConfig,
    hist_avgs_ptr: *const f64,
    lambdas_ptr: *const f64,
    output_ptr: *mut f64,
) {
    // 转换原始指针为切片（零拷贝读取）
    let ctx_slice = std::slice::from_raw_parts(ctx_ptr, count);
    let cfg_slice = std::slice::from_raw_parts(cfg_ptr, count);
    let hist_avgs = std::slice::from_raw_parts(hist_avgs_ptr, count);
    let lambdas = std::slice::from_raw_parts(lambdas_ptr, count);
    let output = std::slice::from_raw_parts_mut(output_ptr, count);

    // 使用 rayon 的并行迭代器自动分发任务
    // 连续内存布局有利于 LLVM 进行自动向量化优化
    output.par_iter_mut()
        .enumerate()
        .for_each(|(i, price_out)| {
            let ctx = &ctx_slice[i];
            let cfg = &cfg_slice[i];
            let lambda = lambdas[i];
            let hist_avg = hist_avgs[i];

            // 修复：引用正确的环境扰动因子计算函数
            let epsilon = environment::calculate_epsilon_internal(ctx, cfg);

            // 执行带地板保护的定价演算 (amount 为 0.0 用于快照预测)
            *price_out = compute_price_with_floor(
                ctx.base_price, 
                neff, 
                0.0, 
                lambda, 
                epsilon, 
                hist_avg
            );
        });
}