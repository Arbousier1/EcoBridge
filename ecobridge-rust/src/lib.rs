// ==================================================
// FILE: ecobridge-rust/src/lib.rs
// ==================================================

use libc::{c_char, c_double, c_int, c_longlong, c_ulonglong};
use std::ffi::CStr;
use std::panic::{self, AssertUnwindSafe};
// ✅ [Fix] 引入缺失的原子操作库
use std::sync::atomic::{AtomicU64, Ordering}; 

// -----------------------------------------------------------------------------
// 模块声明 (Internal Modules)
// -----------------------------------------------------------------------------
pub mod models;
pub mod economy {
    pub mod pricing;
    pub mod summation;
    pub mod environment;
    pub mod control;
    pub mod macro_eco; // [New] 宏观经济模块
}
pub mod security;
pub mod storage;

use crate::models::*;

// -----------------------------------------------------------------------------
// FFI 安全屏障宏 (Panic Guard)
// -----------------------------------------------------------------------------
static PANIC_COUNTER: AtomicU64 = AtomicU64::new(0);

macro_rules! ffi_guard {
    ($fallback:expr, $body:block) => {
        match panic::catch_unwind(AssertUnwindSafe(|| $body)) {
            Ok(result) => result,
            Err(e) => {
                let count = PANIC_COUNTER.fetch_add(1, Ordering::Relaxed);
                
                let msg = if let Some(s) = e.downcast_ref::<&str>() {
                    *s
                } else if let Some(s) = e.downcast_ref::<String>() {
                    s.as_str()
                } else {
                    "Unknown panic origin"
                };

                eprintln!("[EcoBridge-Native] CRITICAL PANIC detected: {}", msg);
                
                if count > 100 {
                    eprintln!("CRITICAL: Native panic count exceeded threshold (100). System instability imminent.");
                }
                
                $fallback
            }
        }
    };
}

// -----------------------------------------------------------------------------
// 1. 系统基础与 ABI 版本握手
// -----------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn ecobridge_abi_version() -> u32 {
    0x0008_0700
}

#[no_mangle]
pub extern "C" fn ecobridge_version() -> *const c_char {
    ffi_guard!(std::ptr::null(), {
        static VERSION: &[u8] = b"EcoBridge Native Core v0.8.5-Production (Behavioral Enhanced)\0";
        VERSION.as_ptr() as *const c_char
    })
}

