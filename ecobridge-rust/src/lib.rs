// =============== ecobridge-rust/src/lib.rs ===============

use libc::{c_char, c_double, c_int, c_longlong, c_ulonglong};
use std::ffi::CStr;
use std::panic::{self, AssertUnwindSafe};

// -----------------------------------------------------------------------------
// 模块声明 (Internal Modules)
// -----------------------------------------------------------------------------
pub mod models;
pub mod economy;
pub mod security;
pub mod storage;

use crate::models::*;

// -----------------------------------------------------------------------------
// FFI 安全屏障宏 (Panic Guard)
// -----------------------------------------------------------------------------
/// 捕获 Rust 侧的 Panic，防止跨语言调用导致 JVM 崩溃 (SIGSEGV/SIGABRT)。
/// 如果发生 Panic，打印错误日志并返回指定的 fallback 值。
macro_rules! ffi_guard {
    ($fallback:expr, $body:block) => {
        match panic::catch_unwind(AssertUnwindSafe(|| $body)) {
            Ok(result) => result,
            Err(e) => {
                // 尝试提取 panic 信息以便调试
                let msg = if let Some(s) = e.downcast_ref::<&str>() {
                    *s
                } else if let Some(s) = e.downcast_ref::<String>() {
                    s.as_str()
                } else {
                    "Unknown panic origin"
                };
                eprintln!("[EcoBridge-Native] CRITICAL: Panic intercepted at FFI boundary: {}", msg);
                $fallback
            }
        }
    };
}

// -----------------------------------------------------------------------------
// 1. 系统基础与 ABI 版本握手
// -----------------------------------------------------------------------------

/// 返回 ABI 版本号 (Hex: 0x00080300 -> v0.8.3)
/// Java 侧需校验此值，防止 DLL/SO 版本与 Java 代码不匹配
#[no_mangle]
pub extern "C" fn ecobridge_abi_version() -> u32 {
    0x0008_0300
}

/// 返回人类可读的版本字符串
/// 注意：返回的是静态生命周期的字符串指针，Java 侧不应释放它
#[no_mangle]
pub extern "C" fn ecobridge_version() -> *const c_char {
    ffi_guard!(std::ptr::null(), {
        static VERSION: &[u8] = b"EcoBridge Native Core v0.8.3-Production\0";
        VERSION.as_ptr() as *const c_char
    })
}

// -----------------------------------------------------------------------------
// 2. 存储与监控 (Storage & Health)
// -----------------------------------------------------------------------------

/// 初始化 DuckDB 连接与异步写入线程
/// path_ptr: 数据库文件夹路径 (UTF-8)
/// return: 0 成功, 非0 失败
#[no_mangle]
pub extern "C" fn ecobridge_init_db(path_ptr: *const c_char) -> c_int {
    ffi_guard!(-99, {
        if path_ptr.is_null() {
            -1 // 替代 return -1
        } else {
            let path_result = unsafe { CStr::from_ptr(path_ptr).to_str() };
            match path_result {
                Ok(path_str) => storage::init_economy_db(path_str),
                Err(_) => -2, // UTF-8 解析失败，替代 return -2
            }
        }
    })
}

/// 异步日志写入 (极高频调用)
/// 该函数设计为 Non-blocking，仅将数据 push 到内存队列
#[no_mangle]
pub unsafe extern "C" fn ecobridge_log_to_duckdb(
    ts: c_longlong,
    uuid_ptr: *const c_char,
    delta: c_double,
    balance: c_double,
    meta_ptr: *const c_char,
) {
    ffi_guard!((), {
        if !uuid_ptr.is_null() && !meta_ptr.is_null() {
            // 字符串拷贝：FFI 边界必须拥有数据的所有权才能跨线程传递
            // to_string_lossy().into_owned() 会分配新的 Rust String
            let uuid = CStr::from_ptr(uuid_ptr).to_string_lossy().into_owned();
            let meta = CStr::from_ptr(meta_ptr).to_string_lossy().into_owned();
            
            // 投递到 storage 模块的 MPSC 队列
            storage::log_economy_event(ts, uuid, delta, balance, meta);
        }
        // 如果指针为空，直接隐式返回 ()，替代 explicit return
    })
}

/// 获取健康状态统计
/// out_total: 总接收日志数
/// out_dropped: 因队列满而丢弃的日志数 (背压指标)
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

/// 向量化有效交易量计算 (Neff)
/// 从 Read-Only DB 连接中查询
#[no_mangle]
pub unsafe extern "C" fn ecobridge_query_neff_vectorized(
    current_ts: c_longlong,
    tau: c_double,
) -> c_double {
    ffi_guard!(0.0, {
        // 调用 economy 模块的求和逻辑
        // 注意：此处会产生 DB IO (Read)，在 Java 端应在虚拟线程中调用
        economy::summation::query_neff_internal(current_ts, tau)
    })
}

/// 最终价格公式计算 (纯数学)
/// P = P0 * exp(-lambda * Neff) * epsilon
#[no_mangle]
pub extern "C" fn ecobridge_compute_price_final(
    base: c_double,
    n_eff: c_double,
    lambda: c_double,
    epsilon: c_double,
) -> c_double {
    ffi_guard!(base, { // Fallback to base price
        economy::pricing::compute_price_final_internal(base, n_eff, lambda, epsilon)
    })
}

/// 市场环境因子 (Epsilon) 计算 (纯数学)
/// [修复] 使用 match 替代 explict return，防止绕过闭包逻辑
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
            _ => 1.0 // 指针无效时返回默认值，不使用 return
        }
    })
}

/// PID 控制器步进计算 (状态机更新)
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

/// 重置 PID 状态
#[no_mangle]
pub unsafe extern "C" fn ecobridge_reset_pid_state(pid_ptr: *mut PidState) {
    ffi_guard!((), {
        if let Some(pid) = pid_ptr.as_mut() {
            *pid = PidState::default();
        }
    })
}

// -----------------------------------------------------------------------------
// 4. 安全审计 (Security Regulator)
// -----------------------------------------------------------------------------

/// 转账合规性检查
/// 返回 TransferResult 结构体 (值传递，避免内存分配)
#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_transfer_check(
    ctx_ptr: *const TransferContext,
    cfg_ptr: *const RegulatorConfig,
) -> TransferResult {
    // 默认 fallback：包含错误码 -999，且阻止交易 (Fail-Safe)
    ffi_guard!(TransferResult::error(-999), {
        match (ctx_ptr.as_ref(), cfg_ptr.as_ref()) {
            (Some(ctx), Some(cfg)) => {
                security::regulator::compute_transfer_check_internal(ctx, cfg)
            },
            (None, _) => TransferResult::error(671), // Error: Context Null
            (_, None) => TransferResult::error(672), // Error: Config Null
        }
    })
}