package top.ellan.ecobridge.application.service;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.control.DefaultEconomySignalCollector;
import top.ellan.ecobridge.application.control.EconomyControlSignals;
import top.ellan.ecobridge.application.control.EconomySignalCollector;
import top.ellan.ecobridge.application.control.MacroControlDecision;
import top.ellan.ecobridge.application.control.CompositeMacroController;
import top.ellan.ecobridge.application.control.MacroControlEngine;
import top.ellan.ecobridge.application.control.PredictiveFuzzyFluidController;
import top.ellan.ecobridge.domain.algorithm.PriceComputeEngine;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Macro control scheduler for global pricing snapshots.
 * PID has been fully replaced by Predictive + Fuzzy + Sink/Faucet control.
 */
public class MacroEngine {

    private final EcoBridge plugin;
    private final AtomicReference<Map<String, Double>> priceSnapshot = new AtomicReference<>(Collections.emptyMap());
    private final AtomicReference<MacroSnapshot> metadataMirror = new AtomicReference<>(null);

    private final ScheduledExecutorService scheduler;
    private volatile BukkitTask metadataSyncTask;
    private long lastComputeTime = System.currentTimeMillis();

    private double defaultLambda;
    private double configTau;
    private double targetTradesPerUser;
    private double targetM1Supply;
    private double controlHorizonSeconds;
    private double controlLambdaMin;
    private double controlLambdaMax;

    private MacroControlEngine macroController;
    private EconomySignalCollector signalCollector;

    public MacroEngine(EcoBridge plugin) {
        this.plugin = plugin;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("EcoBridge-Macro-Scheduler").factory()
        );
    }

    public void updateConfig(
            double lambda,
            double tau,
            double targetVelocity,
            double targetM1,
            double horizonSeconds,
            double minLambdaMultiplier,
            double maxLambdaMultiplier
    ) {
        this.defaultLambda = lambda;
        this.configTau = tau;
        this.targetTradesPerUser = targetVelocity;
        this.targetM1Supply = targetM1;
        this.controlHorizonSeconds = horizonSeconds;
        this.controlLambdaMin = minLambdaMultiplier;
        this.controlLambdaMax = Math.max(minLambdaMultiplier, maxLambdaMultiplier);

        EconomyManager economyManager = EconomyManager.getInstance();
        if (economyManager != null) {
            this.signalCollector = new DefaultEconomySignalCollector(economyManager, targetM1Supply);
        }

        this.macroController = new CompositeMacroController(
            new PredictiveFuzzyFluidController(
                controlHorizonSeconds,
                controlLambdaMin,
                controlLambdaMax
            )
        );
    }

    public void start() {
        this.lastComputeTime = System.currentTimeMillis();

        metadataSyncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncMetadataFromMain, 20L, 100L);
        scheduler.scheduleAtFixedRate(this::runCycle, 2, 2, TimeUnit.SECONDS);

        LogUtil.info("MacroEngine started (Predictive Fuzzy Fluid control)");
    }

    public void shutdown() {
        LogUtil.info("Shutting down MacroEngine...");
        BukkitTask task = metadataSyncTask;
        if (task != null) {
            task.cancel();
            metadataSyncTask = null;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void injectRemoteVolume(double amount) {
        EconomyManager economyManager = EconomyManager.getInstance();
        if (economyManager != null) {
            economyManager.recordTradeVolume(Math.abs(amount));
        }
    }

    public void incrementTradeCounter() {
        EconomyManager economyManager = EconomyManager.getInstance();
        if (economyManager != null) {
            economyManager.recordTradeVolume(1.0);
        }
    }

    private void syncMetadataFromMain() {
        int online = Bukkit.getOnlinePlayers().size();
        FileConfiguration itemsConfig = ItemConfigManager.get();
        if (itemsConfig == null) return;

        ConfigurationSection section = itemsConfig.getConfigurationSection("item-settings");
        List<PriceComputeEngine.ItemMeta> items = new ArrayList<>();

        if (section != null) {
            for (String shopKey : section.getKeys(false)) {
                ConfigurationSection shopSection = section.getConfigurationSection(shopKey);
                if (shopSection == null) continue;

                for (String productId : shopSection.getKeys(false)) {
                    String uniqueKey = shopKey + "." + productId;
                    double basePrice = shopSection.getDouble(productId + ".base-price", 100.0);
                    double lambda = shopSection.getDouble(productId + ".lambda", defaultLambda);

                    items.add(new PriceComputeEngine.ItemMeta(
                            uniqueKey, shopKey, productId, basePrice, lambda
                    ));
                }
            }
        }

        metadataMirror.set(new MacroSnapshot(online, items));
    }

    public Map<String, Double> getCurrentSnapshot() {
        return priceSnapshot.get();
    }

    // Compatibility shim for placeholder APIs expecting PID memory.
    public MemorySegment getGlobalPidState() {
        return MemorySegment.NULL;
    }

    private void runCycle() {
        if (!plugin.isEnabled() || !NativeBridge.isLoaded()) return;

        MacroSnapshot snapshot = metadataMirror.get();
        if (snapshot == null) return;

        try {
            performCalculation(snapshot);
        } catch (Exception e) {
            LogUtil.error("MacroEngine async calculation failed", e);
        }
    }

    private void performCalculation(MacroSnapshot snapshot) {
        long now = System.currentTimeMillis();
        double dt = (now - lastComputeTime) / 1000.0;
        lastComputeTime = now;

        if (dt < 0.1 || dt > 5.0) {
            dt = 2.0;
        }

        if (signalCollector == null || macroController == null) return;

        EconomyControlSignals signals = signalCollector.collect(
                snapshot.onlinePlayers(),
                dt,
                targetTradesPerUser
        );
        MacroControlDecision decision = macroController.decide(signals);
        double effectiveLambda = defaultLambda * decision.lambdaMultiplier();

        Map<String, Double> nextPrices = PriceComputeEngine.computeSnapshot(
                plugin,
                configTau,
                effectiveLambda,
                snapshot.items
        );

        if (nextPrices != null && !nextPrices.isEmpty()) {
            priceSnapshot.set(Map.copyOf(nextPrices));
        }

        if (plugin.getConfig().getBoolean("system.debug", false)) {
            LogUtil.debug("Macro control: lambda=" + String.format("%.4f", effectiveLambda)
                    + ", reason=" + decision.reason());
        }
    }

    private record MacroSnapshot(
            int onlinePlayers,
            List<PriceComputeEngine.ItemMeta> items
    ) {
    }
}
