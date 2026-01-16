// ==================================================
// FILE: ecobridge-java/src/main/java/top/ellan/ecobridge/manager/PricingManager.java
// ==================================================

package top.ellan.ecobridge.manager;

import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.api.event.PriceCalculatedEvent;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.engine.PriceComputeEngine; // [核心依赖] 计算引擎
import top.ellan.ecobridge.model.SaleRecord;
import top.ellan.ecobridge.network.RedisManager;
import top.ellan.ecobridge.storage.AsyncLogger;
import top.ellan.ecobridge.util.LogUtil;
import top.ellan.ecobridge.util.PriceOracle; // [核心依赖] 数学与校验预言机

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 核心定价管理器 (PricingManager v1.2.0 - Coordinator Pattern)
 * <p>
 * 职责边界:
 * 1. [State] 持有价格快照 (Snapshot) 和交易历史 (History)。
 * 2. [Scheduler] 调度后台计算任务，但本身不执行计算。
 * 3. [API] 提供对外查询接口，并处理事件分发。
 * <p>
 * 已剥离逻辑:
 * - Native 计算 -> {@link top.ellan.ecobridge.engine.PriceComputeEngine}
 * - 阶梯定价/物品校验 -> {@link top.ellan.ecobridge.util.PriceOracle}
 */
public class PricingManager {

    private static PricingManager instance;
    private final EcoBridge plugin;

    // [状态 1] 价格快照 (Key: "shopId:productId", Value: UnitPrice)
    // 使用 AtomicReference 实现无锁读写替换
    private final AtomicReference<Map<String, Double>> priceSnapshot =
            new AtomicReference<>(Collections.emptyMap());

    // [状态 2] 交易历史缓存 (Caffeine 管理生命周期)
    private final Cache<String, ThreadSafeHistory> historyCache;

    // 运行控制标志
    private volatile boolean isRunning = true;

    // 缓存的配置参数 (从 Config 读取)
    private double defaultLambda;
    private double configTau;
    private int historyDaysLimit;
    private int maxHistorySize;

    private PricingManager(EcoBridge plugin) {
        this.plugin = plugin;
        
        // 初始化缓存：30分钟无访问自动过期，防止内存泄漏
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
    }

    public void shutdown() {
        this.isRunning = false;
        this.historyCache.invalidateAll();
    }

    // =================================================================================
    //  SECTION 1: Snapshot Scheduling (协调层)
    // =================================================================================

    /**
     * [API] O(1) 读取当前快照价格
     */
    public double getSnapshotPrice(String shopId, String productId) {
        return priceSnapshot.get().getOrDefault(shopId + ":" + productId, -1.0);
    }

    /**
     * 启动后台定价引擎调度器
     * 注意：这里只负责调度，具体的计算逻辑已委托给 PriceComputeEngine
     */
    private void startSnapshotEngine() {
        Thread.ofVirtual().name("EcoBridge-Price-Engine").start(() -> {
            LogUtil.info("定价调度器已启动 (Mode: Delegated Compute)");

            while (isRunning && plugin.isEnabled()) {
                try {
                    // [Delegation] 调用纯计算引擎获取新价格表
                    // 这是一个耗时操作 (包含 Native 调用)，但在虚拟线程中运行不会阻塞主线程
                    Map<String, Double> nextPrices = PriceComputeEngine.computeSnapshot(
                        plugin, configTau, defaultLambda
                    );

                    // [State Update] 原子替换快照
                    if (!nextPrices.isEmpty()) {
                        priceSnapshot.set(Map.copyOf(nextPrices));
                    }

                    // 计算间隔 (2秒)
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
     * 计算最终动态价格 (含阶梯定价 + 事件触发)
     */
    public double calculateDynamicPrice(Player player, ObjectItem item, double amount) {
        String shopId = item.getShop();
        String productId = item.getProduct();
        
        // 1. 获取基准单价 (Snapshot -> Fallback)
        double basePrice = getSnapshotPrice(shopId, productId);
        if (basePrice <= 0) {
            basePrice = PriceOracle.getOriginalBasePrice(item, amount < 0);
        }

        // 2. [Delegation] 应用阶梯定价 (Tier Pricing)
        // 逻辑已移至 PriceOracle，此处仅调用
        double calculatedPrice = PriceOracle.calculateTierPrice(basePrice, Math.abs(amount), amount > 0);

        // 3. 事件分发 (线程安全桥接)
        if (Bukkit.isPrimaryThread()) {
            // 场景 A: 主线程直接调用 (高效)
            PriceCalculatedEvent event = new PriceCalculatedEvent(player, shopId, productId, calculatedPrice);
            Bukkit.getPluginManager().callEvent(event);
            return event.getFinalPrice();
        } else {
            // 场景 B: 异步线程调用 (安全等待)
            // 使用 CompletableFuture 桥接到主线程触发事件，并等待结果返回
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
                // 在虚拟线程中 join() 是低开销的挂起
                return future.join();
            } catch (Exception e) {
                // 异常兜底：返回计算价，不打断交易
                LogUtil.error("事件同步等待失败，使用计算原价", e);
                return calculatedPrice;
            }
        }
    }

    // =================================================================================
    //  SECTION 3: History Management (历史记录容器)
    // =================================================================================

    public void onTradeComplete(ObjectItem item, double effectiveAmount) {
        String productId = item.getProduct();
        long now = System.currentTimeMillis();

        // 写入本地历史
        processTradeInternal(productId, effectiveAmount, now, true);

        // 推送 Redis (用于跨服同步)
        if (RedisManager.getInstance() != null) {
            RedisManager.getInstance().publishTrade(productId, effectiveAmount);
        }
    }

    public void onRemoteTradeReceived(String productId, double amount, long timestamp) {
        // 远程交易只更新本地历史，不写库 (假设源头服已写库)
        processTradeInternal(productId, amount, timestamp, false);
    }

    private void processTradeInternal(String productId, double amount, long timestamp, boolean writeToSql) {
        SaleRecord record = new SaleRecord(timestamp, amount);

        // 更新内存 RingBuffer
        ThreadSafeHistory historyContainer = getHistoryContainer(productId);
        historyContainer.add(record, maxHistorySize);

        // 异步日志记录
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
            // Cache Miss: 从数据库加载历史
            List<SaleRecord> dbData = TransactionDao.getProductHistory(k, historyDaysLimit);
            return new ThreadSafeHistory(dbData);
        });
    }

    public void clearCache() {
        historyCache.invalidateAll();
    }

    /**
     * 线程安全的历史记录容器
     * 使用 ReentrantReadWriteLock 优化读多写少的场景
     */
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