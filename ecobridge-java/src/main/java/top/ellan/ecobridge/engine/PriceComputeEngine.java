package top.ellan.ecobridge.engine;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.bridge.NativeBridge.Layouts;
import top.ellan.ecobridge.bridge.NativeContextBuilder;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.manager.PricingManager;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

/**
 * 价格计算引擎 (PriceComputeEngine v1.4.8 - FFM Safety & Striped Lock Aware)
 * 职责：
 * 1. 资源安全：通过 try-with-resources 严格管理 Arena 生命周期，防止堆外内存泄漏。
 * 2. 线程安全：在结果回填与降级演算中竞争分段写锁，确保快照与实时交易的事务一致性。
 * 3. 性能优化：保持 SIMD 批处理架构，最大化 CPU 指令集效率。
 */
public class PriceComputeEngine {

    private record ItemMeta(
        String key,
        String shopId,
        String productId,
        double basePrice,
        double lambda,
        int index
    ) {}

    /**
     * 执行全量市场快照演算 (SIMD 向量化批处理版)
     */
    public static Map<String, Double> computeSnapshot(EcoBridge plugin, double configTau, double currentLambda) {
        long startTime = System.nanoTime();
        Map<String, Double> resultMap = new HashMap<>();

        if (!NativeBridge.isLoaded()) {
            return resultMap;
        }

        FileConfiguration config = plugin.getConfig();
        ConfigurationSection itemSection = config.getConfigurationSection("item-settings");
        if (itemSection == null) return resultMap;

        // 1. 扫描元数据
        List<ItemMeta> activeItems = collectActiveItems(itemSection, currentLambda);
        if (activeItems.isEmpty()) return resultMap;

        int count = activeItems.size();
        long now = System.currentTimeMillis();

        // 2. IO 聚合：预加载历史均价
        Map<String, Double> histAvgMap = loadHistoryAverages(activeItems);

        // 3. FFM 资源安全封装：使用 try-with-resources 管理 Arena
        // 即使 FFI 调用发生崩溃，堆外内存也会在此块结束时立即释放
        try (Arena arena = Arena.ofConfined()) {
            // 定义连续内存布局
            SequenceLayout tradeCtxLayout = MemoryLayout.sequenceLayout(count, Layouts.TRADE_CONTEXT);
            SequenceLayout marketCfgLayout = MemoryLayout.sequenceLayout(count, Layouts.MARKET_CONFIG);
            SequenceLayout doubleArrLayout = MemoryLayout.sequenceLayout(count, JAVA_DOUBLE);

            // 分配内存段
            MemorySegment ctxArray = arena.allocate(tradeCtxLayout);
            MemorySegment cfgArray = arena.allocate(marketCfgLayout);
            MemorySegment histAvgArray = arena.allocate(doubleArrLayout);
            MemorySegment lambdaArray = arena.allocate(doubleArrLayout);
            MemorySegment resultsArray = arena.allocate(doubleArrLayout);

            double neff = NativeBridge.queryNeffVectorized(now, configTau);

            // 4. 数据 Packing：平铺至连续 Native 内存
            for (ItemMeta meta : activeItems) {
                long ctxOffset = (long) meta.index() * Layouts.TRADE_CONTEXT.byteSize();
                long cfgOffset = (long) meta.index() * Layouts.MARKET_CONFIG.byteSize();

                MemorySegment ctxSlice = ctxArray.asSlice(ctxOffset, Layouts.TRADE_CONTEXT.byteSize());
                NativeContextBuilder.fillGlobalContext(ctxSlice, now);
                NativeBridge.VH_CTX_BASE_PRICE.set(ctxArray, ctxOffset, meta.basePrice());

                ConfigurationSection itemConfig = itemSection.getConfigurationSection(meta.shopId() + "." + meta.productId());
                fillMarketConfigAtOffset(cfgArray, cfgOffset, itemConfig, config, meta.lambda());

                double histAvg = histAvgMap.getOrDefault(meta.productId(), meta.basePrice());
                histAvgArray.setAtIndex(JAVA_DOUBLE, meta.index(), histAvg);
                lambdaArray.setAtIndex(JAVA_DOUBLE, meta.index(), meta.lambda());
            }

            // 5. 执行批处理 FFI 调用
            NativeBridge.computeBatchPrices(
                (long) count,
                neff,
                ctxArray,
                cfgArray,
                histAvgArray,
                lambdaArray,
                resultsArray
            );

            // 6. 结果 Unpacking (带事务锁保护)
            extractResultsWithLock(activeItems, resultsArray, resultMap);

        } catch (Throwable e) {
            LogUtil.error("SIMD 批量计算任务中断，启动带锁降级演算", e);
            fallbackToSingleWithLock(activeItems, resultMap, plugin, now, configTau);
        }

        double durationMs = (System.nanoTime() - startTime) / 1_000_000.0;
        LogUtil.debug("快照演算完成: " + count + " 个商品, 耗时: " + String.format("%.2f", durationMs) + "ms");
        
        return resultMap;
    }

