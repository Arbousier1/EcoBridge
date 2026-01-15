package top.ellan.ecobridge.api;

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
import java.util.concurrent.atomic.AtomicReference; // [v0.8.3 Fix] 引入原子引用

/**
 * UltimateShop 数据适配器 (v0.8.3-Concurrency-Patch)
 * 职责：作为 Java 25 与 UltimateShop 之间的胶水层。
 * <p>
 * 架构变更日志：
 * 1. **Tick-Loop Cache**: 升级为 AtomicReference 快照机制，修复并发幻读问题。
 * 2. **Holiday Injection**: 集成 HolidayManager 进行智能节假日判断。
 * 3. **SSoT Alignment**: 严格遵循 Rust v0.8 models.rs 的内存布局。
 */
public final class UShopProvider {

    // --- Tick-Loop Cache (原子快照缓存) ---
    // [v0.8.3 Fix] 使用 Record 封装值与时间戳，确保读取的一致性
    private record NeffSnapshot(double value, long timestamp) {}

    // 初始化一个空的快照，时间戳为 0 确保首次必然过期
    private static final AtomicReference<NeffSnapshot> NEFF_CACHE = 
            new AtomicReference<>(new NeffSnapshot(0.0, 0L));
    
    private static final long CACHE_TTL_MS = 50;

    // --- 内存字段映射 (MarketConfig SSoT) ---
    private static final VarHandle VH_CFG_LAMBDA;
    private static final VarHandle VH_CFG_VOLATILITY;
    private static final VarHandle VH_CFG_S_AMP;
    private static final VarHandle VH_CFG_W_MULT;
    private static final VarHandle VH_CFG_N_PROT;
    
    // 权重字段 VarHandles (扁平化布局)
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
        
        // 绑定语义化权重字段
        VH_CFG_W_SEASONAL = layout.varHandle(MemoryLayout.PathElement.groupElement("seasonal_weight"));
        VH_CFG_W_WEEKEND = layout.varHandle(MemoryLayout.PathElement.groupElement("weekend_weight"));
        VH_CFG_W_NEWBIE = layout.varHandle(MemoryLayout.PathElement.groupElement("newbie_weight"));
        VH_CFG_W_INFLATION = layout.varHandle(MemoryLayout.PathElement.groupElement("inflation_weight"));
    }

    /**
     * 为商店物品演算动态价格
     * 核心公式：P = P0 * exp(-lambda * Neff) * epsilon
     */
    public static double calculateDynamicPrice(Player player, ObjectItem item, int amount) {
        if (player == null || item == null || item.empty) return 0.0;

        // 仅处理绑定了 Vault 的物品
        if (!isVaultEconomy(item)) {
            return PriceOracle.getOriginalBasePrice(item, amount < 0);
        }

        double p0 = PriceOracle.getOriginalBasePrice(item, amount < 0);
        if (p0 <= 0) return 0.0;

        String shopId = item.getShop(); 
        String productId = item.getProduct(); 

        try (Arena arena = Arena.ofConfined()) {
            
            // 1. 获取基础环境数据
            double inflation = EconomyManager.getInstance().getInflationRate();
            var activity = ActivityCollector.capture(player, 48.0);
            
            // 2. [v0.8.3 Fix] 原子化缓存检查
            // 获取当前时刻的快照（引用获取是原子的）
            long now = System.currentTimeMillis();
            NeffSnapshot snapshot = NEFF_CACHE.get();
            double nEff;
            
            // 检查快照是否有效
            if (now - snapshot.timestamp() < CACHE_TTL_MS) {
                // 有效：直接使用快照中的值，此时值与时间戳绝对匹配
                nEff = snapshot.value();
            } else {
                // 失效：重新计算并更新快照
                // 注意：高并发下可能有多个线程同时计算，这对缓存是可以接受的（Last Write Wins）
                // 关键在于：写入 AtomicReference 时，Value 和 Timestamp 是同时更新的
                double tau = EcoBridge.getInstance().getConfig().getDouble("economy.tau", 7.0);
                nEff = NativeBridge.queryNeffVectorized(now, tau);
                
                NEFF_CACHE.set(new NeffSnapshot(nEff, now));
            }

            // 3. 构建 TradeContext
            MemorySegment ctx = arena.allocate(NativeBridge.Layouts.TRADE_CONTEXT);
            NativeBridge.VH_CTX_BASE_PRICE.set(ctx, 0L, p0);
            NativeBridge.VH_CTX_CURR_AMT.set(ctx, 0L, (double) Math.abs(amount));
            NativeBridge.VH_CTX_INF_RATE.set(ctx, 0L, inflation);
            NativeBridge.VH_CTX_TIMESTAMP.set(ctx, 0L, now);
            NativeBridge.VH_CTX_PLAY_TIME.set(ctx, 0L, (long) activity.seconds());
            
            // 节假日 Mask 注入逻辑
            int isNewbie = (activity.isNewbie() == 1) ? 1 : 0;
            boolean forceFestival = EcoBridge.getInstance().getConfig().getBoolean("economy.force-festival-mode", false);
            int isHoliday = (forceFestival || HolidayManager.isTodayHoliday()) ? 1 : 0;
            
            int combinedMask = (isHoliday << 1) | isNewbie;
            NativeBridge.VH_CTX_NEWBIE_MASK.set(ctx, 0L, combinedMask);

            // 4. 构建 MarketConfig
            MemorySegment cfg = prepareMarketConfig(arena, shopId, productId);

            // 5. 执行 Native 演算
            double epsilon = NativeBridge.calculateEpsilon(ctx, cfg);
            double lambda = EcoBridge.getInstance().getConfig().getDouble("economy.lambda", 0.01);
            
            return NativeBridge.computePrice(p0, nEff, lambda, epsilon);

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