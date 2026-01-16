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
 * <p>
 * 职责：
 * 1. 维护 HikariCP 连接池
 * 2. 管理 SQL 任务的虚拟线程池 (Project Loom)
 * 3. 执行建表 DDL
 * 4. 处理数据库资源的初始化与释放
 */
public class DatabaseManager {

    private static HikariDataSource dataSource;
    private static ExecutorService dbExecutor;

    /**
     * 初始化数据库连接池与线程资源
     */
    public static synchronized void init() {
        // 如果已存在实例，先关闭以避免资源泄漏（支持重载）
        if (dataSource != null || dbExecutor != null) {
            close();
        }

        var plugin = EcoBridge.getInstance();
        var config = plugin.getConfig();

        // 1. 初始化虚拟线程执行器 (适配 Java 21+ / Java 25)
        dbExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 2. 配置 HikariCP
        HikariConfig hikari = new HikariConfig();

        String host = config.getString("database.host", "localhost");
        int port = config.getInt("database.port", 3306);
        String dbName = config.getString("database.database", "ecobridge");
        String user = config.getString("database.username", "root");
        String pass = config.getString("database.password", "");

        // 优化 URL 参数：禁用 SSL，统一 UTC 时区，开启批处理重写，允许公钥检索
        String jdbcUrl = String.format(
            "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true",
            host, port, dbName
        );

        hikari.setJdbcUrl(jdbcUrl);
        hikari.setUsername(user);
        hikari.setPassword(pass);

        // 连接池调优参数
        hikari.setMaximumPoolSize(config.getInt("database.pool-size", 15));
        hikari.setConnectionTimeout(5000); // 5秒连接超时
        hikari.setIdleTimeout(600000);     // 10分钟空闲断开
        hikari.setMaxLifetime(1800000);    // 30分钟最大生命周期

        try {
            dataSource = new HikariDataSource(hikari);
            createTables();
            LogUtil.info("<green>SQL 数据源已就绪 (HikariCP + VirtualThreads)。");
        } catch (Exception e) {
            LogUtil.error("数据库初始化失败！请检查配置。", e);
        }
    }

    /**
     * 获取数据库连接
     *
     * @return 活跃的 SQL 连接
     * @throws SQLException 如果连接池未初始化或已耗尽
     */
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource is not initialized");
        }
        return dataSource.getConnection();
    }

    /**
     * 获取用于异步 IO 的虚拟线程执行器
     */
    public static ExecutorService getExecutor() {
        return dbExecutor;
    }

    /**
     * 检查连接池是否可用
     */
    public static boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * 释放所有数据库资源 (关服/重载时调用)
     */
    public static synchronized void close() {
        LogUtil.info("正在释放 SQL 资源...");
        
        // 1. 关闭线程池
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                // 等待 5 秒让任务完成
                if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            dbExecutor = null;
        }

        // 2. 关闭连接池
        if (dataSource != null) {
            if (!dataSource.isClosed()) {
                dataSource.close();
            }
            dataSource = null;
        }
    }

    /**
     * 自动创建所需的表结构
     */
    private static void createTables() {
        if (dataSource == null) return;

        // 交易流水表
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

        // 玩家资产表 (含 version 乐观锁字段)
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
            LogUtil.error("DDL 执行失败，请检查数据库权限或表结构。", e);
        }
    }
}