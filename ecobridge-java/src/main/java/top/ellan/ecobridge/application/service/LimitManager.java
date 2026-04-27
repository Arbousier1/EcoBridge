package top.ellan.ecobridge.application.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.api.EcoLimitAPI;
import top.ellan.ecobridge.api.ItemLimit;
import top.ellan.ecobridge.api.MarketPhase;
import top.ellan.ecobridge.infrastructure.persistence.storage.ActivityCollector;
import top.ellan.ecobridge.infrastructure.persistence.storage.ActivityCollector.ActivitySnapshot;
import top.ellan.ecobridge.util.LogUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * LimitManager (v2.4 - Cleaned)
 * 修复了重复方法定义和语法错误。
 */
public class LimitManager implements EcoLimitAPI {

    private final EcoBridge plugin;
    private final Map<String, ConfigurationSection> itemConfigCache = new HashMap<>();
    // [v2.0] Secondary index: productId → full key(s) for O(1) lookup on cache miss
    private final Map<String, String> productIdIndex = new HashMap<>();

    public LimitManager(EcoBridge plugin) {
        this.plugin = plugin;
        reloadCache();
    }

    public void reloadCache() {
        itemConfigCache.clear();
        productIdIndex.clear();

        FileConfiguration itemsConfig = ItemConfigManager.get();
        if (itemsConfig == null) {
            LogUtil.warn("LimitManager: items.yml 尚未加载，无法构建缓存。");
            return;
        }

        ConfigurationSection section = itemsConfig.getConfigurationSection("item-settings");
        if (section == null) return;

        int count = 0;
        for (String shopKey : section.getKeys(false)) {
            ConfigurationSection shopSection = section.getConfigurationSection(shopKey);
            if (shopSection == null) continue;

            for (String productId : shopSection.getKeys(false)) {
                String fullKey = shopKey + "." + productId;
                itemConfigCache.put(fullKey, shopSection.getConfigurationSection(productId));
                // Index the last segment for O(1) reverse lookup
                productIdIndex.putIfAbsent(productId, fullKey);
                count++;
            }
        }
        LogUtil.info("LimitManager 配置缓存已构建，索引了 " + count + " 个特殊物品配置。");
    }

    @Override
    public Optional<ItemLimit> getItemLimit(UUID playerUuid, String productId) {
        PricingManager pm = PricingManager.getInstance();
        EconomicStateManager esm = EconomicStateManager.getInstance();

        if (pm == null || esm == null) return Optional.empty();

        double currentPrice = pm.getSnapshotPrice(null, productId);
        if (currentPrice <= 0) {
            currentPrice = getSpecificConfig(productId, "base-price", 100.0);
        }

        double limit = calculateDynamicLimit(playerUuid, productId);
        var internalPhase = esm.analyzeMarketAndNotify(productId, 0.0);
        MarketPhase apiPhase = convertPhase(internalPhase);

        return Optional.of(new ItemLimit(
            productId,
            currentPrice,
            plugin.getConfig().getDouble("economy.sell-ratio", 0.5),
            getSpecificConfig(productId, "lambda", 0.002),
            apiPhase,
            limit,
            apiPhase == MarketPhase.EMERGENCY
        ));
    }

    @Override
    public boolean isBlockedByDynamicLimit(UUID playerUuid, String productId, double quantity) {
        double currentLimit = calculateDynamicLimit(playerUuid, productId);
        return quantity > currentLimit;
    }

    public boolean isBlockedBySellLimit(UUID playerUuid, String productId, double quantity) {
        double currentLimit = calculateSellLimit(playerUuid, productId);
        return quantity > currentLimit;
    }

    public boolean isBlockedByPlayerQuota(UUID playerUuid, String shopId, String productId, double quantity) {
        PlayerMarketPolicyService policy = PlayerMarketPolicyService.getInstance();
        if (policy == null) return false;
        return policy.isBlockedByQuota(playerUuid, quantity);
    }

    private double calculateDynamicLimit(UUID playerUuid, String productId) {
        ActivitySnapshot snapshot = ActivityCollector.getSafeSnapshot(playerUuid);
        double playHours = snapshot.playTimeSeconds() / 3600.0;

        double baseLimit = getSpecificConfig(productId, "base-limit", 64.0);
        double growthRate = getSpecificConfig(productId, "growth-rate", 16.0);
        double maxLimit = getSpecificConfig(productId, "max-limit", 512.0);

        double calculated = baseLimit + (growthRate * Math.sqrt(playHours));
        return Math.min(maxLimit, calculated);
    }

    public double calculateSellLimit(UUID playerUuid, String productId) {
        ActivitySnapshot snapshot = ActivityCollector.getSafeSnapshot(playerUuid);
        double playHours = snapshot.playTimeSeconds() / 3600.0;

        double baseLimit = getSpecificConfig(productId, "sell-base-limit", 
                           getSpecificConfig(productId, "base-limit", 64.0) * 2.0);
        
        double growthRate = getSpecificConfig(productId, "sell-growth-rate", 
                            getSpecificConfig(productId, "growth-rate", 16.0) * 2.0);
        
        double maxLimit = getSpecificConfig(productId, "sell-max-limit", 2048.0);

        double calculated = baseLimit + (growthRate * Math.sqrt(playHours));
        return Math.min(maxLimit, calculated);
    }

    private double getSpecificConfig(String id, String key, double def) {
        ConfigurationSection itemSection = itemConfigCache.get(id);

        if (itemSection == null) {
            // [v2.0] O(1) reverse lookup via secondary index (was O(n) linear scan)
            String indexedKey = productIdIndex.get(id);
            if (indexedKey != null) {
                itemSection = itemConfigCache.get(indexedKey);
            }
        }

        if (itemSection != null) {
            return itemSection.getDouble(key, plugin.getConfig().getDouble("economy.audit-settings.default-" + key, def));
        }
        return plugin.getConfig().getDouble("economy.audit-settings.default-" + key, def);
    }

    @Override
    public double getEstimatedTax(@Nullable Player player, double amount) {
        EconomyManager em = EconomyManager.getInstance();
        if (em == null) return 0.0;

        double baseTaxRate = plugin.getConfig().getDouble("economy.audit-settings.base-tax-rate", 0.05);
        double inflationRate = em.getInflationRate();
        
        return amount * baseTaxRate * (1.0 + Math.max(0, inflationRate));
    }

    @Override
    public String getMarketColor(String productId) {
        EconomicStateManager esm = EconomicStateManager.getInstance();
        if (esm == null) return "<white>";

        var internalPhase = esm.analyzeMarketAndNotify(productId, 0.0);
        
        return switch (internalPhase) {
            case STABLE -> "<green>";
            case SATURATED -> "<yellow>";
            case EMERGENCY -> "<red>";
            case HEALING -> "<aqua>";
        };
    }

    private MarketPhase convertPhase(EconomicStateManager.MarketPhase internal) {
        return switch (internal) {
            case STABLE -> MarketPhase.STABLE;
            case SATURATED -> MarketPhase.SATURATED;
            case EMERGENCY -> MarketPhase.EMERGENCY;
            case HEALING -> MarketPhase.HEALING;
        };
    }
}
