// ==================================================
// FILE: ecobridge-java/src/main/java/top/ellan/ecobridge/manager/EconomyManager.java
// ==================================================

package top.ellan.ecobridge.manager;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.bridge.NativeBridge; // [核心依赖]
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宏观经济管理器 (EconomyManager v1.2.0 - Native Math & Async I/O)
 * <p>
 * 职责：
 * 1. 维护宏观经济状态 (流通热度, 波动时间戳).
 * 2. [Native] 调用 Rust 计算通胀率、稳定性和衰减.
 * 3. [Async] 线程安全地持久化状态.
 */
public class EconomyManager {

    private static EconomyManager instance;
    private final EcoBridge plugin;

    // --- 核心经济状态 ---
    private final DoubleAdder circulationHeat = new DoubleAdder();
    private final AtomicLong lastVolatileTimestamp = new AtomicLong(0);

    // --- 算法超参数 ---
    private double m1MoneySupply;    // 货币发行总量 (基准)
    private double volatilityThreshold; // 波动触发阈值 (单笔交易)
    private double decayRate;      // 每日热度自然衰减率

    private final ScheduledExecutorService economicScheduler;
    
    // I/O 锁：防止多线程并发写入 Config 导致文件损坏
    private final ReentrantLock configLock = new ReentrantLock();

    private EconomyManager(EcoBridge plugin) {
        this.plugin = plugin;
        this.economicScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("EcoBridge-Economy-Worker").factory()
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

        // 从 internal 配置加载
        double savedHeat = config.getDouble("internal.economy-heat", 0.0);
        circulationHeat.reset();
        circulationHeat.add(savedHeat);

        LogUtil.info("经济脑初始化完成: M1=" + m1MoneySupply + ", 初始热度=" + savedHeat);
    }

    /**
     * 线程安全的异步持久化
     */
    public void saveState() {
        double currentHeat = circulationHeat.sum();
        
        // 投递到虚拟线程池执行 I/O，避免阻塞主线程
        plugin.getVirtualExecutor().execute(() -> {
            configLock.lock();
            try {
                plugin.getConfig().set("internal.economy-heat", currentHeat);
                plugin.saveConfig(); // 磁盘 I/O
            } catch (Exception e) {
                LogUtil.error("保存经济状态失败", e);
            } finally {
                configLock.unlock();
            }
        });
    }

    /**
     * 处理交易变动
     */
    public void onTransaction(double delta, boolean isMarketActivity) {
        if (!isMarketActivity) {
            // 管理员增币只增加 M1 基准
            this.m1MoneySupply += delta;
            return;
        }

        circulationHeat.add(delta);

        if (Math.abs(delta) >= volatilityThreshold) {
            markMarketVolatile();
        }
    }

    private void markMarketVolatile() {
        lastVolatileTimestamp.set(System.currentTimeMillis());
    }

    // =================================================================================
    //  SECTION: Native Math Calls (Rust 接管核心算法)
    // =================================================================================

    public double getInflationRate() {
        // [Call Native] 计算通胀率
        if (!NativeBridge.isLoaded()) return 0.0;
        
        double currentHeat = circulationHeat.sum();
        return NativeBridge.calcInflation(currentHeat, m1MoneySupply);
    }

    public double getStabilityFactor() {
        // [Call Native] 计算稳定性因子
        if (!NativeBridge.isLoaded()) return 1.0;
        
        long lastVolatile = lastVolatileTimestamp.get();
        long now = System.currentTimeMillis();
        return NativeBridge.calcStability(lastVolatile, now);
    }

    private void startEconomicTasks() {
        // 每 30 分钟执行一次热度衰减
        economicScheduler.scheduleAtFixedRate(
            this::runEconomicDecay,
            30, 30, TimeUnit.MINUTES
        );
    }

    private void runEconomicDecay() {
        if (!NativeBridge.isLoaded()) return;

        double current = circulationHeat.sum();
        
        // [Call Native] 计算本周期应衰减的量
        // Rust 侧已包含了 abs < 1.0 归零的逻辑判断
        double reduction = NativeBridge.calcDecay(current, decayRate);

        // 如果 reduction > 0，说明需要衰减
        // 注意：reduction 是正数，我们 add 负数
        if (Math.abs(reduction) > 0.0) {
            circulationHeat.add(-reduction);
        }

        // 仅在热度显著变化时执行 IO，保护磁盘
        if (Math.abs(reduction) > 100.0) {
            saveState();
        }
    }

    public void shutdown() {
        economicScheduler.shutdown();
        
        // 关机时在当前线程同步保存，确保数据不丢失
        configLock.lock();
        try {
            plugin.getConfig().set("internal.economy-heat", circulationHeat.sum());
            plugin.saveConfig();
        } finally {
            configLock.unlock();
        }
    }
}