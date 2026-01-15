package top.ellan.ecobridge;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.cache.HotDataCache;
import top.ellan.ecobridge.command.TransferCommand;
import top.ellan.ecobridge.database.TransactionDao;
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

/**
 * EcoBridge v0.8.2 - 工业级经济桥接内核 (Java 25 加固版)
 * 职责：管控系统拓扑，维护跨语言内存屏障，编排高性能异步 IO 链路。
 * <p>
 * v0.8 更新：
 * 1. 集成 Redis 分布式同步层。
 * 2. 增强关闭序列的安全性 (Network -> Persistence -> Native)。
 */
public final class EcoBridge extends JavaPlugin {

    private static EcoBridge instance;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ExecutorService virtualExecutor;
    private boolean fullyInitialized = false;

    @Override
    public void onEnable() {
        instance = this;

        // 1. 初始化并发心脏：Java 25 虚拟线程执行器
        // 确保所有阻塞式 I/O (SQL, DuckDB, Redis) 不会造成物理线程饥饿
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 2. 引导基础架构 (引导顺序：日志 -> 数据库 -> 节假日/Redis -> 物理引擎)
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
            // A. Native 物理核心加载 (ABI v0.8.0 Check)
            // 此时会自动建立 DuckDB 连接并初始化路径屏障
            NativeBridge.init(this);

            // B. 逻辑管理层启动 (含 CAS 原子缓存)
            EconomyManager.init(this);
            EconomicStateManager.init(this);
            PricingManager.init(this);
            TransferManager.init(this);

            // C. 驱动层注册 (指令与事件监听器)
            registerCommands();
            registerListeners();

            this.fullyInitialized = true;
            sendConsole("<blue>┃ <green>系统状态: <white>物理演算核心已进入实时同步状态 (v0.8.2) <blue>┃");
            sendConsole("<gradient:aqua:blue>┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛</gradient>");

        } catch (Throwable e) {
            LogUtil.error("致命错误: 插件组件初始化链条中断", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        sendConsole("<yellow>[EcoBridge] 正在启动安全关机序列 (Panic-Safe Shutdown)...");

        // 1. 拦截新请求：停止业务流入口
        this.fullyInitialized = false;

        // 2. 环境解绑 & 网络断开
        // [关键] 先断开 Redis，防止新消息进入导致 DuckDB/SQL 写入报错
        if (RedisManager.getInstance() != null) {
            RedisManager.getInstance().shutdown();
        }
        HolidayManager.shutdown();

        // 3. 执行核心数据落盘 (关键顺序：驱逐缓存 -> 刷入 SQL -> 关闭 DuckDB)
        shutdownPersistenceLayer();

        // 4. 物理隔离：释放 FFM 堆外 Arena 内存
        NativeBridge.shutdown();

        // 5. 资源清理：关闭虚拟线程池
        terminateVirtualPool();
        
        getServer().getScheduler().cancelTasks(this);
        
        instance = null;
        sendConsole("<red>[EcoBridge] 插件已安全卸载。内存屏障已关闭，物理资源已安全释放。");
    }

    /**
     * 基础架构引导：核心组件初始化
     */
    private void bootstrapInfrastructure() {
        saveDefaultConfig();
        LogUtil.init();
        
        // 初始化 SQL 连接池
        TransactionDao.init();
        
        // 初始化 DuckDB 异步日志
        AsyncLogger.init(this);
        
        // 辅助服务初始化
        HolidayManager.init();
        
        // [v0.8] 初始化 Redis 分布式同步层
        // 即使配置未启用，这里调用也是安全的 (内部会检查配置)
        RedisManager.init(this);
    }

    /**
     * 持久化层安全下线逻辑
     * 确保热路径中的 PlayerData (CAS 位) 完整写回 MySQL
     */
    private void shutdownPersistenceLayer() {
        // 先停止 DuckDB 写入管线
        if (AsyncLogger.getInstance() != null) {
            AsyncLogger.getInstance().shutdown();
        }

        // 强制同步刷盘：将内存中基于 AtomicLong 的余额写回 SQL
        LogUtil.info("正在执行热数据终点刷盘...");
        HotDataCache.saveAllSync();

        // 最后关闭 SQL 连接池
        TransactionDao.close();
    }

    /**
     * 优雅关闭虚拟线程池，防止任务中途夭折
     */
    private void terminateVirtualPool() {
        if (virtualExecutor != null && !virtualExecutor.isShutdown()) {
            virtualExecutor.shutdown();
            try {
                // 给虚拟线程 5 秒处理残余任务 (如未完成的 SQL 写入)
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
        
        // Redis 不支持热重载连接池，建议重启服务器以应用 Redis 配置变更
        
        sendConsole("<green>[EcoBridge] 逻辑参数重载成功。Native 内存布局保持锁定。");
    }

    // --- 全局单例与 API ---

    public static EcoBridge getInstance() { return instance; }

    public ExecutorService getVirtualExecutor() { return virtualExecutor; }
    
    public static MiniMessage getMiniMessage() { return MM; }

    public boolean isFullyInitialized() { return fullyInitialized; }

    private void printBanner() {
        String version = getPluginMeta().getVersion();
        sendConsole("<gradient:aqua:blue>┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓</gradient>");
        sendConsole("<blue>┃ <green>EcoBridge <white>v" + version + " <gray>| <aqua>Java 25 (Loom/FFM) <blue>┃");
    }

    private void sendConsole(String msg, TagResolver... resolvers) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }
}