package top.ellan.ecobridge.manager;

import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.api.event.PriceCalculatedEvent;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.bridge.NativeBridge.Layouts;
import top.ellan.ecobridge.collector.ActivityCollector;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.model.SaleRecord;
import top.ellan.ecobridge.network.RedisManager;
import top.ellan.ecobridge.storage.AsyncLogger;
import top.ellan.ecobridge.util.HolidayManager;
import top.ellan.ecobridge.util.LogUtil;
import top.ellan.ecobridge.util.PriceOracle;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static top.ellan.ecobridge.bridge.NativeBridge.*;

/**
 * 核心定价管理器 (PricingManager v0.8.5 - Behavioral Edition)
 * 职责：驱动物理演算链，集成行为经济学干预（滑动地板、阶梯计价）。
 */
public class PricingManager {

    private static PricingManager instance;
    private final EcoBridge plugin;

    private final Cache<String, List<SaleRecord>> historyCache;

    private double defaultLambda;
    private double configTau;
    private int historyDaysLimit;
    private int maxHistorySize;

    private PricingManager(EcoBridge plugin) {
        this.plugin = plugin;
        this.historyCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build();
        loadConfig();
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

    /**
     * 【行为演进定价核心】
     * 整合：物理演算 + 7d 均价锚定 + 阶梯式边际效用
     */
    public double calculateDynamicPrice(Player player, ObjectItem item, double amount) {
        String productId = item.getProduct();
        String shopId = item.getShop();

        boolean isBuy = amount < 0;
        double basePrice = PriceOracle.getOriginalBasePrice(item, isBuy);
        if (basePrice <= 0) return 0.0;

        try (Arena arena = Arena.ofConfined()) {
            // 1. 获取行为锚定数据 (7天历史均价)
            double histAvg = TransactionDao.get7DayAverage(productId);

            // 2. 环境因子与物理参数采集
            double inflation = EconomyManager.getInstance().getInflationRate();
            var activity = ActivityCollector.capture(player, 48.0);
            double lambda = plugin.getConfig().getDouble("item-settings." + shopId + "." + productId + ".lambda", defaultLambda);

            // 3. 向 Native 请求物理状态
            double nEff = NativeBridge.queryNeffVectorized(System.currentTimeMillis(), configTau);

            // 4. 映射上下文与配置
            MemorySegment ctx = arena.allocate(Layouts.TRADE_CONTEXT);
            VH_CTX_BASE_PRICE.set(ctx, 0L, basePrice);
            VH_CTX_CURR_AMT.set(ctx, 0L, amount);
            VH_CTX_INF_RATE.set(ctx, 0L, inflation);
            VH_CTX_TIMESTAMP.set(ctx, 0L, System.currentTimeMillis());
            VH_CTX_PLAY_TIME.set(ctx, 0L, (long) activity.seconds());
            
            // [v0.8.5 Fix] 必须注入时区偏移，修复 FFI 内存错位问题
            int zoneOffset = java.time.OffsetDateTime.now().getOffset().getTotalSeconds();
            NativeBridge.VH_CTX_TIMEZONE_OFFSET.set(ctx, 0L, zoneOffset);

            int mask = ((HolidayManager.isTodayHoliday() ? 1 : 0) << 1) | (activity.isNewbie() == 1 ? 1 : 0);
            VH_CTX_NEWBIE_MASK.set(ctx, 0L, mask);

            MemorySegment cfg = prepareMarketConfig(arena);

            // 5. 感知市场相位并获取行为修正系数
            var stateManager = EconomicStateManager.getInstance();
            var phase = stateManager.analyzeMarketAndNotify(productId, nEff);
            double behavioralModifier = stateManager.getBehavioralLambdaModifier(phase);

            // 6. 执行跨语言物理演算
            double epsilon = NativeBridge.calculateEpsilon(ctx, cfg);
            double finalLambda = lambda * behavioralModifier;
            double calculatedPrice = NativeBridge.computePrice(basePrice, nEff, amount, finalLambda, epsilon);

            // 7. [行为微调层] 滑动地板保护 (Sliding Floor)
            double dynamicFloor = histAvg * 0.2;
            if (calculatedPrice < dynamicFloor) {
                calculatedPrice = Math.max(dynamicFloor, 0.01);
            }

            // 8. [行为微调层] 阶梯计价与边际效用 (Marginal Utility)
            double finalPrice = applyTierPricing(calculatedPrice, Math.abs(amount), !isBuy);

            // 9. 事件分发 [v0.8.5 Fix] 强制主线程回调，防止跨线程调用 Bukkit 事件监听器
            PriceCalculatedEvent event = new PriceCalculatedEvent(player, shopId, productId, finalPrice);
            if (!Bukkit.isPrimaryThread()) {
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
            } else {
                Bukkit.getPluginManager().callEvent(event);
            }

            return event.getFinalPrice();

        } catch (Throwable e) {
            LogUtil.error("物理定价内核演算链路故障 [" + productId + "]", e);
            return basePrice;
        }
    }

    /**
     * 应用阶梯式计价 (边际收益递减)
     */
    private double applyTierPricing(double basePrice, double quantity, boolean isSell) {
        if (!isSell || quantity <= 500) return basePrice;

        double totalValue = 0;
        double remaining = quantity;

        double t1 = Math.min(remaining, 500);
        totalValue += t1 * basePrice;
        remaining -= t1;

        if (remaining > 0) {
            double t2 = Math.min(remaining, 1500);
            totalValue += t2 * (basePrice * 0.85);
            remaining -= t2;
        }

        if (remaining > 0) {
            totalValue += remaining * (basePrice * 0.6);
        }

        return totalValue / quantity;
    }

    public void onTradeComplete(ObjectItem item, double effectiveAmount) {
        String productId = item.getProduct();
        long now = System.currentTimeMillis();

        processTradeInternal(productId, effectiveAmount, now, true);

        if (RedisManager.getInstance() != null) {
            RedisManager.getInstance().publishTrade(productId, effectiveAmount);
        }
    }

    public void onRemoteTradeReceived(String productId, double amount, long timestamp) {
        processTradeInternal(productId, amount, timestamp, false);
    }

    /**
     * [v0.8.5 Fix] 线程安全重构：隔离数据库操作与 Bukkit API
     */
    private void processTradeInternal(String productId, double amount, long timestamp, boolean writeToSql) {
        SaleRecord record = new SaleRecord(timestamp, amount);
        List<SaleRecord> history = getGlobalHistory(productId);
        if (history != null) {
            history.add(0, record);
            if (history.size() > maxHistorySize) history.remove(history.size() - 1);
        }

        // [v0.8.5 Fix] 核心重构点：在虚拟线程中只能执行 DB/计算
        // Bukkit.getOfflinePlayer 是非线程安全的，必须调度回主线程或在进入异步前完成解析
        if (writeToSql) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                java.util.UUID loggerUuid = Bukkit.getOfflinePlayer(productId).getUniqueId();
                
                // 获取到 UUID 后再异步记录日志
                plugin.getVirtualExecutor().execute(() -> {
                    AsyncLogger.log(loggerUuid, amount, 0.0, timestamp, "LOCAL_TRADE");
                    TransactionDao.saveSaleAsync(null, productId, amount);
                });
            });
        } else {
            java.util.UUID remoteUuid = java.util.UUID.nameUUIDFromBytes(("REMOTE_" + productId).getBytes());
            AsyncLogger.log(remoteUuid, amount, 0.0, timestamp, "REMOTE_SYNC");
        }
    }

    private MemorySegment prepareMarketConfig(Arena arena) {
        MemorySegment cfg = arena.allocate(Layouts.MARKET_CONFIG);
        var config = plugin.getConfig();

        cfg.set(ValueLayout.JAVA_DOUBLE, 0, defaultLambda);
        cfg.set(ValueLayout.JAVA_DOUBLE, 8, 1.0);
        cfg.set(ValueLayout.JAVA_DOUBLE, 16, config.getDouble("economy.seasonal-amplitude", 0.15));
        cfg.set(ValueLayout.JAVA_DOUBLE, 24, config.getDouble("economy.weekend-multiplier", 1.2));
        cfg.set(ValueLayout.JAVA_DOUBLE, 32, config.getDouble("economy.newbie-protection-rate", 0.2));
        cfg.set(ValueLayout.JAVA_DOUBLE, 40, config.getDouble("economy.weights.seasonal", 0.25));
        cfg.set(ValueLayout.JAVA_DOUBLE, 48, config.getDouble("economy.weights.weekend", 0.25));
        cfg.set(ValueLayout.JAVA_DOUBLE, 56, config.getDouble("economy.weights.newbie", 0.25));
        cfg.set(ValueLayout.JAVA_DOUBLE, 64, config.getDouble("economy.weights.inflation", 0.25));

        return cfg;
    }

    public List<SaleRecord> getGlobalHistory(String productId) {
        return historyCache.get(productId, k ->
            new CopyOnWriteArrayList<>(TransactionDao.getProductHistory(k, historyDaysLimit))
        );
    }

    public void clearCache() {
        historyCache.invalidateAll();
    }
}