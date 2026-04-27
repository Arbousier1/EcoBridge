// ==================================================
// FILE: ecobridge-rust/src/economy/summation.rs
// ==================================================

//! Effective Volume Summation Module (v1.6.0 SIMD + Precision Hardened)
//! 
//! 本模块负责交易物品数量（Quantity）的聚合计算。
//! 
//! 变更记录:
//! - [v1.6.0] 适配 i64 Micros 定点数协议，消除浮点累积误差。
//! - [v1.1] 优化: 使用二分查找降至 O(logN + M)。

use crate::models::HistoryRecord;
use crate::storage;
use std::collections::HashMap;
use std::sync::{RwLock, LazyLock};

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

#[cfg(feature = "parallel")]
use rayon::prelude::*;

// ==================== 工业级常量定义 ====================

const PARALLEL_THRESHOLD: usize = 750;
const MS_PER_DAY: f64 = 86_400_000.0;
const MAX_FUTURE_TOLERANCE: i64 = 60_000;
const MICROS_SCALE: f64 = 1_000_000.0; // [v1.6.0] 精度缩放因子
const GLOBAL_MARKET_KEY: &str = "__global__";

// 内存管理阈值
const MAX_HISTORY_SIZE: usize = 500_000;
const PRUNE_TO_SIZE: usize = 400_000;

// ==================== 全局内存态 (Hot Memory Layer) ====================

static HOT_HISTORY_BY_KEY: LazyLock<RwLock<HashMap<String, Vec<HistoryRecord>>>> =
    LazyLock::new(|| RwLock::new(HashMap::new()));

/// 初始化加载逻辑 (服务器启动时调用)
pub fn hydrate_hot_store() {
    let records_by_key = storage::load_recent_market_history_by_key(30);
    let total: usize = records_by_key.values().map(Vec::len).sum();
    let markets = records_by_key.len();

    let mut lock = HOT_HISTORY_BY_KEY.write().unwrap();
    *lock = records_by_key;

    println!(
        "[EcoBridge-Native] v1.6.0 SIMD keyed hot store loaded: {} records across {} markets",
        total, markets
    );
}

/// 实时双写逻辑
/// @param amount 这里的 amount 为原始 double，内部转换为 i64 Micros 存储
pub fn append_trade_to_memory(ts: i64, amount: f64, market_key: &str) {
    let mut lock = HOT_HISTORY_BY_KEY.write().unwrap();

    let amount_micros = (amount * MICROS_SCALE) as i64;
    let push_record = |bucket: &mut Vec<HistoryRecord>| {
        bucket.push(HistoryRecord {
            timestamp: ts,
            amount_micros,
        });
        if bucket.len() > MAX_HISTORY_SIZE {
            let remove_count = bucket.len() - PRUNE_TO_SIZE;
            bucket.drain(0..remove_count);
        }
    };

    let bucket = lock.entry(market_key.to_string()).or_insert_with(|| Vec::with_capacity(1024));
    push_record(bucket);

    // Keep a global aggregate key for compatibility and diagnostics.
    let global_bucket = lock.entry(GLOBAL_MARKET_KEY.to_string()).or_insert_with(|| Vec::with_capacity(4096));
    push_record(global_bucket);
}

// ==================== 核心接口 ====================

pub fn query_neff_internal(
    current_ts: i64,
    tau: f64,
    market_key: &str,
) -> f64 {
    let lock = HOT_HISTORY_BY_KEY.read().unwrap();
    if let Some(history) = lock.get(market_key) {
        return calculate_volume_in_memory(history, current_ts, tau);
    }
    0.0
}

pub fn query_neff_global_internal(current_ts: i64, tau: f64) -> f64 {
    query_neff_internal(current_ts, tau, GLOBAL_MARKET_KEY)
}

// ==================== 内存计算实现 (Binary Search + SIMD) ====================

#[inline]
pub fn calculate_volume_in_memory(
    history: &[HistoryRecord],
    current_time: i64,
    tau: f64,
) -> f64 {
    if history.is_empty() || tau <= 0.0 {
        return 0.0;
    }

    let valid_future_limit = current_time + MAX_FUTURE_TOLERANCE;
    let valid_past_limit = current_time - (tau * MS_PER_DAY * 10.0) as i64;

    let start_idx = history.partition_point(|r| r.timestamp < valid_past_limit);
    let relevant_slice = &history[start_idx..];

    if relevant_slice.is_empty() {
        return 0.0;
    }

    let t_min = relevant_slice[0].timestamp;
    let lambda = 1.0 / (tau * MS_PER_DAY);
    let base_multiplier = (-(current_time - t_min) as f64 * lambda).exp();

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        let sum_partial = unsafe { 
            compute_partial_simd(relevant_slice, t_min, lambda, valid_future_limit, valid_past_limit) 
        };
        // 最终求和时缩放回标准单位
        let result = (sum_partial / MICROS_SCALE) * base_multiplier;
        return if result.is_finite() { result } else { 0.0 };
    }

    // Fallback: 标量实现 (针对 slice)
    let compute_partial = |rec: &HistoryRecord| -> f64 {
        if rec.timestamp > valid_future_limit {
            return 0.0; 
        }
        let dt_rel = rec.timestamp.saturating_sub(t_min) as f64;
        (rec.amount_micros as f64) * (dt_rel * lambda).exp()
    };

    let sum_partial: f64 = if relevant_slice.len() >= PARALLEL_THRESHOLD {
        #[cfg(feature = "parallel")]
        { relevant_slice.par_iter().map(compute_partial).sum() }
        #[cfg(not(feature = "parallel"))]
        { relevant_slice.iter().map(compute_partial).sum() }
    } else {
        relevant_slice.iter().map(compute_partial).sum()
    };

    let result = (sum_partial / MICROS_SCALE) * base_multiplier;
    if result.is_finite() { result } else { 0.0 }
}

