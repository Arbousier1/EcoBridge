package top.ellan.ecobridge.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 数据库基础设施管理器
 * * 职责：
 * 1. 维护 HikariCP 连接池
 * 2. 管理 SQL 任务的平台线程池 (物理隔离以规避虚拟线程 Pinning 风险)
 * 3. 执行建表 DDL
 * 4. 处理资源的生命周期管理
 */
public class DatabaseManager {

    private static HikariDataSource dataSource;
    private static ExecutorService dbExecutor;

    /**
     * 初始化数据库连接池与专用 IO 线程资源
     */
    public static synchronized void init() {
        // 如果已存在实例，先释放资源以支持热重载
        if (dataSource != null || dbExecutor != null) {
            close();
        }

        var plugin = EcoBridge.getInstance();
        var config = plugin.getConfig();

        // --- 核心修复：线程隔离策略 ---
        // 为了防止 JDBC 驱动在执行 SQL 时因为内部 synchronized 块锁定虚拟线程的载体线程 (Carrier Thread)，
        // 我们显式使用平台线程池 (FixedThreadPool) 来隔离数据库 IO。
        int poolSize = config.getInt("database.pool-size", 15);
        dbExecutor = Executors.newFixedThreadPool(
            poolSize,
            Thread.ofPlatform()
                  .name("ecobridge-db-worker-", 0)
                  .factory()
        );

        // --- 配置 HikariCP 连接池 ---
        HikariConfig hikari = new HikariConfig();

        String host = config.getString("database.host", "localhost");
        int port = config.getInt("database.port", 3306);
        String dbName = config.getString("database.database", "ecobridge");
        String user = config.getString("database.username", "root");
        String pass = config.getString("database.password", "");

        // 预设优化参数：开启批处理重写、统一时区并禁用不必要的 SSL 握手
        String jdbcUrl = String.format(
            "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true",
            host, port, dbName
        );

        hikari.setJdbcUrl(jdbcUrl);
        hikari.setUsername(user);
        hikari.setPassword(pass);

        // 池深度与 dbExecutor 保持 1:1 映射，确保吞吐量对齐
        hikari.setMaximumPoolSize(poolSize);
        hikari.setConnectionTimeout(5000); 
        hikari.setIdleTimeout(600000);     
        hikari.setMaxLifetime(1800000);    

        try {
            dataSource = new HikariDataSource(hikari);
            createTables();
            LogUtil.info("<green>SQL 数据源已就绪 (HikariCP + 隔离型平台线程池)。");
        } catch (Exception e) {
            LogUtil.error("数据库初始化失败！请检查配置及 MySQL 服务状态。", e);
        }
    }

    /**
     * 从连接池获取活跃连接
     *
     * @return Connection 实例
     * @throws SQLException 连接失败或池耗尽
     */
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource is not initialized");
        }
        return dataSource.getConnection();
    }

    /**
     * 获取专用于数据库 IO 的执行器
     * 注意：不要在此执行器中运行计算密集型任务
     */
    public static ExecutorService getExecutor() {
        return dbExecutor;
    }

    /**
     * 检查当前数据库连接状态
     */
    public static boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * 安全释放数据库资源 (关服或重载时调用)
     */
    public static synchronized void close() {
        LogUtil.info("正在安全释放 SQL 资源...");
        
        // 1. 关闭异步执行器
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            dbExecutor = null;
        }

        // 2. 关闭数据源
        if (dataSource != null) {
            if (!dataSource.isClosed()) {
                dataSource.close();
            }
            dataSource = null;
        }
    }

    /**
     * 执行初始化 DDL 语句
     */
    private static void createTables() {
        if (dataSource == null) return;

        // 交易历史表：使用 InnoDB 引擎，支持大规模流水索引
        String sqlSales = """
            CREATE TABLE IF NOT EXISTS ecobridge_sales (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                player_uuid CHAR(36) NOT NULL,
                product_id VARCHAR(64) NOT NULL,
                amount DOUBLE NOT NULL,
                timestamp BIGINT NOT NULL,
                INDEX idx_history (product_id, timestamp)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

        // 玩家数据表：核心资产表，使用乐观锁 version 字段保障并发安全
        String sqlPlayers = """
            CREATE TABLE IF NOT EXISTS ecobridge_players (
                uuid CHAR(36) PRIMARY KEY,
                balance DOUBLE NOT NULL DEFAULT 0.0,
                version BIGINT NOT NULL DEFAULT 0,
                last_updated BIGINT NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sqlSales);
            stmt.execute(sqlPlayers);
        } catch (SQLException e) {
            LogUtil.error("DDL 初始化失败，请检查数据库权限。", e);
        }
    }
}