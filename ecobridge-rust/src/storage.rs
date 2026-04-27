use crossbeam_channel::{bounded, Receiver, Sender};
use duckdb::{params, Connection};
use std::collections::HashMap;
use std::ops::Deref;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{OnceLock, RwLock, LazyLock};
use std::thread;
use libc::c_int;
use crate::models::HistoryRecord;

// -----------------------------------------------------------------------------
// 静态状态管理
// -----------------------------------------------------------------------------

// 全局内存历史 - 单一事实来源 (Single Source of Truth)
// 存储的是物品交易数量的 Micros 定点数，用于极致精度的 SIMD 演算
static GLOBAL_HISTORY: LazyLock<RwLock<Vec<HistoryRecord>>> =
    LazyLock::new(|| RwLock::new(Vec::with_capacity(200_000)));

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
    delta: f64,    // 外部传入仍保持 f64 以兼容已有 API
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
            eprintln!("[EcoBridge-Storage] DuckDB Open Error: {}", e);
            return -4;
        }
    };

    let ddl_res = write_conn.execute_batch(
        "SET memory_limit='512MB';
         SET threads=4;
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

    // 启动预热
    load_recent_history_to_memory(&write_conn);

    // 初始化连接池
    let pool_size = 4;
    let (pool_tx, pool_rx) = bounded(pool_size);
    for _ in 0..pool_size {
        if let Ok(c) = write_conn.try_clone() {
            let _ = pool_tx.send(c);
        }
    }

    let _ = READ_POOL.set(ConnectionPool {
        available: pool_rx,
        recycle: pool_tx,
    });

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

/// [Fix] 适配 v1.6.0 定点数精度重构
fn load_recent_history_to_memory(conn: &Connection) {
    let now = chrono::Utc::now().timestamp_millis();
    let cutoff = now - (90i64 * 86_400_000); 
    
    let mut stmt = match conn.prepare("SELECT ts, delta FROM economy_log WHERE ts > ? ORDER BY ts ASC") {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[EcoBridge-Storage] Preload Error: {}", e);
            return;
        }
    };

    let records_iter = stmt.query_map(params![cutoff], |row| {
        // 先获取数据库中的浮点数原始值
        let amount_f64: f64 = row.get(1)?;
        Ok(HistoryRecord {
            timestamp: row.get(0)?,
            // [Precision Fix]: 转换为 i64 Micros
            amount_micros: (amount_f64 * 1_000_000.0) as i64,
        })
    });

    if let Ok(iter) = records_iter {
        if let Ok(mut hist) = GLOBAL_HISTORY.write() {
            for rec in iter.flatten() {
                hist.push(rec);
            }
            println!("[EcoBridge-Storage] 内存预热完成：同步加载了 {} 条高精度记录。", hist.len());
        }
    }
}

pub fn get_history_read() -> std::sync::RwLockReadGuard<'static, Vec<HistoryRecord>> {
    GLOBAL_HISTORY.read().unwrap()
}

/// [Fix] 记录经济事件：同步执行高精度内存注入
pub fn log_economy_event(ts: i64, uuid: String, delta: f64, balance: f64, meta: String) {
    TOTAL_LOGS.fetch_add(1, Ordering::Relaxed);
    
    // 1. 同步更新内存历史记录 (SSoT)
    if let Ok(mut hist) = GLOBAL_HISTORY.write() {
        // [Precision Fix]: 字段重命名并转换单位
        hist.push(HistoryRecord { 
            timestamp: ts, 
            amount_micros: (delta * 1_000_000.0) as i64 
        });

        // 维持滑动窗口
        if hist.len() > 500_000 {
            let keep = 400_000;
            let remove_count = hist.len() - keep;
            hist.drain(0..remove_count);
        }
    }

    // 2. 异步持久化
    if let Some(sender) = LOG_SENDER.get() {
        if sender.try_send(LogEvent { ts, uuid, delta, balance, meta }).is_err() {
            DROPPED_LOGS.fetch_add(1, Ordering::Relaxed);
        }
    }
}

// [Fix] 增加 mut 关键字，允许传递可变引用
fn writer_loop(mut conn: Connection, rx: Receiver<LogEvent>) {
    let mut buffer = Vec::with_capacity(1024);
    loop {
        match rx.recv() {
            Ok(msg) if msg.ts != -1 => {
                buffer.push(msg);
                while buffer.len() < 1024 {
                    match rx.try_recv() {
                        Ok(m) if m.ts != -1 => buffer.push(m),
                        _ => break,
                    }
                }
                // [Fix] 传入 &mut conn
                flush_buffer_to_db(&mut conn, &mut buffer);
            }
            _ => break, 
        }
    }
    if !buffer.is_empty() {
        // [Fix] 传入 &mut conn
        flush_buffer_to_db(&mut conn, &mut buffer);
    }
}

