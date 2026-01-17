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
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.engine.PriceComputeEngine;
import top.ellan.ecobridge.model.SaleRecord;
import top.ellan.ecobridge.network.RedisManager;
import top.ellan.ecobridge.storage.AsyncLogger;
import top.ellan.ecobridge.util.LogUtil;
import top.ellan.ecobridge.util.PriceOracle;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 核心定价管理器 (PricingManager v1.4.1 - Striped Lock & Diagnostic Fix)
 * * 职责：
 * 1. 宏观调控：基于全服财富流速 (Heat) 执行自适应 PID 调节。
 * 2. 并发安全：利用分段读写锁消除异步计算与主线程交易间的竞态条件。
 * 3. 历史审计：提供线程安全的历史记录查询接口。
 */
public class PricingManager {

    private static PricingManager instance;
    private final EcoBridge plugin;

    // [状态] 堆外内存管理
    private final Arena managerArena = Arena.ofShared();
    private final MemorySegment globalPidState;

    // [状态] 价格快照
    private final AtomicReference<Map<String, Double>> priceSnapshot =
            new AtomicReference<>(Collections.emptyMap());

    // [核心安全] 分段锁管理器
    private final Cache<String, ReentrantReadWriteLock> itemLocks;

    // [状态] 流速监控
    private final AtomicLong globalTradeCounter = new AtomicLong(0);
    private long lastComputeTime = System.currentTimeMillis();

    // [状态] 交易历史缓存
    private final Cache<String, ThreadSafeHistory> historyCache;

    private volatile boolean isRunning = true;

    // 配置参数
    private double defaultLambda;
    private double configTau;
    private double sellRatio;
    private int historyDaysLimit;
    private int maxHistorySize;
    private double targetTradesPerUser;

    private PricingManager(EcoBridge plugin) {
        this.plugin = plugin;
        
        this.itemLocks = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();

        this.historyCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build();

        this.globalPidState = managerArena.allocate(NativeBridge.Layouts.PID_STATE);
        NativeBridge.resetPidState(globalPidState);

        loadConfig();
        startSnapshotEngine();
    }

    public static void init(EcoBridge plugin) {
        instance = new PricingManager(plugin);
    }

    public static PricingManager getInstance() {
        return instance;
    }

    public ReentrantReadWriteLock getItemLock(String productId) {
        return itemLocks.get(productId, k -> new ReentrantReadWriteLock());
    }

    public void loadConfig() {
        var config = plugin.getConfig();
        this.defaultLambda = config.getDouble("economy.default-lambda", 0.002);
        this.configTau = config.getDouble("economy.tau", 7.0);
        this.sellRatio = config.getDouble("economy.sell-ratio", 0.5);
        this.historyDaysLimit = config.getInt("economy.history-days-limit", 7);
        this.maxHistorySize = config.getInt("economy.max-history-records", 3000);
        this.targetTradesPerUser = config.getDouble("economy.macro.target-velocity", 0.05);
    }

    public void shutdown() {
        this.isRunning = false;
        this.historyCache.invalidateAll();
        this.itemLocks.invalidateAll();
        managerArena.close();
    }

    // ==================== SECTION 1: 宏观调控引擎 ====================

    private void startSnapshotEngine() {
        Thread.ofVirtual().name("EcoBridge-Macro-Engine").start(() -> {
            while (isRunning && plugin.isEnabled()) {
                try {
                    long now = System.currentTimeMillis();
                    double dt = (now - lastComputeTime) / 1000.0;
                    lastComputeTime = now;

                    int onlineCount = Bukkit.getOnlinePlayers().size();
                    long currentTrades = globalTradeCounter.getAndSet(0);
                    double currentHeat = currentTrades / Math.max(dt, 0.1);
                    double targetHeat = Math.max(0.1, onlineCount * targetTradesPerUser);

                    double inflation = EconomyManager.getInstance().getInflationRate();

                    double macroAdjustment = NativeBridge.computePidAdjustment(
                            globalPidState, targetHeat, currentHeat, dt, inflation, currentHeat
                    );

                    Map<String, Double> nextPrices = PriceComputeEngine.computeSnapshot(
                            plugin, configTau, defaultLambda * macroAdjustment
                    );

                    if (!nextPrices.isEmpty()) {
                        priceSnapshot.set(Map.copyOf(nextPrices));
                    }

                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable e) {
                    LogUtil.warn("宏观调控引擎异常: " + e.getMessage());
                }
            }
        });
    }

    // ==================== SECTION 2: 业务 API ====================

    public double calculateBuyPrice(String productId) {
        ObjectItem item = findObjectItem(productId);
        if (item == null) return 100.0;
        double dynamicPrice = getSnapshotPrice(item.getShop(), productId);
        if (dynamicPrice <= 0) {
            dynamicPrice = PriceOracle.getOriginalBasePrice(item, true);
        }
        return dynamicPrice;
    }

