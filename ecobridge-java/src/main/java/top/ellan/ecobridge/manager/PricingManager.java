package top.ellan.ecobridge.manager;

import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.ObjectShop;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.api.event.PriceCalculatedEvent;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.engine.PriceComputeEngine;
import top.ellan.ecobridge.model.SaleRecord;
import top.ellan.ecobridge.network.RedisManager;
import top.ellan.ecobridge.storage.AsyncLogger;
import top.ellan.ecobridge.util.LogUtil;
import top.ellan.ecobridge.util.PriceOracle;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 核心定价管理器 (PricingManager v1.2.2 - Final Fix)
 * <p>
 * 更新日志:
 * 1. 补全 calculateBuyPrice/calculateSellPrice 以支持 UShopPriceInjector。
 * 2. 增加 onTradeComplete(String, double) 重载以支持无 Item 对象调用。
 * 3. 集成 PriceOracle 进行静态价格兜底。
 */
public class PricingManager {

    private static PricingManager instance;
    private final EcoBridge plugin;

    // [状态 1] 价格快照 (Key: "shopId:productId", Value: UnitPrice)
    private final AtomicReference<Map<String, Double>> priceSnapshot =
            new AtomicReference<>(Collections.emptyMap());

    // [状态 2] 交易历史缓存
    private final Cache<String, ThreadSafeHistory> historyCache;

    // 运行控制标志
    private volatile boolean isRunning = true;

    // 缓存的配置参数
    private double defaultLambda;
    private double configTau;
    private int historyDaysLimit;
    private int maxHistorySize;
    private double sellRatio; // 新增：全局出售折扣率

    private PricingManager(EcoBridge plugin) {
        this.plugin = plugin;
        
        this.historyCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build();

        loadConfig();
        startSnapshotEngine();
    }

    public static void init(EcoBridge plugin) {
        instance = new PricingManager(plugin);
    }

    public static PricingManager getInstance() {
        return instance;
    }

    public void loadConfig() {
        var config = plugin.getConfig();
        this.defaultLambda = config.getDouble("economy.default-lambda", 0.002);
        this.configTau = config.getDouble("economy.tau", 7.0);
        this.historyDaysLimit = config.getInt("economy.history-days-limit", 7);
        this.maxHistorySize = config.getInt("economy.max-history-records", 3000);
        this.sellRatio = config.getDouble("economy.sell-ratio", 0.5); // 默认5折回收
    }

    public void shutdown() {
        this.isRunning = false;
        this.historyCache.invalidateAll();
    }

    // =================================================================================
    //  SECTION 1: Snapshot Scheduling (协调层)
    // =================================================================================

    public double getSnapshotPrice(String shopId, String productId) {
        // 如果 shopId 为空，尝试只用 productId 查找（不推荐，但在某些上下文可能只有 productId）
        if (shopId == null) {
            // 这是一个 O(N) 的低效操作，仅作兜底
            return priceSnapshot.get().entrySet().stream()
                    .filter(e -> e.getKey().endsWith(":" + productId))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(-1.0);
        }
        return priceSnapshot.get().getOrDefault(shopId + ":" + productId, -1.0);
    }

    private void startSnapshotEngine() {
        Thread.ofVirtual().name("EcoBridge-Price-Engine").start(() -> {
            LogUtil.info("定价调度器已启动 (Mode: Delegated Compute)");

            while (isRunning && plugin.isEnabled()) {
                try {
                    Map<String, Double> nextPrices = PriceComputeEngine.computeSnapshot(
                        plugin, configTau, defaultLambda
                    );

                    if (!nextPrices.isEmpty()) {
                        priceSnapshot.set(Map.copyOf(nextPrices));
                    }

                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable e) {
                    LogUtil.warn("定价调度循环异常: " + e.getMessage());
                }
            }
        });
    }

    // =================================================================================
    //  SECTION 2: Dynamic API & Event Bridge (业务层)
    // =================================================================================

    /**
     * [新增] 计算单个商品的买入单价
     * 供 UShopPriceInjector 调用
     */
    public double calculateBuyPrice(String productId) {
        // 1. 尝试查找商品所属商店
        ObjectItem item = findObjectItem(productId);
        
        // 2. 如果找不到 Item，返回安全默认值 (100.0)
        if (item == null) return 100.0;

        // 3. 尝试从快照获取动态价格
        double dynamicPrice = getSnapshotPrice(item.getShop(), productId);
        
        // 4. 如果快照中没有 (冷启动或计算未完成)，使用 PriceOracle 获取静态原价
        if (dynamicPrice <= 0) {
            dynamicPrice = PriceOracle.getOriginalBasePrice(item, true);
        }

        return dynamicPrice;
    }

