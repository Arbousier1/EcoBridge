// ==================================================
// FILE: ecobridge-rust/src/economy/summation.rs
// ==================================================

//! Effective Volume Summation Module (v1.0 SIMD + In-Memory)
//! 
//! 本模块负责交易量的聚合计算。
//! v1.0: 纯内存操作，使用 AVX2 SIMD 指令集。
//! 
//! 架构变更记录:
//! - [Fix] 引入全局热数据区 (HOT_HISTORY)，移除实时 SQL 依赖。
//! - [Fix] 实现了 hydrate_hot_store 用于启动预热。
//! - [Fix] 实现了 append_trade_to_memory 用于实时双写。

use crate::models::HistoryRecord;
use crate::storage;
use std::sync::RwLock;
use lazy_static::lazy_static;

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

#[cfg(feature = "parallel")]
use rayon::prelude::*;

// ==================== 工业级常量定义 ====================

const PARALLEL_THRESHOLD: usize = 750;
const MS_PER_DAY: f64 = 86_400_000.0;
const MAX_FUTURE_TOLERANCE: i64 = 60_000;

// ==================== 全局内存态 (Hot Memory Layer) ====================

lazy_static! {
    // 使用 RwLock 保证线程安全：多读(计算定价)少写(发生交易)
    // 容量预设 100,000 条，约覆盖繁忙服务器 7-30 天的交易量
    static ref HOT_HISTORY: RwLock<Vec<HistoryRecord>> = RwLock::new(Vec::with_capacity(100_000));
}

/// 初始化加载逻辑 (服务器启动时调用)
/// 从 DuckDB 加载最近 30 天的数据预热内存
pub fn hydrate_hot_store() {
    // 这里的 30 天是为了确保长尾衰减计算正确 (Tau=7.0时，30天外的影响可忽略)
    // 注意: storage::load_recent_history 需要在 storage 模块实现
    let records = storage::load_recent_history(30); 
    let len = records.len();
    
    let mut lock = HOT_HISTORY.write().unwrap();
    *lock = records;
    
    println!("[EcoBridge-Native] SIMD 引擎热数据装填完成: {} 条记录", len);
}

/// 实时双写逻辑 (交易发生时调用)
/// 极低延迟写入，确保下一次计算立即生效
pub fn append_trade_to_memory(ts: i64, amount: f64) {
    let mut lock = HOT_HISTORY.write().unwrap();
    lock.push(HistoryRecord {
        timestamp: ts,
        amount,
    });
    
    // TODO: 生产环境建议添加定期修剪逻辑 (Pruning)
    // 当 size > 500,000 时，移除 30 天前的数据，防止内存无限增长
}

// ==================== 核心接口 (已修复：走内存路径) ====================

/// 计算市场热度 (NEff)
/// 
/// 路径: Java -> JNI -> query_neff_internal -> Global Memory (Read Lock) -> SIMD
/// 特性: 0 I/O, 0 SQL, 纯内存操作, 纳秒级响应
pub fn query_neff_internal(
    current_ts: i64,
    tau: f64,
) -> f64 {
    // 1. 获取全局内存历史的读锁
    // RwLock 在无写锁竞争时极快，开销微乎其微
    let lock = HOT_HISTORY.read().unwrap();
    
    // 2. 将切片传递给 SIMD 计算引擎
    calculate_volume_in_memory(&lock, current_ts, tau)
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

    // 找到最早的有效时间戳，作为相对时间的锚点，防止指数溢出
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

    // Fallback: 标量/并行实现 (用于非 x86 或不支持 AVX2 的环境)
    let compute_partial = |rec: &HistoryRecord| -> f64 {
        if rec.timestamp > valid_future_limit || rec.timestamp < valid_past_limit {
            return 0.0; 
        }
        let dt_rel = rec.timestamp.saturating_sub(t_min) as f64;
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
/// 
/// 警告: 必须确保调用前检查过 cpuid 支持 avx2
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

        // 4. 计算 exp(exponent) (混合模式)
        // AVX2 没有原生的 _mm256_exp_pd，通常需要 SVML 库
        // 这里采用 Rust std::f64::exp 的标量回退优化或假设编译器内联优化
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