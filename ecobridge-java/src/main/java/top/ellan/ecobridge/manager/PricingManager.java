package top.ellan.ecobridge.manager;

import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.api.event.PriceCalculatedEvent;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.collector.ActivityCollector;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.model.SaleRecord;
import top.ellan.ecobridge.network.RedisManager; // [关键] 引入 RedisManager
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
 * 核心定价管理器 (PricingManager v0.8.2)
 * 职责：驱动物理演算链，支持本地演算与 Redis 分布式同步。
 */
public class PricingManager {

    private static PricingManager instance;
    private final EcoBridge plugin;

    // 二级缓存：仅用于 UI 列表展示
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
     * 【物理演算核心】演算动态价格
     * 公式：P = p0 * exp(-lambda * Neff) * epsilon
     */
    public double calculateDynamicPrice(Player player, ObjectItem item, double amount) {
        String productId = item.getProduct();
        String shopId = item.getShop();

        boolean isBuy = amount < 0;
        double basePrice = PriceOracle.getOriginalBasePrice(item, isBuy);
        if (basePrice <= 0) return 0.0;

        try (Arena arena = Arena.ofConfined()) {
            
            // 1. 环境因子采集
            double inflation = EconomyManager.getInstance().getInflationRate();
            var activity = ActivityCollector.capture(player, 48.0);
            
            double lambda = plugin.getConfig().getDouble("item-settings." + shopId + "." + productId + ".lambda", defaultLambda);

            // 2. 【计算下沉】向 Native 核心请求向量化 Neff 聚合
            double nEff = NativeBridge.queryNeffVectorized(System.currentTimeMillis(), configTau);

            // 3. 映射 TradeContext
            MemorySegment ctx = arena.allocate(Layouts.TRADE_CONTEXT);
            VH_CTX_BASE_PRICE.set(ctx, 0L, basePrice);
            VH_CTX_CURR_AMT.set(ctx, 0L, amount);
            VH_CTX_INF_RATE.set(ctx, 0L, inflation);
            VH_CTX_TIMESTAMP.set(ctx, 0L, System.currentTimeMillis());
            VH_CTX_PLAY_TIME.set(ctx, 0L, (long) activity.seconds());
            
            // 4. 节假日 Mask 位注入
            int isNewbie = (activity.isNewbie() == 1) ? 1 : 0;
            boolean forceFestival = plugin.getConfig().getBoolean("economy.force-festival-mode", false);
            int isHoliday = (forceFestival || HolidayManager.isTodayHoliday()) ? 1 : 0;
            
            int mask = (isHoliday << 1) | isNewbie;
            VH_CTX_NEWBIE_MASK.set(ctx, 0L, mask);

            // 5. 映射 MarketConfig
            MemorySegment cfg = prepareMarketConfig(arena);

            // 6. 执行跨语言物理演算
            double epsilon = NativeBridge.calculateEpsilon(ctx, cfg);
            double rustPrice = NativeBridge.computePrice(basePrice, nEff, lambda, epsilon);

            // 7. 事件分发
            PriceCalculatedEvent event = new PriceCalculatedEvent(player, shopId, productId, rustPrice);
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
     * [本地触发] 交易完成回调
     * 场景：本服玩家完成了一笔交易
     */
    public void onTradeComplete(ObjectItem item, double effectiveAmount) {
        String productId = item.getProduct();
        long now = System.currentTimeMillis();

        // 1. 本地处理 (DuckDB + Cache + MySQL)
        // writeToSql = true，因为这是本地产生的源数据
        processTradeInternal(productId, effectiveAmount, now, true);

        // 2. 广播到 Redis (通知其他服务器)
        if (RedisManager.getInstance() != null) {
            RedisManager.getInstance().publishTrade(productId, effectiveAmount);
        }
    }

    /**
     * [远程触发] 处理来自 Redis 的跨服交易
     * 场景：收到 Redis 广播，得知其他服务器发生了一笔交易
     * 该方法由 RedisManager 自动调用
     */
    public void onRemoteTradeReceived(String productId, double amount, long timestamp) {
        // 1. 同步处理 (DuckDB + Cache)
        // writeToSql = false (防止数据重复写入 MySQL，MySQL 由产生交易的那个服务器负责)
        processTradeInternal(productId, amount, timestamp, false);
    }

    /**
     * 统一交易处理逻辑
     * @param writeToSql 是否写入本地 MySQL 数据库
     */
    private void processTradeInternal(String productId, double amount, long timestamp, boolean writeToSql) {
        // 1. 更新 Caffeine 本地缓存 (用于 GUI 历史记录显示)
        SaleRecord record = new SaleRecord(timestamp, amount);
        List<SaleRecord> history = getGlobalHistory(productId);
        if (history != null) {
            history.add(0, record); 
            if (history.size() > maxHistorySize) history.remove(history.size() - 1);
        }

        // 2. Native DuckDB 日志写入 (核心物理引擎数据源)
        // 为了在 DuckDB 中区分本地和远程交易，我们使用不同的 UUID 生成策略
        java.util.UUID loggerUuid = writeToSql 
            ? Bukkit.getOfflinePlayer(productId).getUniqueId() // 本地: 尝试获取玩家UUID
            : java.util.UUID.nameUUIDFromBytes(("REMOTE_" + productId).getBytes()); // 远程: 生成虚拟UUID

        AsyncLogger.log(
            loggerUuid,
            amount,
            0.0, 
            timestamp, 
            writeToSql ? "LOCAL_TRADE" : "REMOTE_SYNC" // Meta 标记
        );

        // 3. 异步 SQL 备份 (仅限本地产生的交易)
        if (writeToSql) {
            TransactionDao.saveSaleAsync(null, productId, amount);
        }
    }

    /**
     * 填充 MarketConfig 结构体
     */
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