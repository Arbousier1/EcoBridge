package top.ellan.ecobridge;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.ellan.ecobridge.api.EcoLimitAPI;
import top.ellan.ecobridge.application.bootstrap.AsmIntegrationLifecycle;
import top.ellan.ecobridge.application.bootstrap.CoreServiceLifecycle;
import top.ellan.ecobridge.application.bootstrap.InfrastructureLifecycle;
import top.ellan.ecobridge.application.bootstrap.PlatformIntegrationLifecycle;
import top.ellan.ecobridge.application.bootstrap.ReloadLifecycle;
import top.ellan.ecobridge.application.bootstrap.ShutdownLifecycle;
import top.ellan.ecobridge.application.lifecycle.LifecycleCatalog;
import top.ellan.ecobridge.application.service.ItemConfigManager;
import top.ellan.ecobridge.application.service.LimitManager;
import top.ellan.ecobridge.infrastructure.i18n.I18n;
import top.ellan.ecobridge.integration.platform.console.StartupBanner;
import top.ellan.ecobridge.integration.platform.listener.CommandHijacker;
import top.ellan.ecobridge.infrastructure.persistence.storage.ActivityCollector;
import top.ellan.ecobridge.util.ConfigMigrator;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main plugin class.
 * Coordinates startup, dependency checks, hooks, and shutdown.
 */
public final class EcoBridge extends JavaPlugin implements Listener {

    private static volatile EcoBridge instance;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static LimitManager limitAPI;

    private ExecutorService virtualExecutor;
    private final AtomicBoolean fullyInitialized = new AtomicBoolean(false);
    

    // Shadow mode keeps observation/audit behaviors without strict blocking.
    private final AtomicBoolean shadowMode = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;


        // Runtime executor for async plugin tasks.
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();


        try {
            I18n.init(this);
            LogUtil.init();
            LogUtil.info("Lifecycle startup phases: " + LifecycleCatalog.startupPhaseFlow());
            LogUtil.info("Lifecycle startup components: " + LifecycleCatalog.startupComponentFlow());
            

            // Ensure data folder and default config exist.
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            if (!new java.io.File(getDataFolder(), "config.yml").exists()) {
                saveDefaultConfig();
            }

            // Migrate config from older versions
            ConfigMigrator.checkAndMigrate(this);

            // Initialize dedicated items.yml loader.
            ItemConfigManager.init(this);
            
            // Start infrastructure and activity heartbeat.
            bootstrapInfrastructure();
            ActivityCollector.startHeartbeat(this);
            

            // Install ASM redirection for UltimateShop hook points.
            AsmIntegrationLifecycle.install();
            
        } catch (Exception e) {
            getLogger().severe(I18n.tr("startup.early_error", e.getMessage()));
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }


        // Abort startup if required dependencies are missing.
        if (!verifyDependencies()) return;


        try {

            // Start core services then register platform integrations.
            limitAPI = CoreServiceLifecycle.start(this);
            PlatformIntegrationLifecycle.start(this, limitAPI);


            // Hijack currency commands for unified eco behavior.
            CommandHijacker hijacker = new CommandHijacker(this);
            hijacker.hijackAllCurrencies();
            hijacker.hijackShopCommands();

            this.fullyInitialized.set(true);
            

            // Print startup summary banner.
            StartupBanner.print(this);

        } catch (Throwable e) {
            LogUtil.error(I18n.tr("startup.init_error"), e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        ShutdownLifecycle.stop(this, fullyInitialized, virtualExecutor);
        instance = null;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    public static EcoBridge getInstance() {
        EcoBridge inst = instance;
        if (inst == null) throw new IllegalStateException("EcoBridge has not been initialized or is shutting down.");
        return inst;
    }

    public static EcoBridge getInstanceOrNull() {
        return instance;
    }

    public static EcoLimitAPI getLimitAPI() {
        return limitAPI;
    }

    public boolean isShadowMode() {
        return shadowMode.get();
    }

    public void setShadowMode(boolean enabled) {
        this.shadowMode.set(enabled);
    }
    // ============================== lifecycle helpers ==============================
    private void bootstrapInfrastructure() {
        InfrastructureLifecycle.start(this);
    }

    private boolean verifyDependencies() {
        var pm = Bukkit.getPluginManager();
        if (!pm.isPluginEnabled("CoinsEngine")) {
            sendConsole("<red>" + I18n.tr("startup.dependency_missing") + "</red>");
            pm.disablePlugin(this);
            return false;
        }
        return true;
    }

    public void reload() {
        I18n.reload(this);
        ReloadLifecycle.reload(this, limitAPI);
        StartupBanner.print(this);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { ActivityCollector.updateSnapshot(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { ActivityCollector.removePlayer(event.getPlayer().getUniqueId()); }

    public ExecutorService getVirtualExecutor() { return virtualExecutor; }
    public static MiniMessage getMiniMessage() { return MM; }
    public boolean isFullyInitialized() { return fullyInitialized.get(); }

    private void sendConsole(String msg, TagResolver... resolvers) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }
}
