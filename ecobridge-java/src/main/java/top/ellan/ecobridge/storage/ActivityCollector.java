package top.ellan.ecobridge.storage;

import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.ellan.ecobridge.EcoBridge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家活跃度采集器 (ActivityCollector v1.1.1 - API Completion)
 * * 职责：
 * 1. 在主线程定时同步 Bukkit 非线程安全的统计数据。
 * 2. 为异步定价引擎和转账审计提供 O(1) 延迟且线程安全的快照访问。
 */
public final class ActivityCollector {

    // 存储玩家统计数据的原子快照缓存
    private static final Map<UUID, ActivitySnapshot> SNAPSHOT_CACHE = new ConcurrentHashMap<>();

    /**
     * 玩家统计数据快照
     * @param playTimeSeconds 总玩时长（秒）
     * @param activityScore   活跃度评分 (0.0 - 1.0)
     * @param status          状态码 (0: 正常, 1: 缺省/未加载)
     */
    public record ActivitySnapshot(long playTimeSeconds, double activityScore, int status) {}

    /**
     * 物理采集：将 Bukkit 统计数据同步到缓存
     * 注意：此方法必须在主线程调用
     */
    public static void updateSnapshot(@NotNull Player player) {
        if (!Bukkit.isPrimaryThread()) {
            return; // 防御性编程：禁止在非主线程尝试物理采集
        }

        // 获取总玩时长（单位：Tick，1秒=20Ticks）
        long totalTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long seconds = totalTicks / 20;

        // 计算活跃度评分（示例：以 20 小时为满分基准的线性平滑）
        // 72000秒 = 20小时
        double score = Math.min(1.0, (double) seconds / 72000.0);

        SNAPSHOT_CACHE.put(player.getUniqueId(), new ActivitySnapshot(seconds, score, 0));
    }

    /**
     * [修复编译错误] 获取玩家当前的活跃度评分
     * @param uuid 玩家UUID
     * @return 活跃度评分 (0.0 - 1.0)
     */
    public static double getScore(@NotNull UUID uuid) {
        return getSafeSnapshot(uuid).activityScore();
    }

    /**
     * 安全获取：为异步引擎提供快照访问
     * 此方法在任何线程调用都是安全的
     */
    @NotNull
    public static ActivitySnapshot getSafeSnapshot(@NotNull UUID uuid) {
        return SNAPSHOT_CACHE.getOrDefault(uuid, new ActivitySnapshot(0, 0.0, 1));
    }

    /**
     * 清理缓存：在玩家退出时调用，防止内存泄漏
     */
    public static void removePlayer(@NotNull UUID uuid) {
        SNAPSHOT_CACHE.remove(uuid);
    }

    /**
     * 启动心跳同步任务
     * 建议在插件 onEnable 中调用
     */
    public static void startHeartbeat(@NotNull EcoBridge plugin) {
        // 每 5 分钟 (6000 Ticks) 批量同步一次
        // 如果需要更实时的审计，可以缩短为 1 分钟 (1200L)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updateSnapshot(player);
            }
        }, 100L, 6000L); 
    }
}