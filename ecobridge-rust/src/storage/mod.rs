use crossbeam_channel::{bounded, Receiver, Sender};
use duckdb::{params, Connection};
use std::ops::Deref;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{OnceLock, RwLock};
use std::thread;
use libc::c_int;
use lazy_static::lazy_static;
use crate::models::HistoryRecord;

// -----------------------------------------------------------------------------
// 静态状态管理
// -----------------------------------------------------------------------------

lazy_static! {
    // [核心架构] 全局内存历史 - 单一事实来源 (Single Source of Truth)
    // 供 summation.rs 的 SIMD 模块读取，完全绕过 DuckDB I/O
    static ref GLOBAL_HISTORY: RwLock<Vec<HistoryRecord>> = RwLock::new(Vec::with_capacity(200_000));
}

static LOG_SENDER: OnceLock<Sender<LogEvent>> = OnceLock::new();
static READ_POOL: OnceLock<ConnectionPool> = OnceLock::new();
static TOTAL_LOGS: AtomicU64 = AtomicU64::new(0);
static DROPPED_LOGS: AtomicU64 = AtomicU64::new(0);

// -----------------------------------------------------------------------------
// 数据结构定义
// -----------------------------------------------------------------------------

struct LogEvent {
    ts: i64,
    uuid: String,
    delta: f64,
    balance: f64,
    meta: String,
}

struct ConnectionPool {
    available: Receiver<Connection>,
    recycle: Sender<Connection>,
}

struct DbConnectionGuard {
    conn: Option<Connection>,
    pool_sender: Sender<Connection>,
}

impl Deref for DbConnectionGuard {
    type Target = Connection;
    fn deref(&self) -> &Self::Target {
        self.conn.as_ref().unwrap()
    }
}

impl Drop for DbConnectionGuard {
    fn drop(&mut self) {
        if let Some(conn) = self.conn.take() {
            let _ = self.pool_sender.send(conn);
        }
    }
}

// -----------------------------------------------------------------------------
// FFI 关机指令实现
// -----------------------------------------------------------------------------

pub fn shutdown_db_internal() -> c_int {
    if let Some(sender) = LOG_SENDER.get() {
        let res = sender.send(LogEvent {
            ts: -1, 
            uuid: String::new(),
            delta: 0.0,
            balance: 0.0,
            meta: String::from("SHUTDOWN_SIGNAL"),
        });
        
        if res.is_ok() {
            return 0;
        }
    }
    -1
}

// -----------------------------------------------------------------------------
// 核心初始化逻辑
// -----------------------------------------------------------------------------

pub fn init_economy_db(path_str: &str) -> c_int {
    if LOG_SENDER.get().is_some() {
        return 0;
    }

    let mut db_path = PathBuf::from(path_str);
    db_path.push("ecobridge_vault.db");

    let write_conn = match Connection::open(&db_path) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[EcoBridge-Storage] DB Open Error: {}", e);
            return -4;
        }
    };

    // ✅ 修复点：移除 SQLite 特有的 PRAGMA 指令，改用 DuckDB 兼容配置
    let ddl_res = write_conn.execute_batch(
        "SET memory_limit='512MB';
         CREATE TABLE IF NOT EXISTS economy_log (
             ts BIGINT,
             player_uuid VARCHAR,
             delta DOUBLE,
             balance DOUBLE,
             metadata VARCHAR
         );
         CREATE INDEX IF NOT EXISTS idx_ts ON economy_log (ts);"
    );

    if let Err(e) = ddl_res {
        eprintln!("[EcoBridge-Storage] DDL Error: {}", e);
        return -5;
    }

    // 启动预热：将最近 90 天的数据加载到 GLOBAL_HISTORY
    load_recent_history_to_memory(&write_conn);

    // 初始化连接池
    let pool_size = 4;
    let (pool_tx, pool_rx) = bounded(pool_size);
    for _i in 0..pool_size {
        if let Ok(c) = write_conn.try_clone() {
            let _ = pool_tx.send(c);
        }
    }

    READ_POOL.set(ConnectionPool {
        available: pool_rx,
        recycle: pool_tx,
    }).ok();

    let (tx, rx) = bounded(50_000);

    thread::Builder::new()
        .name("ecobridge-db-writer".into())
        .spawn(move || writer_loop(write_conn, rx))
        .expect("Failed to spawn DB writer thread");

    match LOG_SENDER.set(tx) {
        Ok(_) => 0,
        Err(_) => -7,
    }
}

/// 从 DuckDB 加载历史数据到内存以供 SIMD 使用
fn load_recent_history_to_memory(conn: &Connection) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    
    let cutoff = now - (90i64 * 86_400_000);
    println!("[EcoBridge-Storage] 正在预热内存数据 (Cutoff: {})...", cutoff);

    let mut stmt = match conn.prepare("SELECT ts, delta FROM economy_log WHERE ts > ? ORDER BY ts ASC") {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[EcoBridge-Storage] Preload Prepare Error: {}", e);
            return;
        }
    };

    let records_iter = stmt.query_map(params![cutoff], |row| {
        Ok(HistoryRecord {
            timestamp: row.get(0)?,
            amount: row.get(1)?,
        })
    });

    match records_iter {
        Ok(iter) => {
            let mut hist = GLOBAL_HISTORY.write().unwrap();
            let start_len = hist.len();
            for rec in iter {
                if let Ok(r) = rec {
                    hist.push(r);
                }
            }
            println!("[EcoBridge-Storage] 预热完成。加载了 {} 条记录到内存。", hist.len() - start_len);
        }
        Err(e) => eprintln!("[EcoBridge-Storage] Preload Query Error: {}", e),
    }
}

