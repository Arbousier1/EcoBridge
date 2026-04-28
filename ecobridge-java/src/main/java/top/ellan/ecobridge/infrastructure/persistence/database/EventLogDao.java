package top.ellan.ecobridge.infrastructure.persistence.database;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * H2 Embedded Database DAO for economy event logging (v2.0).
 *
 * <p>Replaces the previous DuckDB-based Rust storage layer.
 * H2 is pure Java — no native compilation needed, zero CI overhead.
 *
 * <p>Tables:
 * <pre>
 *   economy_log (ts, player_uuid, delta, balance, metadata, market_key)
 * </pre>
 */
public final class EventLogDao {

    private static final String DB_URL = "jdbc:h2:"
        + EcoBridge.getInstance().getDataFolder().getAbsolutePath()
        + "/ecobridge_vault;AUTO_SERVER=TRUE";

    private static volatile boolean initialized;

    private EventLogDao() {}

    public static synchronized void init() {
        if (initialized) return;
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            LogUtil.error("H2 driver not found", e);
            return;
        }

        try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS economy_log (
                    ts BIGINT NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    delta DOUBLE NOT NULL,
                    balance DOUBLE NOT NULL,
                    metadata VARCHAR(512),
                    market_key VARCHAR(256)
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ts ON economy_log(ts)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_market ON economy_log(market_key, ts)");

            initialized = true;
            LogUtil.info("<gradient:blue:cyan>H2 economy log initialized</gradient>");
        } catch (SQLException e) {
            LogUtil.error("H2 init failed", e);
        }
    }

    // ==================== Write ====================

    public static void logEvent(long ts, String uuid, double delta, double balance,
                                 String metadata, String marketKey) {
        if (!initialized) return;
        String sql = "INSERT INTO economy_log (ts, player_uuid, delta, balance, metadata, market_key) VALUES (?,?,?,?,?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ts);
            ps.setString(2, uuid);
            ps.setDouble(3, delta);
            ps.setDouble(4, balance);
            ps.setString(5, metadata);
            ps.setString(6, marketKey);
            ps.executeUpdate();

            // Also push to Rust hot memory for SIMD computation
            if (marketKey != null && !marketKey.isEmpty()) {
                NativeBridge.injectRemoteTradeForKey(marketKey, NativeBridge.moneyToMicros(delta));
            }
        } catch (SQLException e) {
            LogUtil.warnOnce("H2_WRITE_ERR", "H2 write failed: " + e.getMessage());
        }
    }

    // ==================== Query: N_eff ====================

    public static double queryNeff(long currentTs, double tauDays, String marketKey) {
        if (!initialized) return 0.0;
        // Use Rust in-memory for fast queries; fall back to H2
        double memResult = NativeBridge.queryNeffForKey(currentTs, tauDays, marketKey);
        if (memResult > 0) return memResult;

        // H2 fallback
        long msPerDay = 86_400_000L;
        long lookback = (long) (tauDays * msPerDay * 10.0);
        long minTs = currentTs - lookback;
        String sql = "SELECT COALESCE(SUM(delta * EXP(-1.0 * (? - ts) / (? * 86400000.0))), 0) FROM economy_log WHERE ts > ? AND market_key = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, currentTs);
            ps.setDouble(2, tauDays);
            ps.setLong(3, minTs);
            ps.setString(4, marketKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0.0;
            }
        } catch (SQLException e) {
            return 0.0;
        }
    }

    // ==================== Query: History ====================

    public static List<long[]> loadRecentHistory(int days) {
        List<long[]> records = new ArrayList<>();
        if (!initialized) return records;

        long cutoff = System.currentTimeMillis() - (days * 86_400_000L);
        String sql = "SELECT ts, delta FROM economy_log WHERE ts > ? ORDER BY ts ASC";
        try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new long[]{rs.getLong(1), Double.doubleToLongBits(rs.getDouble(2))});
                }
            }
        } catch (SQLException e) {
            LogUtil.warnOnce("H2_READ_ERR", "H2 history load failed: " + e.getMessage());
        }
        return records;
    }

    public static Map<String, List<long[]>> loadRecentMarketHistory(int days) {
        Map<String, List<long[]>> result = new HashMap<>();
        if (!initialized) return result;

        long cutoff = System.currentTimeMillis() - (days * 86_400_000L);
        String sql = "SELECT ts, delta, market_key FROM economy_log WHERE ts > ? AND market_key IS NOT NULL ORDER BY ts ASC";
        try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(3);
                    if (key == null || key.isEmpty()) continue;
                    result.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new long[]{rs.getLong(1), Double.doubleToLongBits(rs.getDouble(2))});
                }
            }
        } catch (SQLException e) {
            LogUtil.warnOnce("H2_MARKET_READ_ERR", "H2 market history load failed: " + e.getMessage());
        }
        return result;
    }

    // ==================== Maintenance ====================

    public static void shutdown() {
        initialized = false;
        // H2 auto-closes connections; just flag
        LogUtil.info("H2 economy log shut down");
    }

    public static boolean isInitialized() { return initialized; }
}
