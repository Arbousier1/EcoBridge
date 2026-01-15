package top.ellan.ecobridge.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.ThingType;
import cn.superiormc.ultimateshop.objects.items.prices.ObjectPrices;
import cn.superiormc.ultimateshop.objects.items.prices.ObjectSinglePrice;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.collector.ActivityCollector;
import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.util.HolidayManager;
import top.ellan.ecobridge.util.LogUtil;
import top.ellan.ecobridge.util.PriceOracle;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * UltimateShop 数据适配器 (v0.8.9-Precision-Patch)
 * 职责：作为 Java 25 与 UltimateShop 之间的胶水层。
 * <p>
 * 架构变更日志：
 * 1. **OOM Protection**: [v0.8.9 Fix] 使用 Caffeine 替换 ConcurrentHashMap，并设定 maximumSize(2000) 防止内存溢出。
 * 2. **Nano-Cache**: 切换至 500ms 自动过期策略，大幅削减 FFI 调用背压。
 * 3. **SSoT Alignment**: 严格遵循 Rust v0.8 models.rs 的内存布局。
 */
public final class UShopProvider {

    // --- Tick-Loop Cache (Caffeine 驅動的有界原子緩存) ---
    // [v0.8.9 Fix] 確保長期服在高併發、多商店環境下不會發生 OOM
    private static final Cache<String, Double> NEFF_CACHE = Caffeine.newBuilder()
    .maximumSize(2000)
    .expireAfterWrite(500, TimeUnit.MILLISECONDS)
    .build();

    // --- 内存字段映射 ---
    private static final VarHandle VH_CFG_LAMBDA;
    private static final VarHandle VH_CFG_VOLATILITY;
    private static final VarHandle VH_CFG_S_AMP;
    private static final VarHandle VH_CFG_W_MULT;
    private static final VarHandle VH_CFG_N_PROT;

    private static final VarHandle VH_CFG_W_SEASONAL;
    private static final VarHandle VH_CFG_W_WEEKEND;
    private static final VarHandle VH_CFG_W_NEWBIE;
    private static final VarHandle VH_CFG_W_INFLATION;

    static {
        var layout = NativeBridge.Layouts.MARKET_CONFIG;
        VH_CFG_LAMBDA = layout.varHandle(MemoryLayout.PathElement.groupElement("base_lambda"));
        VH_CFG_VOLATILITY = layout.varHandle(MemoryLayout.PathElement.groupElement("volatility_factor"));
        VH_CFG_S_AMP = layout.varHandle(MemoryLayout.PathElement.groupElement("seasonal_amplitude"));
        VH_CFG_W_MULT = layout.varHandle(MemoryLayout.PathElement.groupElement("weekend_multiplier"));
        VH_CFG_N_PROT = layout.varHandle(MemoryLayout.PathElement.groupElement("newbie_protection_rate"));

        VH_CFG_W_SEASONAL = layout.varHandle(MemoryLayout.PathElement.groupElement("seasonal_weight"));
        VH_CFG_W_WEEKEND = layout.varHandle(MemoryLayout.PathElement.groupElement("weekend_weight"));
        VH_CFG_W_NEWBIE = layout.varHandle(MemoryLayout.PathElement.groupElement("newbie_weight"));
        VH_CFG_W_INFLATION = layout.varHandle(MemoryLayout.PathElement.groupElement("inflation_weight"));
    }

