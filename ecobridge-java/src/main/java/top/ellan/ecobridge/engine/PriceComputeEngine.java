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
 * 价格计算引擎 (PriceComputeEngine v1.4.7 - Striped Lock Aware)
 * * 更新日志：
 * 1. 结果回填锁感知：在提取演算结果时，竞争商品的 writeLock，确保数据原子性。
 * 2. 保持 SIMD 批处理架构，利用 Java 25 Arena 管理堆外内存。
 * 3. 增强降级演算的事务安全性。
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

        // 1. 扫描并收集元数据
        List<ItemMeta> activeItems = collectActiveItems(itemSection, currentLambda);
        if (activeItems.isEmpty()) return resultMap;

        int count = activeItems.size();
        long now = System.currentTimeMillis();

        // 2. 批量获取 7 日均价 (数据库 IO 聚合)
        Map<String, Double> histAvgMap = loadHistoryAverages(activeItems);

        // 3. 线性堆外内存编排 (FFM Arena)
        try (Arena arena = Arena.ofConfined()) {
            SequenceLayout tradeCtxLayout = MemoryLayout.sequenceLayout(count, Layouts.TRADE_CONTEXT);
            SequenceLayout marketCfgLayout = MemoryLayout.sequenceLayout(count, Layouts.MARKET_CONFIG);
            SequenceLayout doubleArrLayout = MemoryLayout.sequenceLayout(count, JAVA_DOUBLE);

            MemorySegment ctxArray = arena.allocate(tradeCtxLayout);
            MemorySegment cfgArray = arena.allocate(marketCfgLayout);
            MemorySegment histAvgArray = arena.allocate(doubleArrLayout);
            MemorySegment lambdaArray = arena.allocate(doubleArrLayout);
            MemorySegment resultsArray = arena.allocate(doubleArrLayout);

            double neff = NativeBridge.queryNeffVectorized(now, configTau);

            // 4. 数据 Packing (平铺至连续内存)
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

            // 5. 单次批量 FFI 调用 (驱动 Rust 并行计算)
            NativeBridge.computeBatchPrices(
                (long) count,
                neff,
                ctxArray,
                cfgArray,
                histAvgArray,
                lambdaArray,
                resultsArray
            );

            // 6. [关键修复] 结果回填 (带分段锁保护)
            // 确保写入 resultMap 的瞬间，该商品没有正在进行的实时交易写入
            extractResultsWithLock(activeItems, resultsArray, resultMap);

        } catch (Throwable e) {
            LogUtil.error("SIMD 批量计算失败，触发紧急降级演算", e);
            fallbackToSingleWithLock(activeItems, resultMap, plugin, now, configTau);
        }

        double durationMs = (System.nanoTime() - startTime) / 1_000_000.0;
        LogUtil.debug("快照批量演算耗时: " + String.format("%.2f", durationMs) + "ms (Items: " + count + ")");
        
        return resultMap;
    }

    /**
     * 提取演算结果，并利用分段锁确保事务一致性
     */
    private static void extractResultsWithLock(List<ItemMeta> activeItems, MemorySegment resultsArray, Map<String, Double> resultMap) {
        PricingManager pm = PricingManager.getInstance();
        for (ItemMeta meta : activeItems) {
            // 获取该商品的写锁
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
                writeLock.unlock();
            }
        }
    }

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

    private static void fillMarketConfigAtOffset(
            MemorySegment cfgBase, long offset, 
            ConfigurationSection itemSec, FileConfiguration globalConfig, double currentLambda
    ) {
        NativeBridge.VH_CFG_LAMBDA.set(cfgBase, offset, currentLambda);
        NativeBridge.VH_CFG_VOLATILITY.set(cfgBase, offset, 1.0);
        NativeBridge.VH_CFG_S_AMP.set(cfgBase, offset, globalConfig.getDouble("economy.seasonal-amplitude", 0.15));
        NativeBridge.VH_CFG_W_MULT.set(cfgBase, offset, globalConfig.getDouble("economy.weekend-multiplier", 1.2));
        NativeBridge.VH_CFG_N_PROT.set(cfgBase, offset, globalConfig.getDouble("economy.newbie-protection-rate", 0.2));

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

    /**
     * 降级演算：单商品原子计算 (同样带写锁保护)
     */
    private static void fallbackToSingleWithLock(List<ItemMeta> items, Map<String, Double> map, EcoBridge plugin, long now, double tau) {
        PricingManager pm = PricingManager.getInstance();
        for (ItemMeta meta : items) {
            if (map.containsKey(meta.key())) continue;
            
            ReentrantReadWriteLock.WriteLock writeLock = pm.getItemLock(meta.productId()).writeLock();
            writeLock.lock();
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
                map.put(meta.key(), meta.basePrice());
            } finally {
                writeLock.unlock();
            }
        }
    }
}