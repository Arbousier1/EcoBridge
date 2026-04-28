// ==================================================
// FILE: ecobridge-rust/src/storage.rs (v2.0 — H2 migration)
// ==================================================
// [v2.0] DuckDB replaced by H2 (pure Java). This module now only maintains
// the in-memory hot history layer used by summation.rs for SIMD computation.
// All persistence is handled by the Java side via EventLogDao (H2).

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{RwLock, LazyLock};
use std::collections::HashMap;
use crate::models::HistoryRecord;

// ==================== In-Memory Hot Store (SSoT for SIMD) ====================

static GLOBAL_HISTORY: LazyLock<RwLock<Vec<HistoryRecord>>> =
    LazyLock::new(|| RwLock::new(Vec::with_capacity(200_000)));

// Keyed store for per-market summation
static HOT_HISTORY_BY_KEY: LazyLock<RwLock<HashMap<String, Vec<HistoryRecord>>>> =
    LazyLock::new(|| RwLock::new(HashMap::new()));

static TOTAL_LOGS: AtomicU64 = AtomicU64::new(0);
static DROPPED_LOGS: AtomicU64 = AtomicU64::new(0);

const MAX_HISTORY_SIZE: usize = 500_000;
const PRUNE_TO_SIZE: usize = 400_000;

// ==================== Public API ====================

/// Append a single trade record to the in-memory hot store.
/// Called from Java via FFI after H2 persistence succeeds.
pub fn append_to_memory(ts: i64, amount: f64, market_key: &str) {
    let amount_micros = (amount * 1_000_000.0) as i64;
    let record = HistoryRecord { timestamp: ts, amount_micros };

    // Global store
    if let Ok(mut hist) = GLOBAL_HISTORY.write() {
        hist.push(record);
        if hist.len() > MAX_HISTORY_SIZE {
            let remove = hist.len() - PRUNE_TO_SIZE;
            hist.drain(0..remove);
        }
    }

    // Keyed store
    if let Ok(mut map) = HOT_HISTORY_BY_KEY.write() {
        let bucket = map.entry(market_key.to_string())
            .or_insert_with(|| Vec::with_capacity(4096));
        bucket.push(record);
        if bucket.len() > MAX_HISTORY_SIZE {
            let remove = bucket.len() - PRUNE_TO_SIZE;
            bucket.drain(0..remove);
        }

        // Keep global aggregate key too
        let global = map.entry("__global__".to_string())
            .or_insert_with(|| Vec::with_capacity(4096));
        global.push(record);
        if global.len() > MAX_HISTORY_SIZE {
            let remove = global.len() - PRUNE_TO_SIZE;
            global.drain(0..remove);
        }
    }

    TOTAL_LOGS.fetch_add(1, Ordering::Relaxed);
}

/// Bulk-load history from Java (called at startup after H2 query).
pub fn bulk_load_history(records: &[HistoryRecord]) {
    if records.is_empty() { return; }
    if let Ok(mut hist) = GLOBAL_HISTORY.write() {
        for r in records {
            hist.push(*r);
        }
        if hist.len() > MAX_HISTORY_SIZE {
            let remove = hist.len() - PRUNE_TO_SIZE;
            hist.drain(0..remove);
        }
    }
    TOTAL_LOGS.fetch_add(records.len() as u64, Ordering::Relaxed);
}

/// Get a read lock on the global history.
pub fn get_history_read() -> std::sync::RwLockReadGuard<'static, Vec<HistoryRecord>> {
    GLOBAL_HISTORY.read().unwrap()
}

/// Get a read lock on the keyed history.
pub fn get_keyed_history_read() -> std::sync::RwLockReadGuard<'static, HashMap<String, Vec<HistoryRecord>>> {
    HOT_HISTORY_BY_KEY.read().unwrap()
}

/// Query N_eff from in-memory data for a specific market key.
pub fn query_neff_in_memory(current_ts: i64, tau: f64, market_key: &str) -> f64 {
    let lock = HOT_HISTORY_BY_KEY.read().unwrap();
    if let Some(history) = lock.get(market_key) {
        return calculate_volume(history, current_ts, tau);
    }
    0.0
}

/// Query global N_eff from in-memory data.
pub fn query_neff_global_in_memory(current_ts: i64, tau: f64) -> f64 {
    let lock = GLOBAL_HISTORY.read().unwrap();
    calculate_volume(&lock, current_ts, tau)
}

fn calculate_volume(history: &[HistoryRecord], current_time: i64, tau: f64) -> f64 {
    if history.is_empty() || tau <= 0.0 { return 0.0; }

    const MS_PER_DAY: f64 = 86_400_000.0;
    const MAX_FUTURE_TOLERANCE: i64 = 60_000;
    const MICROS_SCALE: f64 = 1_000_000.0;

    let valid_past = current_time - (tau * MS_PER_DAY * 10.0) as i64;
    let valid_future = current_time + MAX_FUTURE_TOLERANCE;

    let start_idx = history.partition_point(|r| r.timestamp < valid_past);
    let slice = &history[start_idx..];
    if slice.is_empty() { return 0.0; }

    let t_min = slice[0].timestamp;
    let lambda = 1.0 / (tau * MS_PER_DAY);
    let base = (-(current_time - t_min) as f64 * lambda).exp();

    let sum: f64 = slice.iter()
        .filter(|r| r.timestamp <= valid_future)
        .map(|r| {
            let dt = (r.timestamp - t_min) as f64;
            (r.amount_micros as f64) * (dt * lambda).exp()
        })
        .sum();

    let result = (sum / MICROS_SCALE) * base;
    if result.is_finite() { result } else { 0.0 }
}

// ==================== Health Stats ====================

pub fn get_total_logs() -> u64 { TOTAL_LOGS.load(Ordering::Relaxed) }
pub fn get_dropped_logs() -> u64 { DROPPED_LOGS.load(Ordering::Relaxed) }
