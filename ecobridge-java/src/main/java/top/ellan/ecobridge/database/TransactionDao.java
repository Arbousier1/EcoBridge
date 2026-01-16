package top.ellan.ecobridge.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.cache.HotDataCache;
import top.ellan.ecobridge.cache.HotDataCache.PlayerData;
import top.ellan.ecobridge.model.SaleRecord;
import top.ellan.ecobridge.util.LogUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 交易数据访问对象 (TransactionDao v0.8.9-Optimistic-Lock)
 * 职责：SQL 连接池维护、高精度交易历史存取、玩家资产持久化。
 */
public class TransactionDao {

    private static HikariDataSource dataSource;
    private static ExecutorService dbExecutor;

    /**
     * 初始化数据库引擎
     */
    public static synchronized void init() {
        if (dataSource != null || dbExecutor != null) {
            close();
        }

        var plugin = EcoBridge.getInstance();
        var config = plugin.getConfig();

        // 1. 初始化虚拟线程执行器 (Java 25 Loom)
        dbExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 2. 配置 HikariCP
        HikariConfig hikari = new HikariConfig();

        String host = config.getString("database.host", "localhost");
        int port = config.getInt("database.port", 3306);
        String dbName = config.getString("database.database", "ecobridge");
        String user = config.getString("database.username", "root");
        String pass = config.getString("database.password", "");

        String jdbcUrl = String.format(
        "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true",
        host, port, dbName
    );

        hikari.setJdbcUrl(jdbcUrl);
        hikari.setUsername(user);
        hikari.setPassword(pass);

        hikari.setMaximumPoolSize(config.getInt("database.pool-size", 15));
        hikari.setConnectionTimeout(5000);
        hikari.setIdleTimeout(600000);
        hikari.setMaxLifetime(1800000);

        try {
            dataSource = new HikariDataSource(hikari);
            createTables();
            LogUtil.info("<green>SQL 数据源已就绪 (乐观锁模式)。");
        } catch (Exception e) {
            LogUtil.error("数据库初始化失败！", e);
        }
    }

