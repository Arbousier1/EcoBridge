// ==================================================
// FILE: ecobridge-java/src/main/java/top/ellan/ecobridge/engine/PriceComputeEngine.java
// ==================================================

package top.ellan.ecobridge.engine;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.bridge.NativeBridge.Layouts;
import top.ellan.ecobridge.bridge.NativeContextBuilder;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

/**
 * 价格计算引擎 (PriceComputeEngine v1.1 - Native Math Integration)
 * <p>
 * 职责：
 * 1. 负责 Native 内存 (Arena) 的生命周期管理。
 * 2. 执行全量商品的定价计算循环。
 * 3. [Update] 适配全数学下沉架构，调用 Rust 侧的 computePriceBounded。
 */
public class PriceComputeEngine {

    /**
     * 执行一次全量市场计算
     *
     * @param plugin        插件实例 (用于获取最新配置)
     * @param configTau     时间衰减常数
     * @param defaultLambda 默认流动性系数
     * @return 计算完成的价格快照 Map
     */
    public static Map<String, Double> computeSnapshot(EcoBridge plugin, double configTau, double defaultLambda) {
        Map<String, Double> result = new HashMap<>();

        // 1. 快速失败检查
        if (!NativeBridge.isLoaded()) {
            return result;
        }

        FileConfiguration globalConfig = plugin.getConfig();
        ConfigurationSection itemSection = globalConfig.getConfigurationSection("item-settings");
        if (itemSection == null) return result;

        long now = System.currentTimeMillis();

        // 2. 核心计算循环
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctx = arena.allocate(Layouts.TRADE_CONTEXT);
            MemorySegment cfg = arena.allocate(Layouts.MARKET_CONFIG);

            // 3. 填充全局环境上下文
            NativeContextBuilder.fillGlobalContext(ctx, now);

            // 4. 预计算 Neff (向量化)
            double neff = NativeBridge.queryNeffVectorized(now, configTau);

            // 5. 遍历所有商品
            for (String shopId : itemSection.getKeys(false)) {
                ConfigurationSection shopSection = itemSection.getConfigurationSection(shopId);
                if (shopSection == null) continue;

                for (String productId : shopSection.getKeys(false)) {
                    processSingleItem(
                        shopId, productId, shopSection,
                        ctx, cfg,
                        neff, defaultLambda, globalConfig,
                        result
                    );
                }
            }
        } catch (Throwable e) {
            LogUtil.error("计算引擎发生严重错误", e);
        }

        return result;
    }

    /**
     * 处理单个商品的定价逻辑
     */
    private static void processSingleItem(
            String shopId, String productId, ConfigurationSection shopSection,
            MemorySegment ctx, MemorySegment cfg,
            double neff, double defaultLambda, FileConfiguration globalConfig,
            Map<String, Double> resultCollector
    ) {
        String path = productId; 
        String itemKey = shopId + ":" + productId;
        
        // 读取商品独立配置
        double lambda = shopSection.getDouble(path + ".lambda", defaultLambda);
        double p0 = shopSection.getDouble(path + ".base-price", -1.0);

        if (p0 <= 0) return;

        // A. 更新 Native 上下文
        NativeContextBuilder.updateItemContext(ctx, p0);
        
        // B. 填充市场配置
        ConfigurationSection itemConfig = shopSection.getConfigurationSection(path);
        fillMarketConfig(cfg, itemConfig, globalConfig, defaultLambda);

        // C. 执行 Native 计算
        double epsilon = NativeBridge.calculateEpsilon(ctx, cfg);

        // [Fix] 获取历史均价 (用于底价保护)
        double histAvg = TransactionDao.get7DayAverage(productId);

        // [Fix] 调用新的 computePriceBounded (替代旧的 computePrice)
        // 参数: base, neff, amount(0), lambda, epsilon, histAvg
        // 说明: 这里 amount 传 0.0 是因为这是"基准价预测"，不涉及实际交易增量
        double finalPrice = NativeBridge.computePriceBounded(p0, neff, 0.0, lambda, epsilon, histAvg);

        // D. 存入结果 (Java 侧不再需要 Math.max(floor, price) 逻辑，Rust 已处理)
        resultCollector.put(itemKey, finalPrice);
    }

    /**
     * 填充 MarketConfig 内存段
     */
    private static void fillMarketConfig(
            MemorySegment cfg, ConfigurationSection section, 
            FileConfiguration globalConfig, double defaultLambda
    ) {
        double lambda = (section != null) ? section.getDouble("lambda", defaultLambda) : defaultLambda;
        NativeBridge.VH_CFG_LAMBDA.set(cfg, 0L, lambda);
        NativeBridge.VH_CFG_VOLATILITY.set(cfg, 0L, 1.0);

        NativeBridge.VH_CFG_S_AMP.set(cfg, 0L, globalConfig.getDouble("economy.seasonal-amplitude", 0.15));
        NativeBridge.VH_CFG_W_MULT.set(cfg, 0L, globalConfig.getDouble("economy.weekend-multiplier", 1.2));
        NativeBridge.VH_CFG_N_PROT.set(cfg, 0L, globalConfig.getDouble("economy.newbie-protection-rate", 0.2));

        if (section != null) {
            NativeBridge.VH_CFG_W_SEASONAL.set(cfg, 0L, section.getDouble("weights.seasonal", 0.25));
            NativeBridge.VH_CFG_W_WEEKEND.set(cfg, 0L, section.getDouble("weights.weekend", 0.25));
            NativeBridge.VH_CFG_W_NEWBIE.set(cfg, 0L, section.getDouble("weights.newbie", 0.25));
            NativeBridge.VH_CFG_W_INFLATION.set(cfg, 0L, section.getDouble("weights.inflation", 0.25));
        } else {
            NativeBridge.VH_CFG_W_SEASONAL.set(cfg, 0L, 0.25);
            NativeBridge.VH_CFG_W_WEEKEND.set(cfg, 0L, 0.25);
            NativeBridge.VH_CFG_W_NEWBIE.set(cfg, 0L, 0.25);
            NativeBridge.VH_CFG_W_INFLATION.set(cfg, 0L, 0.25);
        }
    }
}