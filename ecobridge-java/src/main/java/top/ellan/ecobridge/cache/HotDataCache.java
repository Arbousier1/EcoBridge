package top.ellan.ecobridge.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.database.DatabaseManager;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.util.LogUtil;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家数据热点缓存 (HotDataCache v0.9.0 - Cross-Pool Collaboration)
 * 职责：维护在线玩家的高频交易数据快照，并实现专用的 IO 线程隔离。
 */
public class HotDataCache {

    private static final Cache<UUID, PlayerData> CACHE = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterAccess(Duration.ofHours(2))
            .removalListener((UUID uuid, PlayerData data, RemovalCause cause) -> {
                if (data == null) return;
                if (cause != RemovalCause.REPLACED) {
                    saveAsync(uuid, data, "CACHE_" + cause.name());
                }
            })
            .build();

    /**
     * 跨池加载逻辑
     * 1. 在 DatabaseManager 的固定池执行 JDBC 阻塞查询
     * 2. 在 Bukkit 主线程执行缓存挂载与 API 交互
     */
    public static void load(UUID uuid) {
        DatabaseManager.getExecutor().execute(() -> {
            try {
                // 阻塞型 IO：在平台线程池中执行
                PlayerData data = TransactionDao.loadPlayerData(uuid);

                // 逻辑回调：切换回主线程处理 Bukkit 实体
                Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        CACHE.put(uuid, data);
                        LogUtil.debug("已为玩家 " + p.getName() + " 完成数据挂载 (Version: " + data.getVersion() + ")");
                    } else {
                        LogUtil.debug("拦截到过时加载回调 (" + uuid + ")，玩家已离线。");
                    }
                });
            } catch (Exception e) {
                LogUtil.error("玩家 " + uuid + " 数据热加载发生致命错误！", e);
            }
        });
    }

    public static PlayerData get(UUID uuid) {
        return CACHE.getIfPresent(uuid);
    }

    public static void invalidate(UUID uuid) {
        CACHE.invalidate(uuid);
    }

    /**
     * 异步回写逻辑
     * 显式使用 DatabaseManager.getExecutor() 以保护虚拟线程载体池
     */
    private static void saveAsync(UUID uuid, PlayerData data, String reason) {
        DatabaseManager.getExecutor().execute(() -> {
            try {
                TransactionDao.updateBalanceBlocking(uuid, data.getBalance());
                if (LogUtil.isDebugEnabled()) {
                    LogUtil.debug("数据写回成功 [" + reason + "]: " + uuid + " (Balance: " + data.getBalance() + ")");
                }
            } catch (Exception e) {
                LogUtil.error("异步写回失败: " + uuid, e);
            }
        });
    }

    /**
     * 关机时的同步保存
     */
    public static void saveAllSync() {
        LogUtil.info("正在执行关机前的全量热数据强制同步...");
        var snapshotMap = CACHE.asMap();

        for (var entry : snapshotMap.entrySet()) {
            TransactionDao.updateBalanceBlocking(entry.getKey(), entry.getValue().getBalance());
        }

        CACHE.invalidateAll();
        LogUtil.info("所有活跃数据已安全落盘。");
    }

    public static class PlayerData {
        private final UUID uuid;
        private final AtomicLong balanceBits;
        private volatile long version;

        public PlayerData(UUID uuid, double initialBalance, long version) {
            this.uuid = uuid;
            this.balanceBits = new AtomicLong(Double.doubleToRawLongBits(initialBalance));
            this.version = version;
        }

        public UUID getUuid() { return uuid; }

        public double getBalance() {
            return Double.longBitsToDouble(balanceBits.get());
        }

        public long getVersion() {
            return version;
        }

        public void setVersion(long version) {
            this.version = version;
        }

        public void updateFromTruth(double newBalance) {
            balanceBits.set(Double.doubleToRawLongBits(newBalance));
        }

        public void setBalance(double newBalance) {
            updateFromTruth(newBalance);
        }
    }
}