    public double calculateSellPrice(String productId) {
        return calculateBuyPrice(productId) * this.sellRatio;
    }

    public double calculateDynamicPrice(Player player, ObjectItem item, double amount) {
        String shopId = item.getShop();
        String productId = item.getProduct();
        
        var lock = getItemLock(productId).readLock();
        lock.lock();
        try {
            double basePrice = getSnapshotPrice(shopId, productId);
            if (basePrice <= 0) {
                basePrice = PriceOracle.getOriginalBasePrice(item, amount < 0);
            }
            double calculatedPrice = NativeBridge.computeTierPrice(basePrice, Math.abs(amount), amount > 0);
            PriceCalculatedEvent event = new PriceCalculatedEvent(player, shopId, productId, calculatedPrice);

            if (Bukkit.isPrimaryThread()) {
                Bukkit.getPluginManager().callEvent(event);
                return event.getFinalPrice();
            }

            return CompletableFuture.runAsync(() -> Bukkit.getPluginManager().callEvent(event), 
                    runnable -> Bukkit.getScheduler().runTask(plugin, runnable))
                    .thenApply(v -> event.getFinalPrice())
                    .orTimeout(500, TimeUnit.MILLISECONDS) 
                    .join();
        } catch (Exception e) {
            return PriceOracle.getOriginalBasePrice(item, amount < 0);
        } finally {
            lock.unlock();
        }
    }

    // ==================== SECTION 3: 交易处理 (事务保护) ====================

    public void onTradeComplete(ObjectItem item, double effectiveAmount) {
        onTradeComplete(item.getProduct(), effectiveAmount);
    }

    public void onTradeComplete(String productId, double effectiveAmount) {
        var lock = getItemLock(productId).writeLock();
        lock.lock();
        try {
            globalTradeCounter.incrementAndGet();
            processTradeInternal(productId, effectiveAmount, System.currentTimeMillis(), true);
            if (RedisManager.getInstance() != null) {
                RedisManager.getInstance().publishTrade(productId, effectiveAmount);
            }
        } finally {
            lock.unlock();
        }
    }

    public void onRemoteTradeReceived(String productId, double amount, long timestamp) {
        var lock = getItemLock(productId).writeLock();
        lock.lock();
        try {
            processTradeInternal(productId, amount, timestamp, false);
        } finally {
            lock.unlock();
        }
    }

    private void processTradeInternal(String productId, double amount, long timestamp, boolean writeToSql) {
        SaleRecord record = new SaleRecord(timestamp, amount);
        getHistoryContainer(productId).add(record, maxHistorySize);

        if (writeToSql) {
            java.util.UUID productLoggerUuid = java.util.UUID.nameUUIDFromBytes(("PRODUCT_" + productId).getBytes());
            plugin.getVirtualExecutor().execute(() -> {
                AsyncLogger.log(productLoggerUuid, amount, 0.0, timestamp, "LOCAL_TRADE");
                TransactionDao.saveSaleAsync(null, productId, amount);
            });
        }
    }

    /**
     * [修复] 获取全局历史记录接口
     * 正确调用 ThreadSafeHistory.getSnapshot() 以消除本地未使用警告
     */
    public List<SaleRecord> getGlobalHistory(String productId) {
        return getHistoryContainer(productId).getSnapshot();
    }

    // --- 内部工具 ---

    private ObjectItem findObjectItem(String productId) {
        if (ConfigManager.configManager == null) return null;
        for (ObjectShop shop : ConfigManager.configManager.getShops()) {
            ObjectItem item = shop.getProduct(productId);
            if (item != null) return item;
        }
        return null;
    }

    public double getSnapshotPrice(String shopId, String productId) {
        if (shopId == null) {
            return priceSnapshot.get().entrySet().stream()
                    .filter(e -> e.getKey().endsWith(":" + productId))
                    .map(Map.Entry::getValue).findFirst().orElse(-1.0);
        }
        return priceSnapshot.get().getOrDefault(shopId + ":" + productId, -1.0);
    }

    private ThreadSafeHistory getHistoryContainer(String productId) {
        return historyCache.get(productId, k -> new ThreadSafeHistory(TransactionDao.getProductHistory(k, historyDaysLimit)));
    }

    public void clearCache() {
        historyCache.invalidateAll();
        itemLocks.invalidateAll();
    }

    // ==================== 内部类：并发安全历史容器 ====================

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
                if (deque.size() > maxSize) deque.removeLast();
            } finally {
                lock.writeLock().unlock();
            }
        }

        /**
         * 提取历史记录快照
         * [Fix] 现在由 PricingManager.getGlobalHistory 正确调用
         */
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