package top.ellan.ecobridge.application.bootstrap;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.plugin.PluginManager;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.lifecycle.LifecycleComponent;
import top.ellan.ecobridge.application.lifecycle.LifecyclePhase;
import top.ellan.ecobridge.application.service.LimitManager;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.application.service.TransferManager;
import top.ellan.ecobridge.infrastructure.i18n.I18n;
import top.ellan.ecobridge.integration.platform.command.AdminCommand;
import top.ellan.ecobridge.integration.platform.command.DynamicCommand;
import top.ellan.ecobridge.integration.platform.command.TransferCommand;
import top.ellan.ecobridge.integration.platform.hook.EcoPlaceholderExpansion;
import top.ellan.ecobridge.integration.platform.hook.UltimateShopHook;
import top.ellan.ecobridge.integration.platform.hook.VaultEconomyBridge;
import top.ellan.ecobridge.integration.platform.listener.CacheListener;
import top.ellan.ecobridge.integration.platform.listener.CoinsEngineListener;
import top.ellan.ecobridge.util.LogUtil;
import top.ellan.ecobridge.util.UltimateShopImporter;

/** Registers platform-facing integrations: commands, listeners, and optional hooks. */
public final class PlatformIntegrationLifecycle implements LifecycleComponent {

  public static final PlatformIntegrationLifecycle INSTANCE = new PlatformIntegrationLifecycle();

  private PlatformIntegrationLifecycle() {}

  @Override
  public LifecyclePhase phase() {
    return LifecyclePhase.PLATFORM_INTEGRATION;
  }

  @Override
  public String componentName() {
    return "platform-integration";
  }

  public static void start(EcoBridge plugin, LimitManager limitManager) {
    registerCommands(plugin);
    registerListeners(plugin);
    registerHooks(plugin, limitManager);
    importUltimateShopDefaults(plugin);
  }

  public static void reload(EcoBridge plugin) {
    importUltimateShopDefaults(plugin);
  }

  private static void registerListeners(EcoBridge plugin) {
    PluginManager pm = plugin.getServer().getPluginManager();
    pm.registerEvents(plugin, plugin);
    pm.registerEvents(new CoinsEngineListener(plugin), plugin);
    pm.registerEvents(new CacheListener(), plugin);
  }

  private static void registerHooks(EcoBridge plugin, LimitManager limitManager) {
    PluginManager pm = plugin.getServer().getPluginManager();
    if (pm.isPluginEnabled("PlaceholderAPI")) {
      new EcoPlaceholderExpansion(plugin).register();
    }
    if (pm.isPluginEnabled("UltimateShop")) {
      pm.registerEvents(
          new UltimateShopHook(
              TransferManager.getInstance(), PricingManager.getInstance(), limitManager),
          plugin);
    }
    if (pm.isPluginEnabled("Vault")) {
      VaultEconomyBridge.register(plugin);
    }
  }

  private static void registerCommands(EcoBridge plugin) {
    try {
      Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
      commandMapField.setAccessible(true);
      CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

      commandMap.register(
          "ecobridge",
          new DynamicCommand(
              "ecopay",
              I18n.tr("platform.transfer_desc"),
              "/ecopay <player> <amount>",
              Arrays.asList("epay", "auditpay"),
              "ecobridge.command.transfer",
              new TransferCommand()));

      commandMap.register(
          "ecobridge",
          new DynamicCommand(
              "ecoadmin",
              I18n.tr("platform.admin_desc"),
              "/ecoadmin <shadow|reload|import>",
              Arrays.asList("eb", "eco"),
              "ecobridge.admin",
              new AdminCommand()));

      LogUtil.info(I18n.tr("platform.commands_registered"));

    } catch (Exception e) {
      LogUtil.error(I18n.tr("platform.commands_register_failed"), e);
    }
  }

  private static void importUltimateShopDefaults(EcoBridge plugin) {
    if (!plugin.getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
      return;
    }
    double defaultLambda = plugin.getConfig().getDouble("economy.default-lambda", 0.002);
    UltimateShopImporter.runImport(defaultLambda);
  }
}