    /**
     * 提取演算结果，并通过写锁确保不覆盖实时交易数据
     */
    private static void extractResultsWithLock(List<ItemMeta> activeItems, MemorySegment resultsArray, Map<String, Double> resultMap) {
        PricingManager pm = PricingManager.getInstance();
        for (ItemMeta meta : activeItems) {
            ReentrantReadWriteLock.WriteLock writeLock = pm.getItemLock(meta.productId()).writeLock();
            writeLock.lock();
            try {
                double computedPrice = resultsArray.getAtIndex(JAVA_DOUBLE, meta.index());
                if (Double.isFinite(computedPrice) && computedPrice > 0) {
                    resultMap.put(meta.key(), computedPrice);
                } else {
                    resultMap.put(meta.key(), meta.basePrice());
                }
            } finally {
                writeLock.unlock(); // 确保锁必然释放
            }
        }
    }

    /**
     * 降级方案：单商品原子演算 (深度整合资源安全与事务安全)
     */
    private static void fallbackToSingleWithLock(List<ItemMeta> items, Map<String, Double> map, EcoBridge plugin, long now, double tau) {
        PricingManager pm = PricingManager.getInstance();
        for (ItemMeta meta : items) {
            if (map.containsKey(meta.key())) continue;
            
            ReentrantReadWriteLock.WriteLock writeLock = pm.getItemLock(meta.productId()).writeLock();
            writeLock.lock();
            // 在锁内部使用 try-with-resources 管理 Arena
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment ctx = arena.allocate(Layouts.TRADE_CONTEXT);
                MemorySegment cfg = arena.allocate(Layouts.MARKET_CONFIG);
                NativeContextBuilder.fillGlobalContext(ctx, now);
                NativeBridge.VH_CTX_BASE_PRICE.set(ctx, 0L, meta.basePrice());
                
                double histAvg = TransactionDao.get7DayAverage(meta.productId());
                double price = NativeBridge.computePriceBounded(
                    meta.basePrice(), NativeBridge.queryNeffVectorized(now, tau), 0, meta.lambda(),
                    NativeBridge.calculateEpsilon(ctx, cfg), histAvg
                );
                map.put(meta.key(), price);
            } catch (Exception ex) {
                LogUtil.error("单体降级演算失败: " + meta.key(), ex);
                map.put(meta.key(), meta.basePrice());
            } finally {
                writeLock.unlock();
            }
        }
    }

    // --- 内部辅助方法 ---

    private static List<ItemMeta> collectActiveItems(ConfigurationSection itemSection, double macroLambda) {
        List<ItemMeta> items = new ArrayList<>();
        int index = 0;
        for (String shopId : itemSection.getKeys(false)) {
            ConfigurationSection shopSec = itemSection.getConfigurationSection(shopId);
            if (shopSec == null) continue;
            for (String prodId : shopSec.getKeys(false)) {
                double p0 = shopSec.getDouble(prodId + ".base-price", -1.0);
                if (p0 <= 0) continue;
                double lambda = shopSec.getDouble(prodId + ".lambda", macroLambda);
                items.add(new ItemMeta(shopId + ":" + prodId, shopId, prodId, p0, lambda, index++));
            }
        }
        return items;
    }

    private static Map<String, Double> loadHistoryAverages(List<ItemMeta> items) {
        List<String> ids = items.stream().map(ItemMeta::productId).distinct().toList();
        return TransactionDao.get7DayAveragesBatch(ids);
    }

    private static void fillMarketConfigAtOffset(MemorySegment cfgBase, long offset, ConfigurationSection itemSec, FileConfiguration globalConfig, double currentLambda) {
        NativeBridge.VH_CFG_LAMBDA.set(cfgBase, offset, currentLambda);
        NativeBridge.VH_CFG_VOLATILITY.set(cfgBase, offset, 1.0);
        NativeBridge.VH_CFG_S_AMP.set(cfgBase, offset, globalConfig.getDouble("economy.seasonal-amplitude", 0.15));
        NativeBridge.VH_CFG_W_MULT.set(cfgBase, offset, globalConfig.getDouble("economy.weekend-multiplier", 1.2));
        NativeBridge.VH_CFG_N_PROT.set(cfgBase, offset, globalConfig.getDouble("economy.newbie-protection", 0.2));

        if (itemSec != null) {
            NativeBridge.VH_CFG_W_SEASONAL.set(cfgBase, offset, itemSec.getDouble("weights.seasonal", 0.25));
            NativeBridge.VH_CFG_W_WEEKEND.set(cfgBase, offset, itemSec.getDouble("weights.weekend", 0.25));
            NativeBridge.VH_CFG_W_NEWBIE.set(cfgBase, offset, itemSec.getDouble("weights.newbie", 0.25));
            NativeBridge.VH_CFG_W_INFLATION.set(cfgBase, offset, itemSec.getDouble("weights.inflation", 0.25));
        } else {
            NativeBridge.VH_CFG_W_SEASONAL.set(cfgBase, offset, 0.25);
            NativeBridge.VH_CFG_W_WEEKEND.set(cfgBase, offset, 0.25);
            NativeBridge.VH_CFG_W_NEWBIE.set(cfgBase, offset, 0.25);
            NativeBridge.VH_CFG_W_INFLATION.set(cfgBase, offset, 0.25);
        }
    }
}