pub fn get_history_read() -> std::sync::RwLockReadGuard<'static, Vec<HistoryRecord>> {
    GLOBAL_HISTORY.read().unwrap()
}

/// 核心双写：同时更新内存（瞬时计算）和异步持久化队列
pub fn log_economy_event(ts: i64, uuid: String, delta: f64, balance: f64, meta: String) {
    TOTAL_LOGS.fetch_add(1, Ordering::Relaxed);
    
    // 1. 同步写内存
    {
        if let Ok(mut hist) = GLOBAL_HISTORY.write() {
             hist.push(HistoryRecord { timestamp: ts, amount: delta });
             
             // 长度保护，防止 OOM
             if hist.len() > 500_000 {
                 let keep = 400_000;
                 let remove_count = hist.len() - keep;
                 hist.drain(0..remove_count);
             }
        }
    }

    // 2. 异步入库
    if let Some(sender) = LOG_SENDER.get() {
        if let Err(_) = sender.try_send(LogEvent { ts, uuid, delta, balance, meta }) {
            DROPPED_LOGS.fetch_add(1, Ordering::Relaxed);
        }
    } else {
        DROPPED_LOGS.fetch_add(1, Ordering::Relaxed);
    }
}

fn writer_loop(conn: Connection, rx: Receiver<LogEvent>) {
    let mut buffer = Vec::with_capacity(1024);
    
    loop {
        let first = match rx.recv() {
            Ok(msg) => msg,
            Err(_) => break, 
        };

        if first.ts == -1 { 
            eprintln!("[EcoBridge-Storage] 接收到关机信号，正在冲刷缓存并退出...");
            break; 
        }

        buffer.push(first);

        while buffer.len() < 1024 {
            match rx.try_recv() {
                Ok(msg) => {
                    if msg.ts == -1 { break; }
                    buffer.push(msg);
                },
                Err(_) => break,
            }
        }

        flush_buffer_to_db(&conn, &mut buffer);
    }

    if !buffer.is_empty() {
        flush_buffer_to_db(&conn, &mut buffer);
    }
    eprintln!("[EcoBridge-Storage] 后台写入线程已安全终止。");
}

fn flush_buffer_to_db(conn: &Connection, buffer: &mut Vec<LogEvent>) {
    if buffer.is_empty() { return; }
    // 使用 DuckDB Appender API 进行高性能批量插入
    match conn.appender("economy_log") {
        Ok(mut appender) => {
            for ev in buffer.drain(..) {
                if appender.append_row(params![ev.ts, ev.uuid, ev.delta, ev.balance, ev.meta]).is_err() {
                    DROPPED_LOGS.fetch_add(1, Ordering::Relaxed);
                }
            }
        }
        Err(e) => {
            eprintln!("[EcoBridge-Storage] Appender Error: {}", e);
            DROPPED_LOGS.fetch_add(buffer.len() as u64, Ordering::Relaxed);
            buffer.clear();
        }
    }
}

pub fn query_neff_from_db(current_ts: i64, tau: f64) -> f64 {
    let pool = match READ_POOL.get() {
        Some(p) => p,
        None => return 0.0,
    };
    let raw_conn = match pool.available.recv() {
        Ok(c) => c,
        Err(_) => return 0.0,
    };
    let conn_guard = DbConnectionGuard {
        conn: Some(raw_conn),
        pool_sender: pool.recycle.clone(),
    };

    let query = "SELECT SUM(ABS(delta) * EXP( -1.0 * (?1 - ts) / (?2 * 86400000.0) )) FROM economy_log WHERE ts > ?3";
    let ms_per_day = 86_400_000.0;
    let safe_lookback_ms = (tau * ms_per_day * 3.0) as i64;
    let min_ts = current_ts - safe_lookback_ms;

    conn_guard.query_row(query, params![current_ts, tau, min_ts], |row| row.get(0)).unwrap_or(0.0)
}

pub fn get_total_logs() -> u64 { TOTAL_LOGS.load(Ordering::Relaxed) }
pub fn get_dropped_logs() -> u64 { DROPPED_LOGS.load(Ordering::Relaxed) }

pub fn load_recent_history(days: i64) -> Vec<crate::models::HistoryRecord> {
    let pool = match READ_POOL.get() {
        Some(p) => p,
        None => return Vec::new(),
    };
    let raw_conn = match pool.available.recv() {
        Ok(c) => c,
        Err(_) => return Vec::new(),
    };
    
    let ms_lookback = days * 86_400_000;
    let cutoff = chrono::Utc::now().timestamp_millis() - ms_lookback;

    let query = "
        SELECT ts, delta 
        FROM economy_log 
        WHERE ts > ? 
        ORDER BY ts ASC
    ";

    let mut stmt = raw_conn.prepare(query).unwrap();
    let record_iter = stmt.query_map(params![cutoff], |row| {
        Ok(crate::models::HistoryRecord {
            timestamp: row.get(0)?,
            amount: row.get::<_, f64>(1)?.abs(),
        })
    }).unwrap();

    let mut history = Vec::new();
    for record in record_iter {
        if let Ok(r) = record {
            history.push(r);
        }
    }
    
    let _ = pool.recycle.send(raw_conn);
    history
}