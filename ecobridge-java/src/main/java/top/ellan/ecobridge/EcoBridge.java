package top.ellan.ecobridge;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.cache.HotDataCache;
import top.ellan.ecobridge.command.TransferCommand;
import top.ellan.ecobridge.database.DatabaseManager;
import top.ellan.ecobridge.hook.EcoPlaceholderExpansion;
import top.ellan.ecobridge.listener.CacheListener;
import top.ellan.ecobridge.listener.CoinsEngineListener;
import top.ellan.ecobridge.listener.CommandInterceptor;
import top.ellan.ecobridge.listener.TradeListener;
import top.ellan.ecobridge.manager.*;
import top.ellan.ecobridge.network.RedisManager;
import top.ellan.ecobridge.storage.ActivityCollector;
import top.ellan.ecobridge.storage.AsyncLogger;
import top.ellan.ecobridge.util.HolidayManager;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EcoBridge v0.9.1 - 物理演算与宏观调控内核
 * <p>
 * 更新日志:
 * 1. 初始化链条重构：EconomyManager 提升至首位，为物理核心提供宏观画像。
 * 2. 宏观画像集成：支持 Market Heat (财富流速) 与 Eco Saturation (饱和度) 注入。
 * 3. 性能优化：支持 SIMD 并行演算快照分发。
 */
public final class EcoBridge extends JavaPlugin implements Listener {

    private static volatile EcoBridge instance;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ExecutorService virtualExecutor;
    private final AtomicBoolean fullyInitialized = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;

        // 1. 初始化并发心脏：Java 25 虚拟线程执行器 (Project Loom)
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 2. 引导基础架构 (IO 与 静态工具)
        try {
            bootstrapInfrastructure();
            ActivityCollector.startHeartbeat(this);
        } catch (Exception e) {
            getLogger().severe("基础架构引导失败，系统强制挂起: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        printBanner();

        // 3. 核心环境校验
        if (!verifyDependencies()) return;

        // 4. [核心] 组件加载拓扑 (Dependency Topology)
        try {
            // [STEP 1] 首先启动宏观画像中心
            // 它负责采集全服交易脉冲，为后续所有演算提供 Market Heat 数据源
            EconomyManager.init(this);

            // [STEP 2] 加载 Native 物理核心
            // 依赖说明：初始化时会检查 ABI 版本，并准备好 FFM 内存映射
            NativeBridge.init(this);

            // [STEP 3] 启动逻辑管理层
            // 依赖说明：PricingManager 启动宏观引擎，依赖 EconomyManager 的流速数据
            // 以及 NativeBridge 的自适应 PID 函数句柄
            EconomicStateManager.init(this);
            PricingManager.init(this);
            TransferManager.init(this);

            // [STEP 4] 驱动层注册
            registerCommands();
            registerListeners();
            registerHooks();

            // [STEP 5] 延迟注入 UltimateShop 内核
            getServer().getScheduler().runTaskLater(this, () -> {
                if (getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
                    LogUtil.info("检测到 UltimateShop，正在注入 EcoBridge 宏观演算内核...");
                    UShopLimitInjector.execute(this);
                    UShopPriceInjector.execute(this);
                }
            }, 20L);

            this.fullyInitialized.set(true);
            sendConsole("<blue>┃ <green>系统状态: <white>宏观自适应演算内核已上线 (v0.9.1) <blue>┃");
            sendConsole("<gradient:aqua:blue>┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛</gradient>");

        } catch (Throwable e) {
            LogUtil.error("致命错误: 插件组件初始化链条中断 (拓扑校验失败)", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        sendConsole("<yellow>[EcoBridge] 正在启动安全关机序列 (Panic-Safe Shutdown)...");

        this.fullyInitialized.set(false);
        
        // 还原注入，防止重载导致的内存泄漏
        UShopLimitInjector.revert();
        UShopPriceInjector.revert();

        if (RedisManager.getInstance() != null) {
            RedisManager.getInstance().shutdown();
        }
        
        // 按照依赖反向关闭
        if (PricingManager.getInstance() != null) PricingManager.getInstance().shutdown();
        if (EconomyManager.getInstance() != null) EconomyManager.getInstance().shutdown();
        
        shutdownPersistenceLayer();
        NativeBridge.shutdown();
        terminateVirtualPool();

        getServer().getScheduler().cancelTasks(this);
        instance = null;
    }

    private void bootstrapInfrastructure() {
        saveDefaultConfig();
        LogUtil.init();
        DatabaseManager.init();
        AsyncLogger.init(this);
        HolidayManager.init();
        RedisManager.init(this);
    }

    private void shutdownPersistenceLayer() {
        if (AsyncLogger.getInstance() != null) {
            AsyncLogger.getInstance().shutdown();
        }
        LogUtil.info("正在执行热数据终点刷盘...");
        HotDataCache.saveAllSync();
        DatabaseManager.close();
    }

    private void terminateVirtualPool() {
        if (virtualExecutor != null && !virtualExecutor.isShutdown()) {
            virtualExecutor.shutdown();
            try {
                if (!virtualExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    virtualExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                virtualExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean verifyDependencies() {
        var pm = Bukkit.getPluginManager();
        if (!pm.isPluginEnabled("CoinsEngine")) {
            sendConsole("<blue>┃ <red>致命错误: 未检测到 CoinsEngine，系统强制挂起。 <blue>┃");
            pm.disablePlugin(this);
            return false;
        }
        return true;
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(new CoinsEngineListener(this), this);
        pm.registerEvents(new CommandInterceptor(this), this);
        pm.registerEvents(new TradeListener(this), this);
        pm.registerEvents(new CacheListener(), this);
    }
    
    private void registerHooks() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new EcoPlaceholderExpansion(this).register();
        }
    }

    private void registerCommands() {
        var cmd = getCommand("ecopay");
        if (cmd != null) cmd.setExecutor(new TransferCommand());
    }

    public void reload() {
        reloadConfig();
        LogUtil.init();
        if (EconomyManager.getInstance() != null) EconomyManager.getInstance().loadState();
        if (PricingManager.getInstance() != null) PricingManager.getInstance().loadConfig();
        if (getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
            UShopLimitInjector.execute(this);
            UShopPriceInjector.execute(this);
        }
        sendConsole("<green>[EcoBridge] 逻辑参数重载成功。Native 内存布局已重新热对齐。");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ActivityCollector.updateSnapshot(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ActivityCollector.removePlayer(event.getPlayer().getUniqueId());
    }

    public static EcoBridge getInstance() { return instance; }
    public ExecutorService getVirtualExecutor() { return virtualExecutor; }
    public static MiniMessage getMiniMessage() { return MM; }
    public boolean isFullyInitialized() { return fullyInitialized.get(); }

    private void printBanner() {
        String version = getPluginMeta().getVersion();
        sendConsole("<gradient:aqua:blue>┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓</gradient>");
        sendConsole("<blue>┃ <green>EcoBridge <white>v" + version + " <gray>| <aqua>Macro Adaptive Edition <blue>┃");
    }

    private void sendConsole(String msg, TagResolver... resolvers) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }
}