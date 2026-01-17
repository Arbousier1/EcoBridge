package top.ellan.ecobridge.manager;

import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宏观经济管理器 (EconomyManager v1.5.0 - Macro Intelligence)
 * <p>
 * 职责：
 * 1. 采集全服实时交易脉冲，计算财富流速 (Market Heat)。
 * 2. 计算生态饱和度与通胀率，驱动 Rust 侧的自适应价格弹性。
 * 3. 异步持久化经济热度状态，确保服务器重启后的逻辑连续性。
 */
public class EconomyManager {

    private static EconomyManager instance;
    private final EcoBridge plugin;

    // --- 核心经济指标 (对齐 NativeContextBuilder) ---
    private volatile double inflationRate = 0.0;
    private volatile double marketHeat = 0.0;
    private volatile double ecoSaturation = 0.0;

    // --- 采样与状态累加器 ---
    private final DoubleAdder circulationHeat = new DoubleAdder();      // 长期累积热度 (用于持久化)
    private final DoubleAdder tradeVolumeAccumulator = new DoubleAdder(); // 短期交易脉冲 (用于计算 Heat)
    private final AtomicLong lastVolatileTimestamp = new AtomicLong(0);
    private long lastMacroUpdateTime = System.currentTimeMillis();

    // --- 算法配置参数 ---
    private double m1MoneySupply;       // 货币发行总量
    private double volatilityThreshold; // 波动触发阈值
    private double decayRate;           // 热度自然衰减率
    private double capacityPerUser;     // 单用户对流速的理论承载力

    private final ScheduledExecutorService economicScheduler;
    private final ReentrantLock configLock = new ReentrantLock();

    private EconomyManager(EcoBridge plugin) {
        this.plugin = plugin;
        this.economicScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("EcoBridge-Economy-Worker").factory()
        );
        
        loadState();
        startEconomicTasks();
        startMacroAnalyticsTask();
    }

    public static void init(EcoBridge plugin) {
        instance = new EconomyManager(plugin);
    }

    public static EconomyManager getInstance() {
        return instance;
    }

    // =================================================================================
    // SECTION: 核心业务逻辑 (API)
    // =================================================================================

    /**
     * 加载持久化状态
     */
    public void loadState() {
        var config = plugin.getConfig();
        this.m1MoneySupply = config.getDouble("economy.m1-supply", 10_000_000.0);
        this.volatilityThreshold = config.getDouble("economy.volatility-threshold", 50_000.0);
        this.decayRate = config.getDouble("economy.daily-decay-rate", 0.05);
        this.capacityPerUser = config.getDouble("economy.macro.capacity-per-user", 5000.0);

        double savedHeat = config.getDouble("internal.economy-heat", 0.0);
        circulationHeat.reset();
        circulationHeat.add(savedHeat);

        LogUtil.info("EconomyManager 初始化: M1=" + m1MoneySupply + ", 初始累积热度=" + savedHeat);
    }

    /**
     * 处理每一笔发生的交易 (由 PricingManager 或 TransferManager 调用)
     * @param amount 交易额
     * @param isMarketActivity 是否为市场行为（如：商店买卖）
     */
    public void onTransaction(double amount, boolean isMarketActivity) {
        double absAmount = Math.abs(amount);
        
        if (!isMarketActivity) {
            // 非市场行为（如管理员给钱）只影响 M1 总量
            this.m1MoneySupply += amount;
            return;
        }

        // 1. 注入短期脉冲累加器 (驱动 Market Heat)
        tradeVolumeAccumulator.add(absAmount);
        
        // 2. 注入长期累积热度
        circulationHeat.add(absAmount);

        // 3. 波动监测
        if (absAmount >= volatilityThreshold) {
            lastVolatileTimestamp.set(System.currentTimeMillis());
        }
    }

    /**
     * 别名方法，用于 recordTradeVolume 调用
     */
    public void recordTradeVolume(double amount) {
        onTransaction(amount, true);
    }

    // =================================================================================
    // SECTION: 宏观画像演算 (The Brain)
    // =================================================================================

    /**
     * 每一秒执行一次宏观指标演算
     */
    private void startMacroAnalyticsTask() {
        economicScheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                double dt = (now - lastMacroUpdateTime) / 1000.0;
                if (dt < 0.1) return;

                // A. 计算财富流速 (Market Heat: V)
                double currentWindowVolume = tradeVolumeAccumulator.sumThenReset();
                this.marketHeat = currentWindowVolume / dt;

                // B. 计算生态饱和度 (Eco Saturation)
                int online = Bukkit.getOnlinePlayers().size();
                double totalCapacity = Math.max(1, online) * capacityPerUser;
                this.ecoSaturation = Math.min(1.0, marketHeat / totalCapacity);

                // C. 计算实时通胀率 (FFI 调用 Rust 核心算法)
                if (NativeBridge.isLoaded()) {
                    this.inflationRate = NativeBridge.calcInflation(marketHeat, m1MoneySupply);
                }

                lastMacroUpdateTime = now;
            } catch (Exception e) {
                LogUtil.warn("宏观画像任务异常: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void startEconomicTasks() {
        // 每 30 分钟执行一次 Native 热度自然衰减
        economicScheduler.scheduleAtFixedRate(this::runEconomicDecay, 30, 30, TimeUnit.MINUTES);
    }

    private void runEconomicDecay() {
        if (!NativeBridge.isLoaded()) return;

        double currentTotal = circulationHeat.sum();
        double reduction = NativeBridge.calcDecay(currentTotal, decayRate);

        if (Math.abs(reduction) > 0.01) {
            circulationHeat.add(-reduction);
            // 只有在大规模衰减时执行 IO，节省性能
            if (reduction > 100.0) saveState();
        }
    }

    public void saveState() {
        double currentTotal = circulationHeat.sum();
        plugin.getVirtualExecutor().execute(() -> {
            configLock.lock();
            try {
                plugin.getConfig().set("internal.economy-heat", currentTotal);
                plugin.saveConfig();
            } finally {
                configLock.unlock();
            }
        });
    }

    // =================================================================================
    // SECTION: Getters (供 NativeContextBuilder 及其它模块调用)
    // =================================================================================

    public double getMarketHeat() { return this.marketHeat; }
    public double getEcoSaturation() { return this.ecoSaturation; }
    public double getInflationRate() { return this.inflationRate; }

    public double getStabilityFactor() {
        if (!NativeBridge.isLoaded()) return 1.0;
        return NativeBridge.calcStability(lastVolatileTimestamp.get(), System.currentTimeMillis());
    }

    public void shutdown() {
        economicScheduler.shutdown();
        saveState();
    }
}