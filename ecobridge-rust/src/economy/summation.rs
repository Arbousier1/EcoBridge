// =============== ecobridge-rust/src/economy/summation.rs ===============

//! Effective Volume Summation Module (v0.8.5 SIMD Enhanced)
//! 
//! 本模块负责交易量的聚合计算。
//! v0.8.5: 引入 AVX2 SIMD 指令集加速内存计算。

use crate::models::HistoryRecord;
use crate::storage;

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

#[cfg(feature = "parallel")]
use rayon::prelude::*;

// ==================== 工业级常量定义 ====================

const PARALLEL_THRESHOLD: usize = 750;
const MS_PER_DAY: f64 = 86_400_000.0;
const MAX_FUTURE_TOLERANCE: i64 = 60_000;

// ==================== 核心接口 ====================

pub fn query_neff_internal(
    current_ts: i64,
    tau: f64,
) -> f64 {
    storage::query_neff_from_db(current_ts, tau)
}

// ==================== 内存计算实现 (SIMD 加速版) ====================

pub fn calculate_volume_in_memory(
    history: &[HistoryRecord],
    current_time: i64,
    tau: f64,
) -> f64 {
    if history.is_empty() || tau <= 0.0 {
        return 0.0;
    }

    let lambda = 1.0 / (tau * MS_PER_DAY);
    let valid_future_limit = current_time + MAX_FUTURE_TOLERANCE;
    let valid_past_limit = current_time - (tau * MS_PER_DAY * 10.0) as i64;

    // 数据清洗闭包
    let is_valid_record = |r: &&HistoryRecord| -> bool {
        r.timestamp <= valid_future_limit && r.timestamp >= valid_past_limit
    };

    let t_min = history.iter()
        .filter(is_valid_record)
        .map(|r| r.timestamp)
        .min()
        .unwrap_or(current_time);

    let base_multiplier = (-(current_time - t_min) as f64 * lambda).exp();

    // 尝试使用 AVX2 加速
    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        // 安全地调用 unsafe 的 SIMD 函数
        let sum_partial = unsafe { 
            compute_partial_simd(history, t_min, lambda, valid_future_limit, valid_past_limit) 
        };
        let result = sum_partial * base_multiplier;
        return if result.is_finite() { result } else { 0.0 };
    }

    // Fallback: 标量/并行实现 (原逻辑)
    let compute_partial = |rec: &HistoryRecord| -> f64 {
        if rec.timestamp > valid_future_limit || rec.timestamp < valid_past_limit {
            return 0.0; 
        }
        let dt_rel = (rec.timestamp - t_min) as f64;
        rec.amount * (dt_rel * lambda).exp()
    };

    let sum_partial: f64 = if history.len() >= PARALLEL_THRESHOLD {
        #[cfg(feature = "parallel")]
        {
            history.par_iter().map(compute_partial).sum()
        }
        #[cfg(not(feature = "parallel"))]
        {
            history.iter().map(compute_partial).sum()
        }
    } else {
        history.iter().map(compute_partial).sum()
    };

    let result = sum_partial * base_multiplier;
    if result.is_finite() { result } else { 0.0 }
}

