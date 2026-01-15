package top.ellan.ecobridge.manager;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宏观经济管理器 (EconomyManager v0.7.4)
 * 职责：计算全服通胀率 (ε) 与 市场稳定性指数 (Stability).
 * 修复：解决 FileConfiguration 在多线程写入时的竞态条件。
 */
public class EconomyManager {

    private static EconomyManager instance;
    private final EcoBridge plugin;

    // --- 核心经济状态 (高性能原子类) ---
    private final DoubleAdder circulationHeat = new DoubleAdder(); 
    private final AtomicLong lastVolatileTimestamp = new AtomicLong(0);
    
    // [修复点] 配置文件保存锁，防止损坏 config.yml
    private final ReentrantLock saveLock = new ReentrantLock();

    // --- 算法超参数 ---
    private double m1MoneySupply;       // M1 货币总量基准
    private double volatilityThreshold; // 波动触发阈值
    private double decayRate;           // 自然衰减率

    // 专用虚拟线程调度器 (Java 25)
    private final ScheduledExecutorService economicScheduler;

    private EconomyManager(EcoBridge plugin) {
        this.plugin = plugin;
        this.economicScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("EcoBridge-Decay-Worker").factory()
        );
        loadState();
        startEconomicTasks();
    }

    public static void init(EcoBridge plugin) {
        instance = new EconomyManager(plugin);
    }

    public static EconomyManager getInstance() {
        return instance;
    }

    public void loadState() {
        var config = plugin.getConfig();
        this.m1MoneySupply = config.getDouble("economy.m1-supply", 10_000_000.0);
        this.volatilityThreshold = config.getDouble("economy.volatility-threshold", 50_000.0);
        this.decayRate = config.getDouble("economy.daily-decay-rate", 0.05);
        
        double savedHeat = config.getDouble("internal.economy-heat", 0.0);
        circulationHeat.reset();
        circulationHeat.add(savedHeat);
        
        LogUtil.info("经济脑初始化完成: 基准 M1=" + m1MoneySupply + ", 当前热度=" + savedHeat);
    }

    /**
     * [线程安全] 保存经济热度
     * 使用 ReentrantLock 确保写入原子性
     */
    public void saveState() {
        saveLock.lock();
        try {
            plugin.getConfig().set("internal.economy-heat", circulationHeat.sum());
            plugin.saveConfig();
        } catch (Exception e) {
            LogUtil.error("保存经济状态失败", e);
        } finally {
            saveLock.unlock();
        }
    }

    public void onTransaction(double delta) {
        circulationHeat.add(delta);
        if (Math.abs(delta) >= volatilityThreshold) {
            markMarketVolatile();
            if (LogUtil.isDebugEnabled()) {
                LogUtil.debug("检测到市场剧烈波动! 变动量: " + delta);
            }
        }
    }

    private void markMarketVolatile() {
        lastVolatileTimestamp.set(System.currentTimeMillis());
    }

    public double getInflationRate() {
        double currentHeat = circulationHeat.sum();
        double rawRate = currentHeat / m1MoneySupply;
        // 钳位保护 (-15% ~ +45%)
        return Math.clamp(rawRate, -0.15, 0.45);
    }

    public double getStabilityFactor() {
        long lastVolatile = lastVolatileTimestamp.get();
        if (lastVolatile == 0) return 1.0;

        long diff = System.currentTimeMillis() - lastVolatile;
        
        // 恢复窗口: 15分钟
        double recoveryWindow = 15.0 * 60 * 1000;
        double factor = (double) diff / recoveryWindow;
        
        return Math.clamp(factor, 0.0, 1.0);
    }

    private void startEconomicTasks() {
        // 每 30 分钟执行一次热度衰减
        economicScheduler.scheduleAtFixedRate(
                this::runEconomicDecay, 
                30, 30, TimeUnit.MINUTES
        );
    }

    private void runEconomicDecay() {
        double current = circulationHeat.sum();
        if (Math.abs(current) < 0.01) return;

        double perCycleDecay = decayRate / 48.0;
        double reduction = current * perCycleDecay;
        
        circulationHeat.add(-reduction);
        
        // 衰减后触发一次保存，降低数据丢失风险
        if (Math.abs(reduction) > 1.0) {
            saveState();
        }
    }

    public EconomySnapshot getSnapshot() {
        return new EconomySnapshot(
            getInflationRate(),
            getStabilityFactor(),
            System.currentTimeMillis()
        );
    }

    public void shutdown() {
        saveState();
        economicScheduler.shutdown();
        try {
            if (!economicScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                economicScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            economicScheduler.shutdownNow();
        }
    }

    public record EconomySnapshot(double inflationRate, double stability, long timestamp) {}
}