    /**
     * 为商店物品演算动态价格
     */
    public static double calculateDynamicPrice(Player player, ObjectItem item, int amount) {
        if (player == null || item == null || item.empty) return 0.0;

        if (!isVaultEconomy(item)) {
            return PriceOracle.getOriginalBasePrice(item, amount < 0);
        }

        double p0 = PriceOracle.getOriginalBasePrice(item, amount < 0);
        if (p0 <= 0) return 0.0;

        String shopId = item.getShop();
        String productId = item.getProduct();

        try (Arena arena = Arena.ofConfined()) {

            double inflation = EconomyManager.getInstance().getInflationRate();
            var activity = ActivityCollector.capture(player, 48.0);

            // [v0.8.9 Fix] 高效緩存存取：同一商品在 500ms 內的所有請求均共享同一 Neff 結果
            String compositeKey = shopId + ":" + productId;
            Double cachedNeff = NEFF_CACHE.getIfPresent(compositeKey);
            double nEff;

            if (cachedNeff != null) {
                nEff = cachedNeff;
            } else {
                // 緩存未命中或已失效，調用 FFI 穿透至 Rust 物理引擎
                double tau = EcoBridge.getInstance().getConfig().getDouble("economy.tau", 7.0);
                nEff = NativeBridge.queryNeffVectorized(System.currentTimeMillis(), tau);

                NEFF_CACHE.put(compositeKey, nEff);
            }

            // 构建 TradeContext
            MemorySegment ctx = arena.allocate(NativeBridge.Layouts.TRADE_CONTEXT);
            NativeBridge.VH_CTX_BASE_PRICE.set(ctx, 0L, p0);
            NativeBridge.VH_CTX_CURR_AMT.set(ctx, 0L, (double) Math.abs(amount));
            NativeBridge.VH_CTX_INF_RATE.set(ctx, 0L, inflation);
            NativeBridge.VH_CTX_TIMESTAMP.set(ctx, 0L, System.currentTimeMillis());
            NativeBridge.VH_CTX_PLAY_TIME.set(ctx, 0L, (long) activity.seconds());

            int zoneOffset = java.time.OffsetDateTime.now().getOffset().getTotalSeconds();
            NativeBridge.VH_CTX_TIMEZONE_OFFSET.set(ctx, 0L, zoneOffset);

            int isNewbie = (activity.isNewbie() == 1) ? 1 : 0;
            boolean forceFestival = EcoBridge.getInstance().getConfig().getBoolean("economy.force-festival-mode", false);
            int isHoliday = (forceFestival || HolidayManager.isTodayHoliday()) ? 1 : 0;

            int combinedMask = (isHoliday << 1) | isNewbie;
            NativeBridge.VH_CTX_NEWBIE_MASK.set(ctx, 0L, combinedMask);

            MemorySegment cfg = prepareMarketConfig(arena, shopId, productId);

            double epsilon = NativeBridge.calculateEpsilon(ctx, cfg);
            double lambda = EcoBridge.getInstance().getConfig().getDouble("economy.lambda", 0.01);

            return NativeBridge.computePrice(p0, nEff, 0.0, lambda, epsilon);

        } catch (Throwable e) {
            LogUtil.error("UShop 定价内核调用失败 [" + productId + "]", e);
            return p0;
        }
    }

    private static boolean isVaultEconomy(ObjectItem item) {
        ObjectPrices buyPrice = item.getBuyPrice();
        if (buyPrice == null || buyPrice.empty) return false;
        Collection<ObjectSinglePrice> prices = buyPrice.singlePrices;
        return prices != null && prices.stream()
        .anyMatch(sp -> sp.type == ThingType.HOOK_ECONOMY && isVaultHook(sp));
    }

    private static boolean isVaultHook(ObjectSinglePrice sp) {
        ConfigurationSection section = sp.singleSection;
        if (section == null) return false;
        String economyPlugin = section.getString("economy-plugin");
        return "Vault".equalsIgnoreCase(economyPlugin);
    }

    private static MemorySegment prepareMarketConfig(Arena arena, String shopId, String productId) {
        var globalConfig = EcoBridge.getInstance().getConfig();
        MemorySegment cfg = arena.allocate(NativeBridge.Layouts.MARKET_CONFIG);

        String itemPath = "item-settings." + shopId + "." + productId + ".";

        VH_CFG_LAMBDA.set(cfg, 0L, globalConfig.getDouble("economy.lambda", 0.01));
        VH_CFG_VOLATILITY.set(cfg, 0L, 1.0);
        VH_CFG_S_AMP.set(cfg, 0L, globalConfig.getDouble("economy.seasonal-amplitude", 0.15));
        VH_CFG_W_MULT.set(cfg, 0L, globalConfig.getDouble("economy.weekend-multiplier", 1.2));
        VH_CFG_N_PROT.set(cfg, 0L, globalConfig.getDouble("economy.newbie-protection", 0.2));

        VH_CFG_W_SEASONAL.set(cfg, 0L, globalConfig.getDouble(itemPath + "weights.seasonal", 0.25));
        VH_CFG_W_WEEKEND.set(cfg, 0L, globalConfig.getDouble(itemPath + "weights.weekend", 0.25));
        VH_CFG_W_NEWBIE.set(cfg, 0L, globalConfig.getDouble(itemPath + "weights.newbie", 0.25));
        VH_CFG_W_INFLATION.set(cfg, 0L, globalConfig.getDouble(itemPath + "weights.inflation", 0.25));

        return cfg;
    }
}