/// AVX2 优化的部分和计算
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
    
    let v_tmin = _mm256_set1_pd(t_min as f64);
    let v_lambda = _mm256_set1_pd(lambda);

    let chunks = history.chunks_exact(4);
    let remainder = chunks.remainder();

    for chunk in chunks {
        let t0 = chunk[0].timestamp;
        let t3 = chunk[3].timestamp;
        
        if t3 > valid_future || t0 < valid_past {
            for r in chunk {
                if r.timestamp <= valid_future && r.timestamp >= valid_past {
                     let dt = (r.timestamp - t_min) as f64;
                     let val = (r.amount_micros as f64) * (dt * lambda).exp();
                     let v_val = _mm256_set_pd(0.0, 0.0, 0.0, val);
                     sum_vec = _mm256_add_pd(sum_vec, v_val);
                }
            }
            continue;
        }

        // 核心向量化计算路径
        let v_ts = _mm256_set_pd(
            chunk[3].timestamp as f64,
            chunk[2].timestamp as f64,
            chunk[1].timestamp as f64,
            chunk[0].timestamp as f64,
        );
        let v_amount = _mm256_set_pd(
            chunk[3].amount_micros as f64, // [Fix] 访问 amount_micros
            chunk[2].amount_micros as f64,
            chunk[1].amount_micros as f64,
            chunk[0].amount_micros as f64,
        );

        let v_dt = _mm256_sub_pd(v_ts, v_tmin);
        let v_exponent = _mm256_mul_pd(v_dt, v_lambda);

        let mut arr = [0.0f64; 4];
        _mm256_storeu_pd(arr.as_mut_ptr(), v_exponent);
        arr[0] = arr[0].exp();
        arr[1] = arr[1].exp();
        arr[2] = arr[2].exp();
        arr[3] = arr[3].exp();
        let v_exp = _mm256_loadu_pd(arr.as_ptr());

        let v_partial = _mm256_mul_pd(v_amount, v_exp);
        sum_vec = _mm256_add_pd(sum_vec, v_partial);
    }

    let mut temp = [0.0f64; 4];
    _mm256_storeu_pd(temp.as_mut_ptr(), sum_vec);
    let mut total = temp[0] + temp[1] + temp[2] + temp[3];

    for rec in remainder {
        if rec.timestamp <= valid_future && rec.timestamp >= valid_past {
            let dt = (rec.timestamp - t_min) as f64;
            total += (rec.amount_micros as f64) * (dt * lambda).exp();
        }
    }

    total
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::HistoryRecord;

    fn make_record(ts: i64, amount_micros: i64) -> HistoryRecord {
        HistoryRecord { timestamp: ts, amount_micros }
    }

    #[test]
    fn test_empty_history_returns_zero() {
        let history: Vec<HistoryRecord> = vec![];
        let result = calculate_volume_in_memory(&history, 1_000_000_000, 7.0);
        assert!((result - 0.0).abs() < 1e-10);
    }

    #[test]
    fn test_tau_zero_returns_zero() {
        let history = vec![make_record(1_000_000_000, 1_000_000)];
        let result = calculate_volume_in_memory(&history, 1_000_100_000, 0.0);
        assert!((result - 0.0).abs() < 1e-10);
    }

    #[test]
    fn test_exp_decay_basic() {
        let history = vec![make_record(1_000_000_000, 1_000_000)];
        let result = calculate_volume_in_memory(&history, 1_000_100_000, 7.0);
        assert!(result > 0.0, "recent trade should contribute to n_eff");
    }

    #[test]
    fn test_old_records_decay_to_zero() {
        let old_ts = 1_000_000_000;
        let now = old_ts + (90 * 86_400_000); // 90 days later
        let history = vec![make_record(old_ts, 1_000_000_000)];
        let result = calculate_volume_in_memory(&history, now, 7.0);
        assert!(result < 0.001, "90-day-old record should have decayed to near zero");
    }

    #[test]
    fn test_window_bounds_filtering() {
        let now = 2_000_000_000i64;
        let history = vec![
            make_record(1_000_000_000, 1_000_000),   // old
            make_record(1_999_000_000, 2_000_000),   // recent
            make_record(3_000_000_000, 3_000_000),   // future (should be filtered)
        ];
        let result = calculate_volume_in_memory(&history, now, 7.0);
        assert!(result > 0.0);
    }

    #[test]
    fn test_binary_search_start_finds_correct_index() {
        let now = 2_000_000_000i64;
        let history = vec![
            make_record(0, 1_000_000),
            make_record(500_000_000, 1_000_000),
            make_record(1_000_000_000, 1_000_000),
            make_record(1_950_000_000, 5_000_000), // recent enough
        ];
        let result = calculate_volume_in_memory(&history, now, 30.0);
        assert!(result > 0.0, "should find valid records");
    }

    #[test]
    fn test_non_finite_result_clamped_to_zero() {
        // given a very small tau, lambda becomes huge, potentially causing overflow
        let history = vec![make_record(1_000_000_000, i64::MAX)];
        let result = calculate_volume_in_memory(&history, 1_000_001_000, 0.0001);
        assert!(result.is_finite(), "result should always be finite");
    }
}
