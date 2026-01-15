package top.ellan.ecobridge.manager;

import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 宏观经济管理器 (EconomyManager v0.8.5 - Stability Enhanced)
 * 职责：计算全服通胀率 (ε) 与 市场稳定性指数 (Stability).
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

        // 从 internal 配置加载，但注意下面 saveState 的修改
        double savedHeat = config.getDouble("internal.economy-heat", 0.0);
        circulationHeat.reset();
        circulationHeat.add(savedHeat);

        LogUtil.info("经济脑初始化完成: M1=" + m1MoneySupply + ", 初始热度=" + savedHeat);
    }

    /**
     * [v0.8.5 Fix] 线程安全持久化
     * 职责：强制切回主线程保存配置文件，防止多个虚拟线程并发写入引发 FileConfiguration 损坏。
     * @NotThreadSafe - 内部使用 Bukkit Scheduler 进行同步化处理
     */
    public void saveState() {
        double currentHeat = circulationHeat.sum();
        // 调度回主线程执行 Bukkit 配置保存，确保内存快照到磁盘的原子性
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getConfig().set("internal.economy-heat", currentHeat);
            plugin.saveConfig();
        });
    }

    /**
     * [v0.8.5 Logic] 处理交易变动
     * @param delta 资金变动量
     * @param isMarketActivity 是否为真实市场活动（过滤掉管理员干预）
     */
    public void onTransaction(double delta, boolean isMarketActivity) {
        if (!isMarketActivity) {
            // 管理员增币只增加 M1 基准，不产生“流通热度”，防止物价瞬间虚假暴涨
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

    public double getInflationRate() {
        double currentHeat = circulationHeat.sum();
        // 核心公式：通胀率 = 额外流通速度 / 货币总量
        double rawRate = currentHeat / Math.max(m1MoneySupply, 1.0);

        // 钳位保护：确保物价波动在可控范围内
        return Math.clamp(rawRate, -0.15, 0.45);
    }

    public double getStabilityFactor() {
        long lastVolatile = lastVolatileTimestamp.get();
        if (lastVolatile == 0) return 1.0;

        long diff = System.currentTimeMillis() - lastVolatile;
        double recoveryWindow = 15.0 * 60 * 1000; // 15分钟冷静期

        return Math.clamp((double) diff / recoveryWindow, 0.0, 1.0);
    }

    private void startEconomicTasks() {
        // 每 30 分钟执行一次热度衰减 (48次/天)
        economicScheduler.scheduleAtFixedRate(
        this::runEconomicDecay,
        30, 30, TimeUnit.MINUTES
    );
    }

    private void runEconomicDecay() {
        double current = circulationHeat.sum();
        if (Math.abs(current) < 1.0) {
            circulationHeat.reset(); // 彻底归零，防止残留死数据
            return;
        }

        // 每日 decayRate，分摊到每次循环
        double perCycleDecay = decayRate / 48.0;
        double reduction = current * perCycleDecay;

        circulationHeat.add(-reduction);

        // 仅在热度显著变化时执行 IO，保护磁盘
        if (Math.abs(reduction) > 100.0) {
            saveState();
        }
    }

    public void shutdown() {
        economicScheduler.shutdown();
        // 关机前同步一次 (由于 onDisable 本身在主线程，可直接同步保存)
        plugin.getConfig().set("internal.economy-heat", circulationHeat.sum());
        plugin.saveConfig();
    }
}
