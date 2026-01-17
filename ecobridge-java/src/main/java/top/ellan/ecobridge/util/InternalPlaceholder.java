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
import top.ellan.ecobridge.manager.PricingManager;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * EcoBridge 内部变量注册器 - 最终修正版
 */
public final class InternalPlaceholder {

    public static final int PHYSICAL_HARD_CAP = 2000;
    public static final int PHYSICAL_OPTIMAL_CAP = 500;

    private InternalPlaceholder() {}

    /**
     * 1. 获取全局经济变量
     */
    @NotNull
    public static TagResolver getGlobalResolver() {
        EconomyManager eco = EconomyManager.getInstance();
        double currentKp = 0, currentKi = 0, currentKd = 0;

        if (NativeBridge.isLoaded() && PricingManager.getInstance() != null) {
            MemorySegment pidSeg = PricingManager.getInstance().getGlobalPidState();
            if (pidSeg != null && pidSeg.address() != 0) {
                currentKp = pidSeg.get(ValueLayout.JAVA_DOUBLE, 0);
                currentKi = pidSeg.get(ValueLayout.JAVA_DOUBLE, 8);
                currentKd = pidSeg.get(ValueLayout.JAVA_DOUBLE, 16);
            }
        }

        return TagResolver.resolver(
            Placeholder.unparsed("inflation", String.format("%.2f%%", eco.getInflationRate() * 100)),
            Placeholder.unparsed("stability", String.format("%.2f", eco.getStabilityFactor())),
            Placeholder.unparsed("market_heat", String.format("%.1f", eco.getMarketHeat())),
            Placeholder.unparsed("eco_saturation", String.format("%.2f%%", eco.getEcoSaturation() * 100)),
            Placeholder.unparsed("pid_kp", String.format("%.3f", currentKp)),
            Placeholder.unparsed("pid_ki", String.format("%.3f", currentKi)),
            Placeholder.unparsed("pid_kd", String.format("%.3f", currentKd)),
            Placeholder.unparsed("holiday_status", HolidayManager.isTodayHoliday() ? "是" : "否"),
            Placeholder.unparsed("holiday_mult", String.format("%.1fx", HolidayManager.getHolidayEpsilonFactor()))
        );
    }

    /**
     * 2. 系统底层 FFM 状态
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
     * 3. 玩家画像 (修复 int 转换为 boolean 的错误)
     */
    @NotNull
    public static TagResolver getPlayerResolver(@Nullable Player player) {
        if (player == null) return TagResolver.empty();
        
        var snapshot = ActivityCollector.capture(player, 48.0);
        
        // 关键修复：将 int 类型的 isNewbie() 显式与 1 比较
        String tag = (snapshot.isNewbie() == 1) ? "新手" : "资深";
        
        return TagResolver.resolver(
            getGlobalResolver(),
            Placeholder.unparsed("player_hours", String.format("%.1f", snapshot.hours())),
            Placeholder.unparsed("newbie_tag", tag)
        );
    }

    /**
     * 4. 市场分析
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
     * 5. 配额监控 (Record 方法访问修正)
     */
    @NotNull
    public static TagResolver getQuotaResolver(@NotNull Player player, @NotNull ObjectItem item) {
        QuotaData data = calculateQuota(player, item);

        return TagResolver.resolver(
            Placeholder.unparsed("quota_used", String.valueOf(data.used())),
            Placeholder.unparsed("quota_limit", String.valueOf(data.hardLimit())),
            Placeholder.unparsed("quota_optimal", String.valueOf(data.optimalLimit())),
            Placeholder.unparsed("quota_remaining", String.valueOf(data.remaining())),
            Placeholder.unparsed("quota_percent", String.format("%.1f%%", data.percent()))
        );
    }

    // --- 数据模型 ---

    public record QuotaData(int used, int hardLimit, int optimalLimit, int remaining, double percent) {}

    public static QuotaData calculateQuota(@NotNull Player player, @NotNull ObjectItem item) {
        ObjectUseTimesCache cache = ShopHelper.getPlayerUseTimesCache(item, player);
        int used = (cache != null) ? cache.getSellUseTimes() : 0;
        
        int hardLimit = PHYSICAL_HARD_CAP;
        int optimalLimit = PHYSICAL_OPTIMAL_CAP;
        int remaining = Math.max(0, hardLimit - used);
        
        double percent = ((double) used / hardLimit) * 100.0;
        if (percent > 100.0) percent = 100.0;

        return new QuotaData(used, hardLimit, optimalLimit, remaining, percent);
    }
}