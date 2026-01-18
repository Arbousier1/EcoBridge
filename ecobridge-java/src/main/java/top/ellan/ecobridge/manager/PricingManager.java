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
 * 核心定價管理器 (PricingManager v1.4.7 - Hardened Production)
 * 修復點：
 * 1. 完善了 NativeBridge 狀態檢測與異常處理，防止刷屏。
 * 2. 修正了演算循環邏輯，確保異常發生時仍會休眠。
 * 3. 添加了 API 方法的壓制警告。
 */
public class PricingManager {

    private static PricingManager instance;
    private final EcoBridge plugin;

    // [狀態] 堆外內存管理 (FFM Shared Arena)
    private final Arena managerArena = Arena.ofShared();
    private final MemorySegment globalPidState;

    // [狀態] 價格快照 (原子引用提供無鎖讀取性能)
    private final AtomicReference<Map<String, Double>> priceSnapshot =
            new AtomicReference<>(Collections.emptyMap());

    // [核心安全] 分段鎖管理器
    private final Cache<String, ReentrantReadWriteLock> itemLocks;

    // [狀態] 流速監控
    private final AtomicLong globalTradeCounter = new AtomicLong(0);
    private long lastComputeTime = System.currentTimeMillis();

    // [狀態] 交易歷史緩存
    private final Cache<String, ThreadSafeHistory> historyCache;

    private volatile boolean isRunning = true;

    // 配置參數
    private double defaultLambda;
    private double configTau;
    private double sellRatio;
    private int historyDaysLimit;
    private int maxHistorySize;
    private double targetTradesPerUser;

    private PricingManager(EcoBridge plugin) {
        this.plugin = plugin;
        
        // 1. 初始化分段鎖
        this.itemLocks = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();

        // 2. 初始化歷史緩存
        this.historyCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .removalListener((String key, ThreadSafeHistory value, RemovalCause cause) -> {
                    if (cause.wasEvicted()) {
                        LogUtil.debug("緩存清理: 商品 " + key + " 已從內存安全釋放 (" + cause.name() + ")");
                    }
                })
                .build();

        // 3. 分配堆外內存
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
            LogUtil.info("PricingManager 正在安全釋放 FFM 資源...");
        } finally {
            if (managerArena.scope().isAlive()) {
                managerArena.close();
            }
        }
    }

    // ==================== SECTION 1: 宏觀調控引擎 ====================

    private void startSnapshotEngine() {
        Thread.ofVirtual().name("EcoBridge-Macro-Engine").start(() -> {
            while (isRunning && plugin.isEnabled()) {
                long cycleStartTime = System.currentTimeMillis();
                try {
                    // ✅ 核心修復：如果 Native 沒加載成功，每 10 秒檢查一次
                    if (!NativeBridge.isLoaded()) {
                        Thread.sleep(10000);
                        continue;
                    }

                    long now = System.currentTimeMillis();
                    double dt = (now - lastComputeTime) / 1000.0;
                    lastComputeTime = now;

                    // 安全地獲取在線人數 (增加超時容錯)
                    int onlineCount = 0;
                    try {
                        onlineCount = CompletableFuture.supplyAsync(
                                () -> Bukkit.getOnlinePlayers().size(),
                                runnable -> Bukkit.getScheduler().runTask(plugin, runnable)
                        ).get(500, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        onlineCount = Bukkit.getOnlinePlayers().size(); 
                    }

                    // 依賴項預檢
                    EconomyManager eco = EconomyManager.getInstance();
                    if (eco == null) continue;
                    double inflation = eco.getInflationRate();

                    long currentTrades = globalTradeCounter.getAndSet(0);
                    double currentHeat = currentTrades / Math.max(dt, 0.1);
                    double targetHeat = Math.max(0.1, onlineCount * targetTradesPerUser);

                    // 調用 Native 進行 PID 演算
                    double macroAdjustment = NativeBridge.computePidAdjustment(
                            globalPidState, targetHeat, currentHeat, dt, inflation, currentHeat
                    );

                    // 執行批量價格演算
                    Map<String, Double> nextPrices = PriceComputeEngine.computeSnapshot(
                            plugin, configTau, defaultLambda * macroAdjustment
                    );

                    if (nextPrices != null && !nextPrices.isEmpty()) {
                        priceSnapshot.set(Map.copyOf(nextPrices));
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable e) {
                    // ✅ 核心修復：打印堆棧，避免 null message 導致無法排查問題
                    LogUtil.error("宏觀引擎演算邏輯中斷", e);
                } finally {
                    // ✅ 核心修復：無論成功與否，強制進入休眠防止死循環刷屏
                    try {
                        long elapsed = System.currentTimeMillis() - cycleStartTime;
                        Thread.sleep(Math.max(500, 2000 - elapsed));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
    }

    // ==================== SECTION 2: 業務 API ====================

    public double calculateBuyPrice(String productId) {
        ObjectItem item = findObjectItem(productId);
        if (item == null) return 100.0;
        double dynamicPrice = getSnapshotPrice(item.getShop(), productId);
        return (dynamicPrice <= 0) ? PriceOracle.getOriginalBasePrice(item, true) : dynamicPrice;
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
                    .orTimeout(300, TimeUnit.MILLISECONDS) 
                    .join();
        } catch (Exception e) {
            return PriceOracle.getOriginalBasePrice(item, amount < 0);
        } finally {
            lock.unlock();
        }
    }

    // ==================== SECTION 3: 交易處理 ====================

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
                    LogUtil.error("寫穿透 SQL 任務失敗: " + productId, e);
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

    @SuppressWarnings("unused") // API 暴露給外部插件
    public List<SaleRecord> getGlobalHistory(String productId) {
        return getHistoryContainer(productId).getSnapshot();
    }

    // --- 內部工具 ---

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

    @SuppressWarnings("unused") // API
    public void clearCache() {
        historyCache.invalidateAll();
        itemLocks.invalidateAll();
    }

    // --- 內部分類 ---

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

        @SuppressWarnings("unused") // 解決 IDE 關於 getSnapshot 未使用的警告
        public List<SaleRecord> getSnapshot() {
            lock.readLock().lock();
            try {
                return new ArrayList<>(deque);
            } finally {
                lock.readLock().unlock();
            }
        }
    }

    public MemorySegment getGlobalPidState() {
        return globalPidState;
    }
}