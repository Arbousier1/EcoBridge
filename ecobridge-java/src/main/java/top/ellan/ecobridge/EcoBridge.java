package top.ellan.ecobridge;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.cache.HotDataCache;
import top.ellan.ecobridge.command.TransferCommand;
import top.ellan.ecobridge.database.DatabaseManager; // [新增] 引入数据库管理器
import top.ellan.ecobridge.listener.CacheListener;
import top.ellan.ecobridge.listener.CoinsEngineListener;
import top.ellan.ecobridge.listener.CommandInterceptor;
import top.ellan.ecobridge.listener.TradeListener;
import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.manager.PricingManager;
import top.ellan.ecobridge.manager.TransferManager;
import top.ellan.ecobridge.manager.EconomicStateManager;
import top.ellan.ecobridge.network.RedisManager;
import top.ellan.ecobridge.storage.AsyncLogger;
import top.ellan.ecobridge.util.HolidayManager;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EcoBridge v0.8.9 - 工业级经济桥接内核
 * <p>
 * 更新日志:
 * 1. 数据库层重构：基础设施(DatabaseManager)与业务逻辑(TransactionDao)分离。
 * 2. Native层重构：引入 NativeLoader 解决生命周期管理与热重载资源释放问题。
 */
public final class EcoBridge extends JavaPlugin {

    private static EcoBridge instance;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ExecutorService virtualExecutor;
    private final AtomicBoolean fullyInitialized = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;

        // 1. 初始化并发心脏：Java 25 虚拟线程执行器
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 2. 引导基础架构
        try {
            bootstrapInfrastructure();
        } catch (Exception e) {
            getLogger().severe("基础架构引导失败，系统强制挂起: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        printBanner();

        // 3. 环境与依赖校验
        if (!verifyDependencies()) return;

        // 4. 组件加载拓扑
        try {
            // A. Native 物理核心加载 (自动处理 Loader 与 ABI 校验)
            NativeBridge.init(this);

            // B. 逻辑管理层启动
            EconomyManager.init(this);
            EconomicStateManager.init(this);
            PricingManager.init(this);
            TransferManager.init(this);

            // C. 驱动层注册
            registerCommands();
            registerListeners();

            this.fullyInitialized.set(true);
            sendConsole("<blue>┃ <green>系统状态: <white>物理演算核心已进入实时同步状态 (v0.8.9) <blue>┃");
            sendConsole("<gradient:aqua:blue>┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛</gradient>");

        } catch (Throwable e) {
            LogUtil.error("致命错误: 插件组件初始化链条中断", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        sendConsole("<yellow>[EcoBridge] 正在启动安全关机序列 (Panic-Safe Shutdown)...");

        // 1. 拦截新请求
        this.fullyInitialized.set(false);

        // 2. 环境解绑 & 网络断开
        if (RedisManager.getInstance() != null) {
            RedisManager.getInstance().shutdown();
        }
        HolidayManager.shutdown();

        // 3. 执行核心数据落盘 (关键顺序：驱逐缓存 -> 刷入 SQL -> 关闭持久化连接)
        shutdownPersistenceLayer();

        // 4. 物理隔离：切断 FFI 入口，释放 Native 资源 (必须在持久化之后)
        NativeBridge.shutdown();

        // 5. 资源清理：关闭虚拟线程池
        terminateVirtualPool();

        getServer().getScheduler().cancelTasks(this);

        instance = null;
        sendConsole("<red>[EcoBridge] 插件已安全卸载。内存屏障已关闭，物理资源已安全释放。");
    }

    private void bootstrapInfrastructure() {
        saveDefaultConfig();
        LogUtil.init();

        // [修复] 使用 DatabaseManager 初始化连接池与表结构
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
        // 这里的 HotDataCache.saveAllSync 内部会调用 TransactionDao，只要 DatabaseManager 没关就没问题
        HotDataCache.saveAllSync();

        // [修复] 关闭数据库连接池与基础设施
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
        pm.registerEvents(new CoinsEngineListener(this), this);
        pm.registerEvents(new CommandInterceptor(this), this);
        pm.registerEvents(new TradeListener(this), this);
        pm.registerEvents(new CacheListener(), this);
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

        sendConsole("<green>[EcoBridge] 逻辑参数重载成功。Native 内存布局保持锁定。");
    }

    public static EcoBridge getInstance() { return instance; }
    public ExecutorService getVirtualExecutor() { return virtualExecutor; }
    public static MiniMessage getMiniMessage() { return MM; }
    public boolean isFullyInitialized() { return fullyInitialized.get(); }

    private void printBanner() {
        String version = getPluginMeta().getVersion();
        sendConsole("<gradient:aqua:blue>┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓</gradient>");
        sendConsole("<blue>┃ <green>EcoBridge <white>v" + version + " <gray>| <aqua>Java 25 (Loom/FFM) <blue>┃");
    }

    private void sendConsole(String msg, TagResolver... resolvers) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }
}