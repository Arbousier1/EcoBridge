// =============== ecobridge-rust/src/storage/mod.rs ===============

use crossbeam_channel::{bounded, Receiver, Sender};
use duckdb::{params, Connection};
use std::ops::Deref;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::OnceLock;
use std::thread;
use libc::c_int;

// -----------------------------------------------------------------------------
// 静态状态管理
// -----------------------------------------------------------------------------

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

/// [FFI] 触发 Native 层安全关机序列
pub fn shutdown_db_internal() -> c_int {
    if let Some(sender) = LOG_SENDER.get() {
        // 发送“毒丸”信号：ts = -1 表示停止信号
        // 这将打破 writer_loop 的无限循环
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
// 业务逻辑实现
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

    let ddl_res = write_conn.execute_batch(
        "PRAGMA journal_mode=WAL;
         PRAGMA synchronous=NORMAL;
         PRAGMA busy_timeout=5000; 
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

    let pool_size = 8;
    let (pool_tx, pool_rx) = bounded(pool_size);
    for _i in 0..pool_size {
        match write_conn.try_clone() {
            Ok(c) => {
                let _ = pool_tx.send(c);
            }
            Err(e) => {
                eprintln!("[EcoBridge-Storage] 连接池初始化失败: {}", e);
                return -6;
            }
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

/// 改进的后台写入循环：支持优雅关机
fn writer_loop(conn: Connection, rx: Receiver<LogEvent>) {
    let mut buffer = Vec::with_capacity(1024);
    
    // 主事件循环
    loop {
        let first = match rx.recv() {
            Ok(msg) => msg,
            Err(_) => break, // 通道关闭
        };

        // 核心变更：检测到关机信号则跳出循环
        if first.ts == -1 { 
            eprintln!("[EcoBridge-Storage] 接收到关机信号，正在冲刷缓存并退出...");
            break; 
        }

        buffer.push(first);

        // 批量拉取后续数据 (Backpressure Aware) [cite: 500]
        while buffer.len() < 1024 {
            match rx.try_recv() {
                Ok(msg) => {
                    if msg.ts == -1 {
                        // 如果在批量获取中也拉到了关机信号，立即终止
                        break;
                    }
                    buffer.push(msg);
                },
                Err(_) => break,
            }
        }

        // 写入当前批次
        flush_buffer_to_db(&conn, &mut buffer);
    }

    // 关机前的最终清理：冲刷 buffer 内剩余的所有数据
    if !buffer.is_empty() {
        flush_buffer_to_db(&conn, &mut buffer);
    }
    eprintln!("[EcoBridge-Storage] 后台写入线程已安全终止。");
}

/// 辅助函数：执行 DuckDB Appender 写入 
fn flush_buffer_to_db(conn: &Connection, buffer: &mut Vec<LogEvent>) {
    if buffer.is_empty() { return; }
    
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

pub fn log_economy_event(ts: i64, uuid: String, delta: f64, balance: f64, meta: String) {
    TOTAL_LOGS.fetch_add(1, Ordering::Relaxed);
    if let Some(sender) = LOG_SENDER.get() {
        if let Err(_) = sender.try_send(LogEvent { ts, uuid, delta, balance, meta }) {
            DROPPED_LOGS.fetch_add(1, Ordering::Relaxed);
        }
    } else {
        DROPPED_LOGS.fetch_add(1, Ordering::Relaxed);
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

    let query = "
        SELECT SUM(ABS(delta) * EXP( -1.0 * (?1 - ts) / (?2 * 86400000.0) ))
        FROM economy_log
        WHERE ts > ?3
    ";

    let ms_per_day = 86_400_000.0;
    let safe_lookback_ms = (tau * ms_per_day * 3.0) as i64;
    let min_ts = current_ts - safe_lookback_ms;

    conn_guard.query_row(
        query,
        params![current_ts, tau, min_ts],
        |row| row.get(0)
    ).unwrap_or(0.0)
}

pub fn get_total_logs() -> u64 { 
    TOTAL_LOGS.load(Ordering::Relaxed) 
}

/// 获取因背压（内存队列满）而丢弃的日志数 [cite: 504]
pub fn get_dropped_logs() -> u64 { 
    DROPPED_LOGS.load(Ordering::Relaxed) 
}