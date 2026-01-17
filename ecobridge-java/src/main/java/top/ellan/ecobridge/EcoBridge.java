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
import top.ellan.ecobridge.hook.UShopHookManager;
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
 * EcoBridge v0.9.4 - 物理演算与宏观调控内核
 * <p>
 * 更新摘要:
 * 1. 移除 JVM ShutdownHook，完全交由 Bukkit 生命周期管理，消除线程竞争风险。
 * 2. 强化 shutdownSequence 的原子性，通过 getAndSet 确保资源释放仅触发一次。
 * 3. 规范资源回收序列：先解除第三方注入，再刷盘，最后关闭底层连接与线程池。
 */
public final class EcoBridge extends JavaPlugin implements Listener {

    private static volatile EcoBridge instance;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ExecutorService virtualExecutor;
    private final AtomicBoolean fullyInitialized = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;

        // 1. 初始化并发核心 (Java 25 Virtual Threads)
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 2. 引导基础架构 (I/O, 静态工具)
        // ✅ 修正：不再注册 Runtime ShutdownHook。
        // 在 Bukkit 环境中，onDisable 是资源回收的最佳场所。
        try {
            bootstrapInfrastructure();
            ActivityCollector.startHeartbeat(this);
            printBanner();
        } catch (Exception e) {
            getLogger().severe("基础架构引导失败: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 环境与依赖校验
        if (!verifyDependencies()) return;

        // 4. 组件加载拓扑 (Dependency Topology)
        try {
            // [STEP 1] 宏观画像中心
            EconomyManager.init(this);

            // [STEP 2] Native 物理核心 (FFM 绑定)
            NativeBridge.init(this);

            // [STEP 3] 业务管理器层
            EconomicStateManager.init(this);
            PricingManager.init(this);
            TransferManager.init(this);

            // [STEP 4] 注册驱动层
            registerCommands();
            registerListeners();
            registerHooks();

            // [STEP 5] 深度插件接管
            getServer().getScheduler().runTaskLater(this, () -> {
                if (getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
                    LogUtil.info("检测到 UltimateShop，执行统一内核接管...");
                    UShopHookManager.execute(this);
                }
            }, 30L);

            this.fullyInitialized.set(true);
            sendConsole("<blue>┃ <green>系统状态: <white>宏观自适应演算内核已上线 (v0.9.4) <blue>┃");
            sendConsole("<gradient:aqua:blue>┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛</gradient>");

        } catch (Throwable e) {
            LogUtil.error("致命错误: 初始化拓扑崩溃", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // 直接触发关机序列
        shutdownSequence();
        instance = null;
    }

    /**
     * 执行完整的关机/资源回收序列
     */
    private void shutdownSequence() {
        // ✅ 核心原子锁：防止 onDisable 或其他潜在路径重复触发
        if (!fullyInitialized.getAndSet(false)) return;

        sendConsole("<yellow>[EcoBridge] 正在启动安全关机序列 (Atomic-Safe Shutdown)...");

        try {
            // 1. 优先还原第三方注入，防止在关闭过程中产生无效的跨插件回调
            UShopHookManager.revert();

            // 2. 解除网络与中间件关联
            if (RedisManager.getInstance() != null) RedisManager.getInstance().shutdown();
            HolidayManager.shutdown();

            // 3. 停止业务计算逻辑
            if (PricingManager.getInstance() != null) PricingManager.getInstance().shutdown();
            if (EconomyManager.getInstance() != null) EconomyManager.getInstance().shutdown();

            // 4. 持久化刷盘：确保所有异步日志和缓存数据落库
            if (AsyncLogger.getInstance() != null) AsyncLogger.getInstance().shutdown();
            LogUtil.info("正在执行最终数据同步 (Sync Cache Save)...");
            HotDataCache.saveAllSync();

            // 5. 物理隔离：安全释放 FFM 内存段与 Native 资源
            NativeBridge.shutdown();

            // 6. 线程池终结：等待虚拟线程任务完成或强制关闭
            terminateVirtualPool();

            // 7. 最后关闭数据库物理连接
            DatabaseManager.close();

            getServer().getScheduler().cancelTasks(this);
            sendConsole("<red>[EcoBridge] 所有系统资源已安全回收。逻辑屏障已关闭。");

        } catch (Exception e) {
            System.err.println("[EcoBridge] 关机序列异常，可能存在资源泄漏: " + e.getMessage());
        }
    }

    private void bootstrapInfrastructure() {
        saveDefaultConfig();
        LogUtil.init();
        DatabaseManager.init();
        AsyncLogger.init(this);
        HolidayManager.init();
        RedisManager.init(this);
    }

    private void terminateVirtualPool() {
        if (virtualExecutor != null && !virtualExecutor.isShutdown()) {
            virtualExecutor.shutdown();
            try {
                // 为正在执行的虚拟线程任务提供 5 秒缓冲
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
            sendConsole("<blue>┃ <red>致命错误: 未检测到必要依赖 CoinsEngine。 <blue>┃");
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
        
        // 刷新接管状态
        if (getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
            UShopHookManager.execute(this);
        }
        sendConsole("<green>[EcoBridge] 逻辑配置参数重载成功。");
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { ActivityCollector.updateSnapshot(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { ActivityCollector.removePlayer(event.getPlayer().getUniqueId()); }

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