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
 * 玩家数据热点缓存 (HotDataCache v0.7.5 - Concurrency Hardened)
 * 职责：维护在线玩家的高频交易数据，减少数据库 IO 压力。
 * <p>
 * 修复日志 (v0.7.5):
 * - [Fix] 修复了 LogUtil.error 参数不匹配导致的编译错误。
 * - [Critical] 修复 PlayerData.addAndGet 中的 CAS 无限自旋风险 (活锁防御)。
 * - [Safety] 增加浮点数溢出与 NaN 检查。
 * - [Perf] 引入 Thread.onSpinWait() 优化 CPU 调度。
 */
public class HotDataCache {

    // 缓存配置：写入后 2 小时过期（防止僵尸数据），最大容量 2000 人
    private static final Cache<UUID, PlayerData> CACHE = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterAccess(Duration.ofHours(2))
            .removalListener((UUID uuid, PlayerData data, RemovalCause cause) -> {
                if (data == null) return;
                // 只有非替换操作（如过期、手动移除）才触发异步回写，避免 update 时的冗余 IO
                if (cause != RemovalCause.REPLACED) {
                    saveAsync(uuid, data, "CACHE_" + cause.name());
                }
            })
            .build();

    /**
     * 异步加载玩家数据到缓存
     * 通常在玩家 PlayerJoinEvent 时调用
     */
    public static void load(UUID uuid) {
        CompletableFuture.runAsync(() -> {
            try {
                // 调用 DAO 获取完整的 PlayerData 对象
                PlayerData data = TransactionDao.loadPlayerData(uuid);
                
                // 回到主线程（或安全线程）放入缓存
                Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        CACHE.put(uuid, data);
                        LogUtil.debug("已为玩家 " + p.getName() + " 完成原子路径数据挂载。");
                    } else {
                        // 如果加载完成时玩家已离线，为了数据安全，立即回写一次
                        saveAsync(uuid, data, "LATE_LOAD_PROTECT");
                    }
                });
            } catch (Exception e) {
                LogUtil.error("玩家 " + uuid + " 数据热加载发生致命错误！", e);
            }
        }, EcoBridge.getInstance().getVirtualExecutor());
    }

    /**
     * 获取玩家热数据
     * @return 可能为 null (如果未命中缓存)
     */
    public static PlayerData get(UUID uuid) {
        return CACHE.getIfPresent(uuid);
    }

    /**
     * 使缓存失效（触发下线回写）
     */
    public static void invalidate(UUID uuid) {
        CACHE.invalidate(uuid);
    }

    /**
     * 内部异步保存逻辑
     */
    private static void saveAsync(UUID uuid, PlayerData data, String reason) {
        EcoBridge.getInstance().getVirtualExecutor().execute(() -> {
            TransactionDao.updateBalance(uuid, data.getBalance());
            
            if (LogUtil.isDebugEnabled()) {
                LogUtil.debug("数据写回成功 [" + reason + "]: " + uuid + " (Balance: " + data.getBalance() + ")");
            }
        });
    }

    /**
     * 关机时的全量同步保存
     * 警告：此操作会阻塞主线程，仅在 onDisable 使用
     */
    public static void saveAllSync() {
        LogUtil.info("正在执行关机前的全量热数据强制同步...");
        var snapshotMap = CACHE.asMap();
        
        for (var entry : snapshotMap.entrySet()) {
            TransactionDao.updateBalanceSync(entry.getKey(), entry.getValue().getBalance());
        }
        
        CACHE.invalidateAll();
        LogUtil.info("所有活跃数据已安全落盘。");
    }

    /**
     * 线程安全的玩家数据容器
     * 使用 AtomicLong 存储 double 的位表示，实现无锁并发读写
     */
    public static class PlayerData {
        private final UUID uuid;
        private final AtomicLong balanceBits;

        public PlayerData(UUID uuid, double initialBalance) {
            this.uuid = uuid;
            this.balanceBits = new AtomicLong(Double.doubleToRawLongBits(initialBalance));
        }

        public UUID getUuid() { return uuid; }

        public double getBalance() {
            return Double.longBitsToDouble(balanceBits.get());
        }

        public void setBalance(double newBalance) {
            balanceBits.set(Double.doubleToRawLongBits(newBalance));
        }

        /**
         * 原子性增加余额 (修复版)
         * 包含：有界重试、浮点检查、SpinWait 优化、审计日志
         *
         * @param delta 变化量
         * @return 变化后的新余额
         */
        public double addAndGet(double delta) {
            int retries = 0;
            // 1. 有界自旋，防止活锁
            while (retries < 1000) {
                long currentBits = balanceBits.get();
                double currentVal = Double.longBitsToDouble(currentBits);
                double nextVal = currentVal + delta;

                // 2. 防止浮点溢出或 NaN 污染
                if (Double.isInfinite(nextVal) || Double.isNaN(nextVal)) {
                    // [修复] 创建异常对象并将其传递给 LogUtil.error(String, Throwable)
                    ArithmeticException overflowEx = new ArithmeticException("Balance overflow/NaN detected");
                    LogUtil.error("余额计算溢出拦截! UUID: " + uuid + " Cur: " + currentVal + " Delta: " + delta, overflowEx);
                    throw overflowEx;
                }

                long nextBits = Double.doubleToRawLongBits(nextVal);

                // 3. CAS 尝试
                if (balanceBits.compareAndSet(currentBits, nextBits)) {
                    // 4. 简易审计日志 (仅在 Debug 模式开启)
                    if (LogUtil.isDebugEnabled()) {
                        LogUtil.debug(String.format("Audit [%s]: %.2f -> %.2f (Delta: %.2f)", uuid, currentVal, nextVal, delta));
                    }
                    return nextVal;
                }

                retries++;
                // 5. JEP 285 CPU 节能优化 (SpinWait Hint)
                if (retries % 100 == 0) {
                    Thread.onSpinWait();
                }
            }
            
            // 超过重试次数，抛出异常让上层重试或降级
            IllegalStateException contentionEx = new IllegalStateException("CAS contention too high for player " + uuid);
            LogUtil.error("CAS 自旋重试次数超过上限 (1000次)", contentionEx);
            throw contentionEx;
        }
    }
}