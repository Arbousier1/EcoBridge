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

// 写入端：只持有发送通道
static LOG_SENDER: OnceLock<Sender<LogEvent>> = OnceLock::new();

// 读取端：[修复] 使用连接池替代单一 Mutex 连接
// 通过 Crossbeam Channel 实现无锁并发获取连接
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

/// [新增] 基于 Channel 的轻量级连接池
struct ConnectionPool {
    // 闲置连接队列 (获取连接)
    available: Receiver<Connection>,
    // 归还连接通道 (用完放回)
    recycle: Sender<Connection>,
}

/// [新增] 连接守卫 (RAII)
/// 作用：利用 Drop 特性，确保用完或发生 Panic 时自动将连接归还给池子
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
            // 将连接归还回池子，如果池子已关闭则丢弃连接
            let _ = self.pool_sender.send(conn);
        }
    }
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

    // 1. 打开主连接 (Writer 独占)
    let write_conn = match Connection::open(&db_path) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[EcoBridge-Storage] DB Open Error: {}", e);
            return -4;
        }
    };

    // 2. 配置 WAL 模式 (支持并发的关键)
    let ddl_res = write_conn.execute_batch(
        "PRAGMA journal_mode=WAL;
         PRAGMA synchronous=NORMAL;
         -- 增加 Busy Timeout 防止高并发时的 SQLITE_BUSY 错误
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

    // 3. [修复] 初始化读取连接池 (Pool Size = 8)
    // 避免使用单一连接导致查询串行化
    let pool_size = 8;
    let (pool_tx, pool_rx) = bounded(pool_size);
    
    for i in 0..pool_size {
        match write_conn.try_clone() {
            Ok(c) => {
                // 将克隆的连接放入池中
                pool_tx.send(c).expect("Failed to fill connection pool");
            }
            Err(e) => {
                eprintln!("[EcoBridge-Storage] 连接池初始化失败 (第 {} 个): {}", i, e);
                return -6;
            }
        }
    }

    // 注册连接池
    // 注意：我们将 sender 存入结构体，用于后续归还连接
    READ_POOL.set(ConnectionPool {
        available: pool_rx,
        recycle: pool_tx,
    }).ok();

    // 4. 初始化写入通道
    let (tx, rx) = bounded(50_000);

    // 5. 启动写入线程
    thread::Builder::new()
        .name("ecobridge-db-writer".into())
        .spawn(move || writer_loop(write_conn, rx))
        .expect("Failed to spawn DB writer thread");

    // 6. 注册发送端
    match LOG_SENDER.set(tx) {
        Ok(_) => 0,
        Err(_) => -7,
    }
}

fn writer_loop(conn: Connection, rx: Receiver<LogEvent>) {
    let mut buffer = Vec::with_capacity(1024);
    loop {
        let first = match rx.recv() {
            Ok(msg) => msg,
            Err(_) => break,
        };
        buffer.push(first);

        while buffer.len() < 1024 {
            match rx.try_recv() {
                Ok(msg) => buffer.push(msg),
                Err(_) => break,
            }
        }

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

pub fn get_total_logs() -> u64 { TOTAL_LOGS.load(Ordering::Relaxed) }
pub fn get_dropped_logs() -> u64 { DROPPED_LOGS.load(Ordering::Relaxed) }

// -----------------------------------------------------------------------------
// 查询接口 (修复版)
// -----------------------------------------------------------------------------

/// 计算 Neff 的 SQL 聚合查询
/// [修复] 从连接池获取连接，支持并发，并在 Panic 时自动归还连接
pub fn query_neff_from_db(current_ts: i64, tau: f64) -> f64 {
    // 1. 获取连接池引用
    let pool = match READ_POOL.get() {
        Some(p) => p,
        None => return 0.0, // DB 未初始化
    };
    
    // 2. 从池中借出连接 (阻塞等待直到有可用连接)
    let raw_conn = match pool.available.recv() {
        Ok(c) => c,
        Err(_) => return 0.0, // 池子已关闭
    };

    // 3. 封装进 Guard (RAII)，确保函数结束(return/panic)时自动归还
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

    // 4. 执行查询 (通过 Deref 自动使用 conn)
    conn_guard.query_row(
        query,
        params![current_ts, tau, min_ts],
        |row| row.get(0)
    ).unwrap_or(0.0)
    
    // 函数结束，conn_guard 被 Drop，连接自动 send 回 pool.recycle
}