    private static void createTables() {
        if (dataSource == null) return;
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

        // [修改]: 增加 version 字段，用于乐观锁校验
        String sqlPlayers = """
        CREATE TABLE IF NOT EXISTS ecobridge_players (
        uuid CHAR(36) PRIMARY KEY,
        balance DOUBLE NOT NULL DEFAULT 0.0,
        version BIGINT NOT NULL DEFAULT 0,
        last_updated BIGINT NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sqlSales);
            stmt.execute(sqlPlayers);
        } catch (SQLException e) {
            LogUtil.error("DDL 执行失败，请检查数据库权限或表结构。", e);
        }
    }

    // ==================== 模块 A: 玩家画像存取 (SSoT) ====================

    public static PlayerData loadPlayerData(UUID uuid) {
        if (dataSource == null) return new PlayerData(uuid, 0.0, 0);

        // [修改]: 同时读取 balance 和 version
        String sql = "SELECT balance, version FROM ecobridge_players WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, uuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new PlayerData(uuid, rs.getDouble("balance"), rs.getLong("version"));
                }
            }
        } catch (SQLException e) {
            LogUtil.error("读取玩家 SQL 失败: " + uuid, e);
        }
        return new PlayerData(uuid, 0.0, 0);
    }

    public static void updateBalance(UUID uuid, double balance) {
        if (dbExecutor == null) return;
        dbExecutor.execute(() -> updateBalanceSync(uuid, balance));
    }

    /**
     * 同步持久化 + 乐观锁重试机制
     */
    public static void updateBalanceSync(UUID uuid, double balance) {
        if (dataSource == null) return;

        // [修改]: 乐观锁更新 SQL
        String updateSql = "UPDATE ecobridge_players SET balance = ?, version = version + 1, last_updated = ? WHERE uuid = ? AND version = ?";
        String insertSql = "INSERT IGNORE INTO ecobridge_players (uuid, balance, version, last_updated) VALUES (?, ?, 0, ?)";

        long now = System.currentTimeMillis();
        int maxRetries = 3;
        SQLException lastEx = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            PlayerData cached = HotDataCache.get(uuid);
            long currentVersion = (cached != null) ? cached.getVersion() : -1;

            try (Connection conn = dataSource.getConnection()) {
                // 如果数据库没记录，先尝试插入
                if (currentVersion == -1) {
                    try (PreparedStatement ipstmt = conn.prepareStatement(insertSql)) {
                        ipstmt.setString(1, uuid.toString());
                        ipstmt.setDouble(2, balance);
                        ipstmt.setLong(3, now);
                        if (ipstmt.executeUpdate() > 0) return;
                    }
                }

                // 执行 CAS (Compare And Swap) 更新
                try (PreparedStatement upstmt = conn.prepareStatement(updateSql)) {
                    upstmt.setDouble(1, balance);
                    upstmt.setLong(2, now);
                    upstmt.setString(3, uuid.toString());
                    upstmt.setLong(4, Math.max(0, currentVersion));

                    int affected = upstmt.executeUpdate();
                    if (affected > 0) {
                        // 更新成功，同步本地版本号
                        if (cached != null) cached.setVersion(currentVersion + 1);
                        return;
                    }
                }

                // 走到这里说明版本冲突，重新加载最新版本并重试
                PlayerData fresh = loadPlayerData(uuid);
                if (cached != null) cached.setVersion(fresh.getVersion());

                if (attempt < maxRetries) {
                    Thread.sleep(50L * attempt);
                }

            } catch (SQLException e) {
                lastEx = e;
                if (isFatalError(e)) break;
                try { Thread.sleep(100L * attempt); } catch (InterruptedException ie) { break; }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        LogUtil.error("持久化玩家余额最终失败 (乐观锁冲突/重试耗尽): " + uuid, lastEx);
    }

    // ==================== 模块 B: 市场交易流备份 ====================

    public static void saveSaleAsync(UUID uuid, String productId, double amount) {
        if (dbExecutor == null) return;
        dbExecutor.execute(() -> {
            String sql = "INSERT INTO ecobridge_sales(player_uuid, product_id, amount, timestamp) VALUES(?,?,?,?)";
            try (Connection conn = dataSource.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, uuid != null ? uuid.toString() : "SYSTEM");
                pstmt.setString(2, productId);
                pstmt.setDouble(3, amount);
                pstmt.setLong(4, System.currentTimeMillis());
                pstmt.executeUpdate();

            } catch (SQLException e) {
                LogUtil.error("写入 SQL 交易历史失败: " + productId, e);
            }
        });
    }

    // ==================== 模块 C: 行为经济学数据支持 ====================

    public static double get7DayAverage(String productId) {
        if (dataSource == null) return 0.0;

        String sql = "SELECT AVG(ABS(amount)) FROM ecobridge_sales WHERE product_id = ? AND timestamp > ?";
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);

        try (Connection conn = dataSource.getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, productId);
            pstmt.setLong(2, cutoff);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (SQLException e) {
            LogUtil.error("滑动地板数据回溯异常: " + productId, e);
        }
        return 0.0;
    }

    public static List<SaleRecord> getProductHistory(String productId, int daysLimit) {
        if (dataSource == null) return new ArrayList<>();

        List<SaleRecord> history = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysLimit);

        String sql = "SELECT timestamp, amount FROM ecobridge_sales WHERE product_id = ? AND timestamp > ? " +
        "ORDER BY timestamp DESC LIMIT 5000";

        try (Connection conn = dataSource.getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, productId);
            pstmt.setLong(2, cutoff);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    history.add(new SaleRecord(rs.getLong("timestamp"), rs.getDouble("amount")));
                }
            }
        } catch (SQLException e) {
            LogUtil.error("回溯商品 SQL 冷数据异常: " + productId, e);
        }
        return history;
    }

    private static boolean isFatalError(SQLException e) {
        String state = e.getSQLState();
        if (state == null) return false;
        return state.startsWith("42") || state.startsWith("23");
    }

    public static synchronized void close() {
        LogUtil.info("正在释放 SQL 资源...");
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
        if (dataSource != null) {
            if (!dataSource.isClosed()) dataSource.close();
            dataSource = null;
        }
    }

    public static HikariDataSource getDataSource() {
        return dataSource;
    }
}
