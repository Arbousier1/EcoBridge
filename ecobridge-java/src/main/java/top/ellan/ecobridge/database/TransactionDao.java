package top.ellan.ecobridge.database;

import top.ellan.ecobridge.cache.HotDataCache;
import top.ellan.ecobridge.cache.HotDataCache.PlayerData;
import top.ellan.ecobridge.model.SaleRecord;
import top.ellan.ecobridge.util.LogUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 交易数据访问对象 (TransactionDao v0.8.10-Batch-Query)
 * <p>
 * 职责：
 * 1. 玩家资产数据的 SSoT (Single Source of Truth) 读取与持久化
 * 2. 处理乐观锁并发写入
 * 3. 提供市场分析所需的历史交易数据
 * 4. 【新增】批量查询商品历史均价，优化定价引擎性能
 */
public class TransactionDao {

    // ==================== 模块 A: 玩家画像存取 (SSoT) ====================

    /**
     * 从数据库加载玩家数据（包含余额和乐观锁版本号）
     */
    public static PlayerData loadPlayerData(UUID uuid) {
        // 快速失败检查
        if (!DatabaseManager.isConnected()) {
            return new PlayerData(uuid, 0.0, 0);
        }

        String sql = "SELECT balance, version FROM ecobridge_players WHERE uuid = ?";
        try (Connection conn = DatabaseManager.getConnection();
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
        // 如果数据库无记录，返回默认空对象
        return new PlayerData(uuid, 0.0, 0);
    }

    /**
     * 异步更新玩家余额
     */
    public static void updateBalance(UUID uuid, double balance) {
        if (DatabaseManager.getExecutor() == null) return;
        DatabaseManager.getExecutor().execute(() -> updateBalanceBlocking(uuid, balance));
    }

    /**
     * 同步持久化玩家余额（包含乐观锁重试机制）
     * 该方法是线程安全的，处理了并发写入冲突。
     */
    public static void updateBalanceBlocking(UUID uuid, double balance) {
        if (!DatabaseManager.isConnected()) return;

        // CAS 更新语句：只有版本号匹配时才更新，且版本号自增
        String updateSql = "UPDATE ecobridge_players SET balance = ?, version = version + 1, last_updated = ? WHERE uuid = ? AND version = ?";
        // 插入语句：如果不存在则插入
        String insertSql = "INSERT IGNORE INTO ecobridge_players (uuid, balance, version, last_updated) VALUES (?, ?, 0, ?)";

        long now = System.currentTimeMillis();
        int maxRetries = 3; // 最大重试次数
        SQLException lastEx = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // 获取缓存中的当前版本号
            PlayerData cached = HotDataCache.get(uuid);
            long currentVersion = (cached != null) ? cached.getVersion() : -1;

            try (Connection conn = DatabaseManager.getConnection()) {
                // 1. 如果本地没有版本号（或者它是新玩家），尝试插入
                if (currentVersion == -1) {
                    try (PreparedStatement ipstmt = conn.prepareStatement(insertSql)) {
                        ipstmt.setString(1, uuid.toString());
                        ipstmt.setDouble(2, balance);
                        ipstmt.setLong(3, now);
                        // 如果影响行数 > 0，说明插入成功，直接返回
                        if (ipstmt.executeUpdate() > 0) return;
                    }
                }

                // 2. 执行 CAS 更新
                try (PreparedStatement upstmt = conn.prepareStatement(updateSql)) {
                    upstmt.setDouble(1, balance);
                    upstmt.setLong(2, now);
                    upstmt.setString(3, uuid.toString());
                    upstmt.setLong(4, Math.max(0, currentVersion));

                    int affected = upstmt.executeUpdate();
                    if (affected > 0) {
                        // 更新成功，同步内存中的版本号，避免下次无谓重试
                        if (cached != null) cached.setVersion(currentVersion + 1);
                        return;
                    }
                }

                // 3. 走到这里说明更新失败（版本号不匹配，发生了并发修改）
                // 重新从数据库加载最新版本号
                PlayerData fresh = loadPlayerData(uuid);
                if (cached != null) cached.setVersion(fresh.getVersion());

                // 简单的指数退避等待
                if (attempt < maxRetries) {
                    Thread.sleep(50L * attempt);
                }

            } catch (SQLException e) {
                lastEx = e;
                if (isFatalError(e)) break; // 如果是致命错误（如语法错误），直接停止重试
                try { Thread.sleep(100L * attempt); } catch (InterruptedException ie) { break; }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        LogUtil.error("持久化玩家余额最终失败 (乐观锁冲突/重试耗尽): " + uuid, lastEx);
    }

    // ==================== 模块 B: 市场交易流备份 ====================

    /**
     * 异步记录交易流水（用于市场分析和回溯）
     */
    public static void saveSaleAsync(UUID uuid, String productId, double amount) {
        if (DatabaseManager.getExecutor() == null) return;
        
        DatabaseManager.getExecutor().execute(() -> {
            String sql = "INSERT INTO ecobridge_sales(player_uuid, product_id, amount, timestamp) VALUES(?,?,?,?)";
            try (Connection conn = DatabaseManager.getConnection();
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

    /**
     * 获取指定商品过去 7 天的平均交易量（绝对值）
     */
    public static double get7DayAverage(String productId) {
        if (!DatabaseManager.isConnected()) return 0.0;

        String sql = "SELECT AVG(ABS(amount)) FROM ecobridge_sales WHERE product_id = ? AND timestamp > ?";
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);

        try (Connection conn = DatabaseManager.getConnection();
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

    /**
     * 【新增】批量获取多个商品过去 7 天的平均交易量（绝对值）
     * 
     * @param productIds 要查询的商品ID列表
     * @return Map<商品ID, 7天平均交易量>
     */
    public static Map<String, Double> get7DayAveragesBatch(List<String> productIds) {
        // 快速失败检查
        if (productIds == null || productIds.isEmpty() || !DatabaseManager.isConnected()) {
            return new HashMap<>();
        }
        
        Map<String, Double> resultMap = new HashMap<>();
        // 为所有商品ID初始化默认值为0.0
        for (String productId : productIds) {
            resultMap.put(productId, 0.0);
        }
        
        // 构建IN子句的占位符
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        String sql = "SELECT product_id, AVG(ABS(amount)) as avg_amount FROM ecobridge_sales " +
                     "WHERE product_id IN (" + placeholders + ") " +
                     "AND timestamp > ? " +
                     "GROUP BY product_id";
        
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            // 设置商品ID参数
            for (int i = 0; i < productIds.size(); i++) {
                pstmt.setString(i + 1, productIds.get(i));
            }
            // 设置时间参数
            pstmt.setLong(productIds.size() + 1, cutoff);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String productId = rs.getString("product_id");
                    double avgAmount = rs.getDouble("avg_amount");
                    resultMap.put(productId, avgAmount);
                }
                // 不需要为未查询到的商品设置默认值，因为我们已经初始化了所有商品ID为0.0
            }
        } catch (SQLException e) {
            LogUtil.error("批量历史均价查询失败", e);
            // 降级：返回已初始化的默认值(0.0)
        }
        
        return resultMap;
    }

    /**
     * 获取商品的历史交易记录（用于计算 Neff 和波动率）
     */
    public static List<SaleRecord> getProductHistory(String productId, int daysLimit) {
        if (!DatabaseManager.isConnected()) return new ArrayList<>();

        List<SaleRecord> history = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysLimit);

        String sql = "SELECT timestamp, amount FROM ecobridge_sales WHERE product_id = ? AND timestamp > ? " +
                "ORDER BY timestamp DESC LIMIT 5000";

        try (Connection conn = DatabaseManager.getConnection();
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
        // 42xxx: 语法错误, 23xxx: 约束违反
        return state.startsWith("42") || state.startsWith("23");
    }
}