    /**
     * [新增] 计算单个商品的卖出单价
     * 供 UShopPriceInjector 调用
     */
    public double calculateSellPrice(String productId) {
        // 基础逻辑：买入价 * 折扣率
        // 进阶逻辑：未来可接入 PriceOracle 获取独立的卖出基准价
        double buyPrice = calculateBuyPrice(productId);
        return buyPrice * this.sellRatio;
    }

    public double calculateDynamicPrice(Player player, ObjectItem item, double amount) {
        String shopId = item.getShop();
        String productId = item.getProduct();
        
        double basePrice = getSnapshotPrice(shopId, productId);
        if (basePrice <= 0) {
            basePrice = PriceOracle.getOriginalBasePrice(item, amount < 0);
        }

        double calculatedPrice = PriceOracle.calculateTierPrice(basePrice, Math.abs(amount), amount > 0);

        if (Bukkit.isPrimaryThread()) {
            PriceCalculatedEvent event = new PriceCalculatedEvent(player, shopId, productId, calculatedPrice);
            Bukkit.getPluginManager().callEvent(event);
            return event.getFinalPrice();
        } else {
            CompletableFuture<Double> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    PriceCalculatedEvent event = new PriceCalculatedEvent(player, shopId, productId, calculatedPrice);
                    Bukkit.getPluginManager().callEvent(event);
                    future.complete(event.getFinalPrice());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            try {
                return future.join();
            } catch (Exception e) {
                LogUtil.error("事件同步等待失败，使用计算原价", e);
                return calculatedPrice;
            }
        }
    }

    // 辅助方法：通过 ID 反向查找 ObjectItem
    private ObjectItem findObjectItem(String productId) {
        if (ConfigManager.configManager == null) return null;
        for (ObjectShop shop : ConfigManager.configManager.getShops()) {
            ObjectItem item = shop.getProduct(productId);
            if (item != null) return item;
        }
        return null;
    }

    // =================================================================================
    //  SECTION 3: History Management (历史记录容器)
    // =================================================================================

    /**
     * 原有的基于 ObjectItem 的交易记录方法
     */
    public void onTradeComplete(ObjectItem item, double effectiveAmount) {
        onTradeComplete(item.getProduct(), effectiveAmount);
    }

    /**
     * [新增] 基于 ProductID 的重载方法 (供 UShopPriceInjector 调用)
     */
    public void onTradeComplete(String productId, double effectiveAmount) {
        long now = System.currentTimeMillis();
        processTradeInternal(productId, effectiveAmount, now, true);

        if (RedisManager.getInstance() != null) {
            RedisManager.getInstance().publishTrade(productId, effectiveAmount);
        }
    }

    public void onRemoteTradeReceived(String productId, double amount, long timestamp) {
        processTradeInternal(productId, amount, timestamp, false);
    }

    private void processTradeInternal(String productId, double amount, long timestamp, boolean writeToSql) {
        SaleRecord record = new SaleRecord(timestamp, amount);
        ThreadSafeHistory historyContainer = getHistoryContainer(productId);
        historyContainer.add(record, maxHistorySize);

        java.util.UUID productLoggerUuid = java.util.UUID.nameUUIDFromBytes(("PRODUCT_" + productId).getBytes());

        if (writeToSql) {
            plugin.getVirtualExecutor().execute(() -> {
                AsyncLogger.log(productLoggerUuid, amount, 0.0, timestamp, "LOCAL_TRADE");
                TransactionDao.saveSaleAsync(null, productId, amount);
            });
        } else {
            java.util.UUID remoteUuid = java.util.UUID.nameUUIDFromBytes(("REMOTE_" + productId).getBytes());
            AsyncLogger.log(remoteUuid, amount, 0.0, timestamp, "REMOTE_SYNC");
        }
    }

    public List<SaleRecord> getGlobalHistory(String productId) {
        return getHistoryContainer(productId).getSnapshot();
    }

    private ThreadSafeHistory getHistoryContainer(String productId) {
        return historyCache.get(productId, k -> {
            List<SaleRecord> dbData = TransactionDao.getProductHistory(k, historyDaysLimit);
            return new ThreadSafeHistory(dbData);
        });
    }

    public void clearCache() {
        historyCache.invalidateAll();
    }

    private static class ThreadSafeHistory {
        private final ArrayDeque<SaleRecord> deque;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        public ThreadSafeHistory(List<SaleRecord> initialData) {
            this.deque = new ArrayDeque<>(initialData);
        }

        public void add(SaleRecord record, int maxSize) {
            lock.writeLock().lock();
            try {
                deque.addFirst(record);
                if (deque.size() > maxSize) {
                    deque.removeLast();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        public List<SaleRecord> getSnapshot() {
            lock.readLock().lock();
            try {
                return new ArrayList<>(deque);
            } finally {
                lock.readLock().unlock();
            }
        }
    }
}