// -----------------------------------------------------------------------------
// 2. 存储与监控 (Storage & Health)
// -----------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn ecobridge_init_db(path_ptr: *const c_char) -> c_int {
    ffi_guard!(-99, {
        if path_ptr.is_null() {
            -1
        } else {
            let path_result = unsafe { CStr::from_ptr(path_ptr).to_str() };
            match path_result {
                Ok(path_str) => {
                    let res = storage::init_economy_db(path_str);
                    if res == 0 {
                        economy::summation::hydrate_hot_store();
                    }
                    res
                },
                Err(_) => -2,
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_log_to_duckdb(
    ts: c_longlong,
    uuid_ptr: *const c_char,
    trade_amount: c_double,
    balance: c_double,
    meta_ptr: *const c_char,
) {
    ffi_guard!((), {
        if !uuid_ptr.is_null() && !meta_ptr.is_null() {
            let uuid = CStr::from_ptr(uuid_ptr).to_string_lossy().into_owned();
            let meta = CStr::from_ptr(meta_ptr).to_string_lossy().into_owned();
            
            economy::summation::append_trade_to_memory(ts, trade_amount.abs());
            storage::log_economy_event(ts, uuid, trade_amount, balance, meta);
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_get_health_stats(
    out_total: *mut c_ulonglong,
    out_dropped: *mut c_ulonglong,
) {
    ffi_guard!((), {
        if let Some(total) = out_total.as_mut() {
            *total = storage::get_total_logs();
        }
        if let Some(dropped) = out_dropped.as_mut() {
            *dropped = storage::get_dropped_logs();
        }
    })
}

// -----------------------------------------------------------------------------
// 3. 经济演算 (Economy Calculation)
// -----------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "C" fn ecobridge_query_neff_vectorized(
    current_ts: c_longlong,
    tau: c_double,
) -> c_double {
    ffi_guard!(0.0, {
        economy::summation::query_neff_internal(current_ts, tau)
    })
}

#[no_mangle]
pub extern "C" fn ecobridge_compute_price_final(
    base: c_double,
    n_eff: c_double,
    lambda: c_double,
    epsilon: c_double,
) -> c_double {
    ffi_guard!(base, { 
        economy::pricing::compute_price_final_internal(base, n_eff, lambda, epsilon)
    })
}

#[no_mangle]
pub extern "C" fn ecobridge_compute_price_humane(
    base: c_double,
    n_eff: c_double,
    trade_amount: c_double,
    lambda: c_double,
    epsilon: c_double,
) -> c_double {
    ffi_guard!(base, {
        economy::pricing::compute_price_humane_internal(base, n_eff, trade_amount, lambda, epsilon)
    })
}

/// [New] 带地板价保护的定价入口
#[no_mangle]
pub extern "C" fn ecobridge_compute_price_bounded(
    base: c_double, n_eff: c_double, amt: c_double, lambda: c_double, eps: c_double, 
    hist_avg: c_double
) -> c_double {
    ffi_guard!(base, {
        economy::pricing::compute_price_with_floor(base, n_eff, amt, lambda, eps, hist_avg)
    })
}

/// [New] 阶梯定价入口
#[no_mangle]
pub extern "C" fn ecobridge_compute_tier_price(base: c_double, qty: c_double, is_sell: bool) -> c_double {
    ffi_guard!(base, {
        economy::pricing::compute_tier_price_internal(base, qty, is_sell)
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_calculate_epsilon(
    ctx_ptr: *const TradeContext,
    cfg_ptr: *const MarketConfig,
) -> c_double {
    ffi_guard!(1.0, {
        match (ctx_ptr.as_ref(), cfg_ptr.as_ref()) {
            (Some(ctx), Some(cfg)) => {
                economy::environment::calculate_epsilon_internal(ctx, cfg)
            },
            _ => 1.0
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_pid_adjustment(
    pid_ptr: *mut PidState,
    target: c_double,
    current: c_double,
    dt: c_double,
    inflation: c_double,
) -> c_double {
    ffi_guard!(0.0, {
        match pid_ptr.as_mut() {
            Some(pid) => {
                economy::control::compute_pid_adjustment_internal(pid, target, current, dt, inflation)
            }
            None => 0.0,
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_reset_pid_state(pid_ptr: *mut PidState) {
    ffi_guard!((), {
        if let Some(pid) = pid_ptr.as_mut() {
            *pid = PidState::default();
        }
    })
}

// -----------------------------------------------------------------------------
// 4. 宏观经济导出 (Macro Economy Exports)
// -----------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn ecobridge_calc_inflation(current_heat: c_double, m1: c_double) -> c_double {
    ffi_guard!(0.0, {
        economy::macro_eco::calculate_inflation_rate(current_heat, m1)
    })
}

#[no_mangle]
pub extern "C" fn ecobridge_calc_stability(last_ts: c_longlong, curr_ts: c_longlong) -> c_double {
    ffi_guard!(1.0, {
        // 默认恢复窗口 15分钟 (900000ms)
        economy::macro_eco::calculate_stability(last_ts, curr_ts, 900000.0)
    })
}

#[no_mangle]
pub extern "C" fn ecobridge_calc_decay(heat: c_double, rate: c_double) -> c_double {
    ffi_guard!(0.0, {
        economy::macro_eco::calculate_decay(heat, rate, 48.0)
    })
}

// -----------------------------------------------------------------------------
// 5. 安全审计 (Security Regulator)
// -----------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_transfer_check(
    ctx_ptr: *const TransferContext,
    cfg_ptr: *const RegulatorConfig,
) -> TransferResult {
    ffi_guard!(TransferResult::error(-999), {
        match (ctx_ptr.as_ref(), cfg_ptr.as_ref()) {
            (Some(ctx), Some(cfg)) => {
                security::regulator::compute_transfer_check_internal(ctx, cfg)
            },
            (None, _) => TransferResult::error(671),
            (_, None) => TransferResult::error(672),
        }
    })
}

#[no_mangle]
pub extern "C" fn ecobridge_shutdown_db() -> c_int {
    storage::shutdown_db_internal()
}