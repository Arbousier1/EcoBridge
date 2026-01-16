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
 * UShop 适配器 - 强化生命周期安全版 (v0.8.9-Strict)
 */
public final class UShopProvider {

    private static final Cache<String, Double> NEFF_CACHE = Caffeine.newBuilder()
    .maximumSize(2000)
    .expireAfterWrite(500, TimeUnit.MILLISECONDS)
    .build();

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

    public static double calculateDynamicPrice(Player player, ObjectItem item, int amount) {
        if (player == null || item == null || item.empty) return 0.0;

        // 1. 基准价前置校验
        if (!isVaultEconomy(item)) {
            return PriceOracle.getOriginalBasePrice(item, amount < 0);
        }

        double p0 = PriceOracle.getOriginalBasePrice(item, amount < 0);
        if (p0 <= 0) return 0.0;

        // 2. 核心状态校验：如果 Native 引擎未载入，直接返回基准价 [cite: 82, 87]
        if (!NativeBridge.isLoaded()) {
            return p0;
        }

        String shopId = item.getShop();
        String productId = item.getProduct();

        // 3. 严格受限的作用域：Arena.ofConfined() 确保内存线程安全且即用即弃
        try (Arena arena = Arena.ofConfined()) {
            // 获取经济参数
            double inflation = EconomyManager.getInstance().getInflationRate();
            var activity = ActivityCollector.capture(player, 48.0);
            double lambda = EcoBridge.getInstance().getConfig().getDouble("economy.lambda", 0.01);

            // 获取或计算 nEff (成交量向量)
            String compositeKey = shopId + ":" + productId;
            double nEff = NEFF_CACHE.get(compositeKey, k -> {
                double tau = EcoBridge.getInstance().getConfig().getDouble("economy.tau", 7.0);
                return NativeBridge.queryNeffVectorized(System.currentTimeMillis(), tau);
            });

            // --- Native 内存对齐分配 ---
            MemorySegment ctx = arena.allocate(NativeBridge.Layouts.TRADE_CONTEXT);
            MemorySegment cfg = prepareMarketConfig(arena, shopId, productId);

            // 填充 TradeContext
            NativeBridge.VH_CTX_BASE_PRICE.set(ctx, 0L, p0);
            NativeBridge.VH_CTX_CURR_AMT.set(ctx, 0L, (double) Math.abs(amount));
            NativeBridge.VH_CTX_INF_RATE.set(ctx, 0L, inflation);
            NativeBridge.VH_CTX_TIMESTAMP.set(ctx, 0L, System.currentTimeMillis());
            NativeBridge.VH_CTX_PLAY_TIME.set(ctx, 0L, (long) activity.seconds());
            NativeBridge.VH_CTX_TIMEZONE_OFFSET.set(ctx, 0L, java.time.OffsetDateTime.now().getOffset().getTotalSeconds());

            // 构造身份/环境掩码 (Newbie + Holiday)
            int mask = (activity.isNewbie() == 1 ? 1 : 0) |
            ((HolidayManager.isTodayHoliday() ? 1 : 0) << 1);
            NativeBridge.VH_CTX_NEWBIE_MASK.set(ctx, 0L, mask);

            // --- 关键 FFI 调用 ---
            // 调用 calculateEpsilon 计算环境因子
            double epsilon = NativeBridge.calculateEpsilon(ctx, cfg);

            // 调用最终定价函数并将结果立即转换为 primitive 类型，脱离内存段依赖 [cite: 36, 83]
            return NativeBridge.computePrice(p0, nEff, 0.0, lambda, epsilon);

        } catch (Throwable e) {
            // 兜底策略：内存泄露或内核 Panic 时返回原价，并打印详细错误 [cite: 37, 79]
            LogUtil.error("物理定价内核演算严重故障 [" + productId + "]", e);
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
        // 严格分配：确保 Layout 包含完整的 MarketConfig 结构 [cite: 41, 96]
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