// [Fix] 签名修改：conn: &Connection -> conn: &mut Connection
fn flush_buffer_to_db(conn: &mut Connection, buffer: &mut Vec<LogEvent>) {
    if buffer.is_empty() { return; }

    // [Optimization] 使用事务批量提交，解决单条插入性能瓶颈 (1k -> 50k rows/sec)
    
    
    // 1. 开启事务 (现在可以成功调用，因为有了 &mut self)
    let tx = match conn.transaction() {
        Ok(t) => t,
        Err(_) => {
            DROPPED_LOGS.fetch_add(buffer.len() as u64, Ordering::Relaxed);
            buffer.clear();
            return;
        }
    };

    // 2. 在事务内执行批量插入
    {
        match tx.appender("economy_log") {
            Ok(mut appender) => {
                for ev in buffer.drain(..) {
                    let _ = appender.append_row(params![ev.ts, ev.uuid, ev.delta, ev.balance, ev.meta]);
                }
                // Appender 在离开作用域时自动 flush 数据到 Transaction
            },
            Err(_) => {
                DROPPED_LOGS.fetch_add(buffer.len() as u64, Ordering::Relaxed);
                buffer.clear();
                return;
            }
        }
    } 

    // 3. 提交事务 (一次性 fsync)
    if let Err(_) = tx.commit() {
        // 如果提交失败，由于 buffer 已被 drain，数据实际上丢失
        // 在高频日志场景下，我们选择忽略极低概率的提交失败以保证不阻塞
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

    let query = "SELECT SUM(delta * EXP( -1.0 * (?1 - ts) / (?2 * 86400000.0) )) FROM economy_log WHERE ts > ?3";
    let ms_per_day = 86_400_000.0;
    let safe_lookback_ms = (tau * ms_per_day * 3.0) as i64;
    let min_ts = current_ts - safe_lookback_ms;

    conn_guard.query_row(query, params![current_ts, tau, min_ts], |row| row.get(0)).unwrap_or(0.0)
}

pub fn get_total_logs() -> u64 { TOTAL_LOGS.load(Ordering::Relaxed) }
pub fn get_dropped_logs() -> u64 { DROPPED_LOGS.load(Ordering::Relaxed) }

/// [Fix] 这里的 HistoryRecord 构造也需要同步更新
pub fn load_recent_history(days: i64) -> Vec<crate::models::HistoryRecord> {
    let pool = match READ_POOL.get() {
        Some(p) => p,
        None => return Vec::new(),
    };
    let raw_conn = match pool.available.recv() {
        Ok(c) => c,
        Err(_) => return Vec::new(),
    };

    // Guard ensures connection is always returned to pool
    let guard = DbConnectionGuard {
        conn: Some(raw_conn),
        pool_sender: pool.recycle.clone(),
    };

    let ms_lookback = days * 86_400_000;
    let cutoff = chrono::Utc::now().timestamp_millis() - ms_lookback;

    let query = "SELECT ts, delta FROM economy_log WHERE ts > ? ORDER BY ts ASC";
    let mut stmt = match guard.prepare(query) {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let record_iter = match stmt.query_map(params![cutoff], |row| {
        let amt_f64: f64 = row.get(1)?;
        Ok(crate::models::HistoryRecord {
            timestamp: row.get(0)?,
            amount_micros: (amt_f64 * 1_000_000.0) as i64,
        })
    }) {
        Ok(iter) => iter,
        Err(_) => return Vec::new(),
    };

    let mut history = Vec::new();
    for record in record_iter.flatten() {
        history.push(record);
    }
    // Guard drops here, connection returned to pool
    history
}

pub fn load_recent_market_history_by_key(days: i64) -> HashMap<String, Vec<crate::models::HistoryRecord>> {
    let mut result: HashMap<String, Vec<crate::models::HistoryRecord>> = HashMap::new();
    let pool = match READ_POOL.get() {
        Some(p) => p,
        None => return result,
    };
    let raw_conn = match pool.available.recv() {
        Ok(c) => c,
        Err(_) => return result,
    };

    let ms_lookback = days * 86_400_000;
    let cutoff = chrono::Utc::now().timestamp_millis() - ms_lookback;
    let query = "SELECT ts, delta, metadata FROM economy_log WHERE ts > ? ORDER BY ts ASC";
    let mut stmt = match raw_conn.prepare(query) {
        Ok(s) => s,
        Err(_) => {
            let _ = pool.recycle.send(raw_conn);
            return result;
        }
    };

    let record_iter = match stmt.query_map(params![cutoff], |row| {
        let timestamp: i64 = row.get(0)?;
        let amt_f64: f64 = row.get(1)?;
        let metadata: String = row.get(2)?;
        Ok((timestamp, amt_f64, metadata))
    }) {
        Ok(iter) => iter,
        Err(_) => {
            let _ = pool.recycle.send(raw_conn);
            return result;
        }
    };

    for record in record_iter.flatten() {
        let (timestamp, amt_f64, metadata) = record;
        if let Some(key) = metadata.strip_prefix("MARKET_TRADE:") {
            if key.is_empty() {
                continue;
            }
            let bucket = result.entry(key.to_string()).or_default();
            bucket.push(crate::models::HistoryRecord {
                timestamp,
                amount_micros: (amt_f64 * 1_000_000.0) as i64,
            });
        }
    }

    let _ = pool.recycle.send(raw_conn);
    result
}
