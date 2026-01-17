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
import top.ellan.ecobridge.collector.ActivityCollector;
import top.ellan.ecobridge.manager.EconomicStateManager;
import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.util.HolidayManager;
import top.ellan.ecobridge.util.InternalPlaceholder;
import top.ellan.ecobridge.util.InternalPlaceholder.QuotaData;

/**
 * EcoBridge 占位符扩展 (PAPI Hook)
 * 适配 UltimateShop ConfigManager API
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
        // --- 1. 全局与系统变量 ---
        if (params.equals("inflation")) {
             return String.format("%.2f%%", EconomyManager.getInstance().getInflationRate() * 100);
        }
        if (params.equals("stability")) return String.format("%.2f", EconomyManager.getInstance().getStabilityFactor());
        if (params.equals("is_holiday")) return HolidayManager.isTodayHoliday() ? "是" : "否";
        if (params.equals("holiday_mult")) return String.format("%.1fx", HolidayManager.getHolidayEpsilonFactor());
        
        // Native 状态
        if (params.startsWith("native_")) {
             return "Check Console"; 
        }

        // --- 2. 玩家变量 ---
        if (offlinePlayer != null && offlinePlayer.isOnline()) {
            Player player = offlinePlayer.getPlayer();
            
            if (params.equals("player_hours")) {
                return String.format("%.1f", ActivityCollector.capture(player, 48.0).hours());
            }
            if (params.equals("player_is_newbie")) {
                return (ActivityCollector.capture(player, 48.0).isNewbie() & 1) == 1 ? "新手" : "资深";
            }

            // --- 3. 动态配额变量 ---
            // 格式: quota_<类型>_<ProductID>
            if (params.startsWith("quota_")) {
                return handleQuotaRequest(player, params);
            }
        }

        // --- 4. 市场状态 ---
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

    /**
     * 处理配额请求
     */
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
        
        if (targetItem == null) {
            return "Unknown";
        }

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

    /**
     * 辅助方法：通过 ProductID 查找 ObjectItem
     * 遍历 ConfigManager 中的所有商店
     */
    private ObjectItem findObjectItem(String productId) {
        ConfigManager cm = ConfigManager.configManager;
        if (cm == null) return null;

        for (ObjectShop shop : cm.getShops()) {
            // UltimateShop API: shop.getProduct(id)
            ObjectItem item = shop.getProduct(productId);
            if (item != null) {
                return item;
            }
        }
        return null;
    }
}