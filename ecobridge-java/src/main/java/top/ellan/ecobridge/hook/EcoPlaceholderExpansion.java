package top.ellan.ecobridge.hook;

import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.ObjectShop;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.collector.ActivityCollector;
import top.ellan.ecobridge.manager.EconomicStateManager;
import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.manager.PricingManager;
import top.ellan.ecobridge.util.HolidayManager;
import top.ellan.ecobridge.util.InternalPlaceholder;
import top.ellan.ecobridge.util.InternalPlaceholder.QuotaData;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * EcoBridge 占位符扩展
 * 适配 50人在线环境，集成 PID 监控与系统健康度变量
 */
public class EcoPlaceholderExpansion extends PlaceholderExpansion {

    private final EcoBridge plugin;

    public EcoPlaceholderExpansion(EcoBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ecobridge";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Ellan";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        EconomyManager eco = EconomyManager.getInstance();

        // --- 1. 宏观经济变量 ---
        if (params.equals("inflation")) return String.format("%.2f%%", eco.getInflationRate() * 100);
        if (params.equals("stability")) return String.format("%.2f", eco.getStabilityFactor());
        if (params.equals("market_heat")) return String.format("%.1f", eco.getMarketHeat());
        if (params.equals("eco_saturation")) return String.format("%.2f%%", eco.getEcoSaturation() * 100);

        // 实时 PID 增益 (Native 内存嗅探)
        if (params.startsWith("pid_")) {
            if (!NativeBridge.isLoaded() || PricingManager.getInstance() == null) return "0.000";
            MemorySegment pidSeg = PricingManager.getInstance().getGlobalPidState();
            if (pidSeg == null || pidSeg.address() == 0) return "0.000";

            return switch (params) {
                case "pid_kp" -> String.format("%.3f", pidSeg.get(ValueLayout.JAVA_DOUBLE, 0));
                case "pid_ki" -> String.format("%.3f", pidSeg.get(ValueLayout.JAVA_DOUBLE, 8));
                case "pid_kd" -> String.format("%.3f", pidSeg.get(ValueLayout.JAVA_DOUBLE, 16));
                default -> "0.000";
            };
        }

        if (params.equals("is_holiday")) return HolidayManager.isTodayHoliday() ? "是" : "否";
        if (params.equals("holiday_mult")) return String.format("%.1fx", HolidayManager.getHolidayEpsilonFactor());

        // --- 2. Native 系统监控 (修复编译错误点) ---
        if (params.startsWith("native_")) {
            if (!NativeBridge.isLoaded()) return "未加载";
            if (params.equals("native_status")) return "已就绪";
            
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment totalPtr = arena.allocate(ValueLayout.JAVA_LONG);
                MemorySegment droppedPtr = arena.allocate(ValueLayout.JAVA_LONG);
                NativeBridge.getHealthStats(totalPtr, droppedPtr);
                
                if (params.equals("native_logs")) return String.valueOf(totalPtr.get(ValueLayout.JAVA_LONG, 0));
                // 修复：直接获取值并返回，不再赋值给未定义的 droppedLogs 变量
                if (params.equals("native_dropped")) return String.valueOf(droppedPtr.get(ValueLayout.JAVA_LONG, 0));
            } catch (Throwable e) { return "Error"; }
        }

        // --- 3. 玩家变量 ---
        if (offlinePlayer != null && offlinePlayer.isOnline()) {
            Player player = offlinePlayer.getPlayer();
            var snapshot = ActivityCollector.capture(player, 48.0);

            if (params.equals("player_hours")) return String.format("%.1f", snapshot.hours());
            if (params.equals("player_is_newbie")) {
                return (snapshot.isNewbie() == 1) ? "新手" : "资深";
            }

            if (params.startsWith("quota_")) {
                return handleQuotaRequest(player, params);
            }
        }

        // --- 4. 市场分析状态 ---
        if (params.startsWith("state_color_")) {
            String pid = params.substring(12);
            var phase = EconomicStateManager.getInstance().analyzeMarketAndNotify(pid, 0.0);
            return switch (phase) {
                case STABLE -> "&a";
                case SATURATED -> "&e";
                case EMERGENCY -> "&c";
                case HEALING -> "&b";
            };
        }
        if (params.startsWith("state_")) {
            String pid = params.substring(6);
            return EconomicStateManager.getInstance().analyzeMarketAndNotify(pid, 0.0).name();
        }

        return null;
    }

    private String handleQuotaRequest(Player player, String params) {
        String type;
        String productId;

        if (params.startsWith("quota_optimal_")) {
            type = "optimal";
            productId = params.substring("quota_optimal_".length());
        } else if (params.startsWith("quota_limit_")) {
            type = "limit";
            productId = params.substring("quota_limit_".length());
        } else if (params.startsWith("quota_remaining_")) {
            type = "remaining";
            productId = params.substring("quota_remaining_".length());
        } else if (params.startsWith("quota_used_")) {
            type = "used";
            productId = params.substring("quota_used_".length());
        } else if (params.startsWith("quota_percent_")) {
            type = "percent";
            productId = params.substring("quota_percent_".length());
        } else {
            return null;
        }

        ObjectItem targetItem = findObjectItem(productId);
        if (targetItem == null) return "Unknown";

        QuotaData data = InternalPlaceholder.calculateQuota(player, targetItem);

        return switch (type) {
            case "optimal" -> String.valueOf(data.optimalLimit());
            case "limit" -> String.valueOf(data.hardLimit());
            case "remaining" -> String.valueOf(data.remaining());
            case "used" -> String.valueOf(data.used());
            case "percent" -> String.format("%.1f%%", data.percent());
            default -> "Error";
        };
    }

    private ObjectItem findObjectItem(String productId) {
        ConfigManager cm = ConfigManager.configManager;
        if (cm == null) return null;
        for (ObjectShop shop : cm.getShops()) {
            ObjectItem item = shop.getProduct(productId);
            if (item != null) return item;
        }
        return null;
    }
}