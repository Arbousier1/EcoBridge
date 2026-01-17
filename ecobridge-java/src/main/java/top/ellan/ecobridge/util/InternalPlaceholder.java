package top.ellan.ecobridge.util;

import cn.superiormc.ultimateshop.api.ShopHelper;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.caches.ObjectUseTimesCache;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.collector.ActivityCollector;
import top.ellan.ecobridge.manager.EconomicStateManager;
import top.ellan.ecobridge.manager.EconomyManager;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * EcoBridge 内部变量注册器 (物理限额修正版)
 * 职责：提供统一的变量计算逻辑，供 PAPI 和内部消息使用。
 */
public final class InternalPlaceholder {

    // --- 物理常量定义 (与内核保持一致) ---
    public static final int PHYSICAL_HARD_CAP = 2000;    // 市场饱和/熔断线 (超过此值触发红色警告)
    public static final int PHYSICAL_OPTIMAL_CAP = 500;  // 最佳收益线 (超过此值开始收益递减)

    // 私有构造
    private InternalPlaceholder() {}

    /**
     * 1. 获取全局经济变量
     */
    @NotNull
    public static TagResolver getGlobalResolver() {
        return TagResolver.resolver(
            Placeholder.unparsed("inflation", 
                String.format("%.2f%%", EconomyManager.getInstance().getInflationRate() * 100)),
            Placeholder.unparsed("stability", 
                String.format("%.2f", EconomyManager.getInstance().getStabilityFactor())),
            Placeholder.unparsed("holiday_status", 
                HolidayManager.isTodayHoliday() ? "是" : "否"),
            Placeholder.unparsed("holiday_mult", 
                String.format("%.1fx", HolidayManager.getHolidayEpsilonFactor()))
        );
    }

    /**
     * 2. 获取系统底层监控变量 (FFM)
     */
    @NotNull
    public static TagResolver getSystemResolver() {
        String status = NativeBridge.isLoaded() ? "已就绪" : "未加载";
        long totalLogs = 0;
        long droppedLogs = 0;

        if (NativeBridge.isLoaded()) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment totalPtr = arena.allocate(ValueLayout.JAVA_LONG);
                MemorySegment droppedPtr = arena.allocate(ValueLayout.JAVA_LONG);
                NativeBridge.getHealthStats(totalPtr, droppedPtr);
                totalLogs = totalPtr.get(ValueLayout.JAVA_LONG, 0L);
                droppedLogs = droppedPtr.get(ValueLayout.JAVA_LONG, 0L);
            } catch (Throwable ignored) {}
        }

        return TagResolver.resolver(
            Placeholder.unparsed("native_status", status),
            Placeholder.unparsed("native_logs", String.valueOf(totalLogs)),
            Placeholder.unparsed("native_dropped", String.valueOf(droppedLogs))
        );
    }

    /**
     * 3. 获取玩家画像变量
     */
    @NotNull
    public static TagResolver getPlayerResolver(@Nullable Player player) {
        if (player == null) return TagResolver.empty();
        var snapshot = ActivityCollector.capture(player, 48.0);
        return TagResolver.resolver(
            getGlobalResolver(),
            Placeholder.unparsed("player_hours", String.format("%.1f", snapshot.hours())),
            Placeholder.unparsed("newbie_tag", (snapshot.isNewbie() & 1) == 1 ? "新手" : "资深")
        );
    }

    /**
     * 4. 获取市场阶段变量
     */
    @NotNull
    public static TagResolver getMarketResolver(@NotNull String productId) {
        var phase = EconomicStateManager.getInstance().analyzeMarketAndNotify(productId, 0.0);
        String color = switch (phase) {
            case STABLE -> "<green>";
            case SATURATED -> "<yellow>";
            case EMERGENCY -> "<red>";
            case HEALING -> "<aqua>";
        };
        return TagResolver.resolver(
            Placeholder.unparsed("market_phase", phase.name()),
            Placeholder.parsed("market_color", color)
        );
    }

    /**
     * 5. 获取配额变量 (供内部 GUI 使用)
     */
    @NotNull
    public static TagResolver getQuotaResolver(@NotNull Player player, @NotNull ObjectItem item) {
        // 复用下方的计算逻辑
        QuotaData data = calculateQuota(player, item);

        return TagResolver.resolver(
            Placeholder.unparsed("quota_used", String.valueOf(data.used)),
            Placeholder.unparsed("quota_limit", String.valueOf(data.hardLimit)),
            Placeholder.unparsed("quota_optimal", String.valueOf(data.optimalLimit)),
            Placeholder.unparsed("quota_remaining", String.valueOf(data.remaining)),
            Placeholder.unparsed("quota_percent", String.format("%.1f%%", data.percent))
        );
    }

    // --- 数据计算核心 (供 PAPI 和 Resolver 共用) ---

    public record QuotaData(int used, int hardLimit, int optimalLimit, int remaining, double percent) {}

    public static QuotaData calculateQuota(@NotNull Player player, @NotNull ObjectItem item) {
        ObjectUseTimesCache cache = ShopHelper.getPlayerUseTimesCache(item, player);
        int used = (cache != null) ? cache.getSellUseTimes() : 0;
        
        // 强制使用物理常量，忽略 UltimateShop 配置文件的 -1
        int hardLimit = PHYSICAL_HARD_CAP;
        int optimalLimit = PHYSICAL_OPTIMAL_CAP;
        int remaining = Math.max(0, hardLimit - used);
        
        double percent = ((double) used / hardLimit) * 100.0;
        if (percent > 100.0) percent = 100.0;

        return new QuotaData(used, hardLimit, optimalLimit, remaining, percent);
    }
}