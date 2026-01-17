package top.ellan.ecobridge.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.util.LogUtil;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家数据热点缓存 (HotDataCache v0.8.9 - Optimistic Lock Support)
 * 职责：维护在线玩家的高频交易数据快照，并携带版本号以支持数据库乐观锁。
 */
public class HotDataCache {

    // 缓存配置：写入后 2 小时过期，最大容量 2000 人
    private static final Cache<UUID, PlayerData> CACHE = Caffeine.newBuilder()
    .maximumSize(2000)
    .expireAfterAccess(Duration.ofHours(2))
    .removalListener((UUID uuid, PlayerData data, RemovalCause cause) -> {
        if (data == null) return;
        // 只有非替换操作（如过期、手动移除）才触发异步回写
        if (cause != RemovalCause.REPLACED) {
            saveAsync(uuid, data, "CACHE_" + cause.name());
        }
    })
    .build();

    /**
     * 异步加载玩家数据到缓存
     */
    public static void load(UUID uuid) {
        CompletableFuture.runAsync(() -> {
            try {
                // TransactionDao.loadPlayerData 现在会返回包含版本号的 PlayerData
                PlayerData data = TransactionDao.loadPlayerData(uuid);

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
        }, EcoBridge.getInstance().getVirtualExecutor());
    }

    public static PlayerData get(UUID uuid) {
        return CACHE.getIfPresent(uuid);
    }

    public static void invalidate(UUID uuid) {
        CACHE.invalidate(uuid);
    }

    private static void saveAsync(UUID uuid, PlayerData data, String reason) {
        EcoBridge.getInstance().getVirtualExecutor().execute(() -> {
            // 这里调用 updateBalanceBlocking 触发乐观锁写入逻辑
            TransactionDao.updateBalanceBlocking(uuid, data.getBalance());

            if (LogUtil.isDebugEnabled()) {
                LogUtil.debug("数据写回成功 [" + reason + "]: " + uuid + " (Balance: " + data.getBalance() + ")");
            }
        });
    }

    /**
     * 关机时的全量同步保存
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

    /**
     * 线程安全的玩家数据容器
     */
    public static class PlayerData {
        private final UUID uuid;
        private final AtomicLong balanceBits;
        private volatile long version; // [v0.8.9 新增] 对应数据库中的 version 字段

        public PlayerData(UUID uuid, double initialBalance, long version) {
            this.uuid = uuid;
            this.balanceBits = new AtomicLong(Double.doubleToRawLongBits(initialBalance));
            this.version = version;
        }

        public UUID getUuid() { return uuid; }

        public double getBalance() {
            return Double.longBitsToDouble(balanceBits.get());
        }

        /**
         * 获取当前数据版本号
         */
        public long getVersion() {
            return version;
        }

        /**
         * 更新本地版本号
         * 仅当 TransactionDao 写入成功后调用
         */
        public void setVersion(long version) {
            this.version = version;
        }

        /**
         * 镜像同步方法
         */
        public void updateFromTruth(double newBalance) {
            balanceBits.set(Double.doubleToRawLongBits(newBalance));
        }

        public void setBalance(double newBalance) {
            updateFromTruth(newBalance);
        }
    }
}
