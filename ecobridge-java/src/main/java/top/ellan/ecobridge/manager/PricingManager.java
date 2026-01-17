package top.ellan.ecobridge.manager;

import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.ObjectShop;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
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
 * 核心定价管理器 (PricingManager v1.4.2 - Hardened Production)
 */
public class PricingManager {

    private static PricingManager instance;
    private final EcoBridge plugin;

    // [状态] 堆外内存管理 (FFM Shared Arena)
    private final Arena managerArena = Arena.ofShared();
    private final MemorySegment globalPidState;

    // [状态] 价格快照 (原子引用提供无锁读取性能)
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
        
        // 1. 初始化分段锁
        this.itemLocks = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();

        // 2. 初始化历史缓存
        this.historyCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .removalListener((String key, ThreadSafeHistory value, RemovalCause cause) -> {
                    if (cause.wasEvicted()) {
                        LogUtil.debug("缓存清理: 商品 " + key + " 已从内存安全释放 (" + cause.name() + ")");
                    }
                })
                .build();

        // 3. 分配堆外内存
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
        try {
            this.historyCache.invalidateAll();
            this.itemLocks.invalidateAll();
            LogUtil.info("PricingManager 正在安全释放 FFM 资源...");
        } finally {
            if (managerArena.scope().isAlive()) {
                managerArena.close();
            }
        }
    }

    // ==================== SECTION 1: 宏观调控引擎 ====================

    private void startSnapshotEngine() {
        Thread.ofVirtual().name("EcoBridge-Macro-Engine").start(() -> {
            while (isRunning && plugin.isEnabled()) {
                try {
                    long now = System.currentTimeMillis();
                    double dt = (now - lastComputeTime) / 1000.0;
                    lastComputeTime = now;

                    // ✅ 修复：在异步线程安全地获取在线人数
                    int onlineCount = CompletableFuture.supplyAsync(
                            () -> Bukkit.getOnlinePlayers().size(),
                            runnable -> Bukkit.getScheduler().runTask(plugin, runnable)
                    ).get(100, TimeUnit.MILLISECONDS);

                    long currentTrades = globalTradeCounter.getAndSet(0);
                    double currentHeat = currentTrades / Math.max(dt, 0.1);
                    double targetHeat = Math.max(0.1, onlineCount * targetTradesPerUser);

                    double inflation = EconomyManager.getInstance().getInflationRate();

                    double macroAdjustment = NativeBridge.computePidAdjustment(
                            globalPidState, targetHeat, currentHeat, dt, inflation, currentHeat
                    );

                    // ✅ 恢复：对齐你的 PriceComputeEngine 原始签名 (3 参数)
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
                    LogUtil.warn("宏观引擎演算异常: " + e.getMessage());
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

            // ✅ 线程安全修复：通过 Bukkit 调度器将事件抛回主线程执行
            return CompletableFuture.runAsync(() -> Bukkit.getPluginManager().callEvent(event), 
                    runnable -> Bukkit.getScheduler().runTask(plugin, runnable))
                    .thenApply(v -> event.getFinalPrice())
                    .orTimeout(200, TimeUnit.MILLISECONDS) 
                    .join();
        } catch (Exception e) {
            return PriceOracle.getOriginalBasePrice(item, amount < 0);
        } finally {
            lock.unlock();
        }
    }

    // ==================== SECTION 3: 交易处理 ====================

    public void onTradeComplete(ObjectItem item, double effectiveAmount) {
        onTradeComplete(item.getProduct(), effectiveAmount);
    }

    public void onTradeComplete(String productId, double effectiveAmount) {
        var lock = getItemLock(productId).writeLock();
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            globalTradeCounter.incrementAndGet();

            SaleRecord record = new SaleRecord(now, effectiveAmount);
            getHistoryContainer(productId).add(record, maxHistorySize);

            plugin.getVirtualExecutor().execute(() -> {
                try {
                    AsyncLogger.log(java.util.UUID.nameUUIDFromBytes(productId.getBytes()), effectiveAmount, 0, now, "TRX_WRITE_THROUGH");
                    TransactionDao.saveSaleAsync(null, productId, effectiveAmount);
                } catch (Exception e) {
                    LogUtil.error("写穿透 SQL 任务失败: " + productId, e);
                }
            });

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
            SaleRecord record = new SaleRecord(timestamp, amount);
            getHistoryContainer(productId).add(record, maxHistorySize);
        } finally {
            lock.unlock();
        }
    }

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
        Map<String, Double> current = priceSnapshot.get();
        if (shopId == null) {
            return current.entrySet().stream()
                    .filter(e -> e.getKey().endsWith(":" + productId))
                    .map(Map.Entry::getValue).findFirst().orElse(-1.0);
        }
        return current.getOrDefault(shopId + ":" + productId, -1.0);
    }

    /**
     * ✅ 修复：解决缓存加载时的阻塞问题
     */
    private ThreadSafeHistory getHistoryContainer(String productId) {
        ThreadSafeHistory container = historyCache.getIfPresent(productId);
        if (container == null) {
            container = new ThreadSafeHistory(new ArrayList<>());
            historyCache.put(productId, container);
            
            final ThreadSafeHistory finalContainer = container;
            plugin.getVirtualExecutor().execute(() -> {
                List<SaleRecord> initialData = TransactionDao.getProductHistory(productId, historyDaysLimit);
                finalContainer.loadBatch(initialData);
            });
        }
        return container;
    }

    public void clearCache() {
        historyCache.invalidateAll();
        itemLocks.invalidateAll();
    }

    // --- 内部类：保持原有结构 ---

    private static class ThreadSafeHistory {
        private final ArrayDeque<SaleRecord> deque;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        public ThreadSafeHistory(List<SaleRecord> initialData) {
            this.deque = new ArrayDeque<>(initialData);
        }

        public void loadBatch(List<SaleRecord> records) {
            lock.writeLock().lock();
            try {
                deque.clear();
                deque.addAll(records);
            } finally {
                lock.writeLock().unlock();
            }
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