/// AVX2 优化的部分和计算
/// 使用 4 路并行处理 f64
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn compute_partial_simd(
    history: &[HistoryRecord], 
    t_min: i64, 
    lambda: f64,
    valid_future: i64,
    valid_past: i64
) -> f64 {
    let mut sum_vec = _mm256_setzero_pd();
    
    // 广播常量
    let v_tmin = _mm256_set1_pd(t_min as f64);
    let v_lambda = _mm256_set1_pd(lambda);

    // 批量处理 4 个元素
    let chunks = history.chunks_exact(4);
    let remainder = chunks.remainder();

    for chunk in chunks {
        // 1. 快速过滤检查 (标量检查，避免 SIMD 分支的复杂性)
        // 如果整个块都在时间范围内，使用 SIMD；否则逐个处理或部分处理
        // 为了性能，我们假设大部分数据是有效的，直接计算，无效数据通过掩码处理会更复杂，
        // 这里简化为：先加载，如果数据无效，则在计算 exp 前将其设为极小值或 0
        // 但为了保持 "clean code" 和正确性，我们这里做简单的标量预检
        let t0 = chunk[0].timestamp; let t1 = chunk[1].timestamp;
        let t2 = chunk[2].timestamp; let t3 = chunk[3].timestamp;
        
        if t0 > valid_future || t0 < valid_past || 
           t1 > valid_future || t1 < valid_past ||
           t2 > valid_future || t2 < valid_past ||
           t3 > valid_future || t3 < valid_past {
               // 有脏数据，回退到标量处理这个块
               for r in chunk {
                   if r.timestamp <= valid_future && r.timestamp >= valid_past {
                        let dt = (r.timestamp - t_min) as f64;
                        let val = r.amount * (dt * lambda).exp();
                        // 累加到 sum_vec 的第一个元素 (低效但正确)
                        let v_val = _mm256_set_pd(0.0, 0.0, 0.0, val);
                        sum_vec = _mm256_add_pd(sum_vec, v_val);
                   }
               }
               continue;
        }

        // 2. 加载时间戳和金额
        let v_ts = _mm256_set_pd(
            chunk[3].timestamp as f64,
            chunk[2].timestamp as f64,
            chunk[1].timestamp as f64,
            chunk[0].timestamp as f64,
        );
        let v_amount = _mm256_set_pd(
            chunk[3].amount,
            chunk[2].amount,
            chunk[1].amount,
            chunk[0].amount,
        );

        // 3. 计算 exponent = (ts - t_min) * lambda
        let v_dt = _mm256_sub_pd(v_ts, v_tmin);
        let v_exponent = _mm256_mul_pd(v_dt, v_lambda);

        // 4. 计算 exp(exponent)
        // 由于 std 没有 SIMD exp，我们使用简单的多项式近似或混合策略
        // 为保证精度，这里演示 "提取-计算-打包" 策略 (对于 exp 这种复杂操作，纯 SIMD 实现很长)
        // 如果需要极致性能，应使用 `sleef` 或手写 Padé 近似。这里采用混合模式：
        let mut arr = [0.0f64; 4];
        _mm256_storeu_pd(arr.as_mut_ptr(), v_exponent);
        arr[0] = arr[0].exp();
        arr[1] = arr[1].exp();
        arr[2] = arr[2].exp();
        arr[3] = arr[3].exp();
        let v_exp = _mm256_loadu_pd(arr.as_ptr());

        // 5. 累加: sum += amount * exp
        let v_partial = _mm256_mul_pd(v_amount, v_exp);
        sum_vec = _mm256_add_pd(sum_vec, v_partial);
    }

    // 归约求和
    let mut temp = [0.0f64; 4];
    _mm256_storeu_pd(temp.as_mut_ptr(), sum_vec);
    let mut total = temp[0] + temp[1] + temp[2] + temp[3];

    // 处理剩余元素
    for rec in remainder {
        if rec.timestamp <= valid_future && rec.timestamp >= valid_past {
            let dt = (rec.timestamp - t_min) as f64;
            total += rec.amount * (dt * lambda).exp();
        }
    }

    total
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vectorized_exponential_decay() {
        let now = 200_000_000;
        let one_day = 86_400_000;
        
        let records = vec![
            HistoryRecord { timestamp: now, amount: 100.0 },
            HistoryRecord { timestamp: now - one_day, amount: 100.0 },
            HistoryRecord { timestamp: now - 2 * one_day, amount: 100.0 },
        ];

        let total_vol = calculate_volume_in_memory(&records, now, 1.0);
        let expected = 100.0 * (1.0 + (-1.0f64).exp() + (-2.0f64).exp());
        
        assert!((total_vol - expected).abs() < 1e-10);
    }

    #[test]
    fn test_large_timestamp_overflow_safety() {
        let now = 1_700_000_000_000;
        let records = vec![
            HistoryRecord { timestamp: now, amount: 100.0 },
        ];
        
        let res = calculate_volume_in_memory(&records, now, 1.0);
        assert!(!res.is_infinite());
        assert!((res - 100.0).abs() < 1e-10);
    }

    #[test]
    fn test_future_timestamp_attack_resilience() {
        let now = 1_700_000_000_000;
        let records = vec![
            HistoryRecord { timestamp: now, amount: 100.0 },
            HistoryRecord { timestamp: now + 1_000_000_000_000, amount: 1_000_000.0 }, 
            HistoryRecord { timestamp: now + 65_000, amount: 500.0 },
        ];

        let res = calculate_volume_in_memory(&records, now, 1.0);
        
        assert!(!res.is_infinite(), "Result should not be infinite");
        assert!((res - 100.0).abs() < 1e-5, "Should ignore future timestamps");
    }
}