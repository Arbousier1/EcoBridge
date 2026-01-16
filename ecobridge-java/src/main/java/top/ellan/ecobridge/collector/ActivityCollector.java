// ==================================================
// FILE: ecobridge-java/src/main/java/top/ellan/ecobridge/collector/ActivityCollector.java
// ==================================================

package top.ellan.ecobridge.collector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;

import java.util.concurrent.CompletableFuture;

/**
 * 活跃度数据采集器 (ActivityCollector v1.0 - Thread-Safe Gatekeeper)
 * <p>
 * 核心职责：
 * 1. 提供玩家活跃度快照 (PlayTime)。
 * 2. [安全核心] 强制主线程门禁，杜绝异步调用 Bukkit API 导致的 ConcurrentModificationException。
 * 3. [架构修复] 修复了 TradeListener 在虚拟线程中调用此方法导致的潜在 Crash 风险。
 */
public class ActivityCollector {

    private static final long TICKS_PER_SECOND = 20L;
    private static final double SECONDS_PER_HOUR = 3600.0;

    /**
     * [主线程同步采集]
     * <p>
     * 强一致性检查：
     * 如果检测到在异步线程调用，立即拦截并返回安全的兜底数据（老手状态），
     * 同时打印警告堆栈，帮助开发者定位错误的调用源。
     *
     * @param player 目标玩家
     * @param newbieThresholdHours 新手阈值(小时)
     * @return 活跃度快照
     */
    public static ActivitySnapshot capture(Player player, double newbieThresholdHours) {
        // [Thread Gate] 严禁在异步线程触碰 Bukkit 实体 API
        // Paper/Spigot 的 getStatistic 不是线程安全的
        if (!Bukkit.isPrimaryThread()) {
            EcoBridge.getInstance().getLogger().warning(
                "[线程安全拦截] ActivityCollector.capture 检测到非法异步调用！" +
                "调用方必须在主线程执行。玩家: " + player.getName()
            );
            // 兜底策略：返回一个"极大值"（视作老手），避免误判为新手导致经济漏洞
            // 360000s = 100h, isNewbie = 0
            return new ActivitySnapshot(360000L, 100.0, 0); 
        }

        // 1. 获取统计数据 (仅主线程安全)
        long totalTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long totalSeconds = totalTicks / TICKS_PER_SECOND;

        // 2. 转换量纲
        double hours = (double) totalSeconds / SECONDS_PER_HOUR;

        // 3. 计算位掩码 (Bit 0: Newbie)
        int newbieBit = (hours < newbieThresholdHours) ? 1 : 0;

        return new ActivitySnapshot(totalSeconds, hours, newbieBit);
    }

    /**
     * [异步兼容接口]
     * <p>
     * 如果当前已在主线程，立即返回；
     * 如果在异步线程，自动调度到主线程获取并回调。
     * 适合在 CompletableFuture 链中使用。
     * * @param player 目标玩家
     * @param newbieThresholdHours 新手阈值
     * @return 包含快照的 Future
     */
    public static CompletableFuture<ActivitySnapshot> captureAsync(Player player, double newbieThresholdHours) {
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(capture(player, newbieThresholdHours));
        }
        
        CompletableFuture<ActivitySnapshot> future = new CompletableFuture<>();
        // 调度回主线程
        Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
            try {
                // 此时已在主线程，安全调用 capture
                future.complete(capture(player, newbieThresholdHours));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public record ActivitySnapshot(long seconds, double hours, int isNewbie) {}

    /**
     * GUI/Chat 展示组件生成
     * (通常由命令触发，天然在主线程，但仍通过 capture 享受门禁保护)
     */
    public static Component toComponent(Player player) {
        // 默认新手线 48h
        var snapshot = capture(player, 48.0);

        String color = snapshot.hours() < 10 ? "<red>" : (snapshot.hours() < 50 ? "<yellow>" : "<green>");
        double displayHours = Math.floor(snapshot.hours() * 10) / 10.0;

        return MiniMessage.miniMessage().deserialize(
            "<gray>活跃等级: " + color + "<hours>h <dark_gray>(<sec>s) <gray>新手状态: <newbie>",
            Placeholder.unparsed("hours", String.valueOf(displayHours)),
            Placeholder.unparsed("sec", String.valueOf(snapshot.seconds())),
            Placeholder.unparsed("newbie", (snapshot.isNewbie() & 1) == 1 ? "<yellow>是" : "<green>否")
        );
    }
}