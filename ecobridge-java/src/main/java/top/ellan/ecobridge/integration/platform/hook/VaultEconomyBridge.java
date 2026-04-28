package top.ellan.ecobridge.integration.platform.hook;

import java.util.List;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.TransferManager;
import top.ellan.ecobridge.util.LogUtil;

/**
 * Vault Economy Bridge (v2.0)
 *
 * <p>Registers EcoBridge as a Vault economy provider, intercepting all Vault-based economy calls
 * and routing them through EcoBridge's transfer regulator and CoinsEngine's actual balance store.
 *
 * <p>This ensures that ANY plugin using the Vault API (essentials, quests, jobs, etc.)
 * automatically benefits from EcoBridge's tax calculation, transfer limits, puppet detection, and
 * wealth-gap regulation.
 */
@SuppressWarnings("deprecation")
public class VaultEconomyBridge implements Economy {

  private final EcoBridge plugin;
  private final ExcellentCurrency primaryCurrency;

  private static ExcellentEconomyAPI eeApi() {
    RegisteredServiceProvider<ExcellentEconomyAPI> p =
        Bukkit.getServicesManager().getRegistration(ExcellentEconomyAPI.class);
    return p != null ? p.getProvider() : null;
  }

  public VaultEconomyBridge(EcoBridge plugin) {
    this.plugin = plugin;
    String currencyId = plugin.getConfig().getString("economy.currency-id", "coins");
    this.primaryCurrency = eeApi() != null ? eeApi().getCurrency(currencyId) : null;
  }

  /** Register this bridge as the primary Vault economy provider. */
  public static void register(EcoBridge plugin) {
    if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
      LogUtil.info("Vault not detected — skipping Vault economy bridge registration.");
      return;
    }

    VaultEconomyBridge bridge = new VaultEconomyBridge(plugin);
    Bukkit.getServicesManager().register(Economy.class, bridge, plugin, ServicePriority.High);

    LogUtil.info(
        "<gradient:gold:yellow>Vault economy bridge registered — "
            + "all Vault transactions now routed through EcoBridge regulator");
  }

  @Override
  public boolean hasBankSupport() {
    return false;
  }

  // ==================== Balance queries (read directly from CoinsEngine) ====================

  @Override
  public double getBalance(String playerName) {
    OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
    return getBalance(player);
  }

  @Override
  public double getBalance(OfflinePlayer player) {
    return getBalance(player.getUniqueId());
  }

  @Override
  public double getBalance(String playerName, String world) {
    return getBalance(playerName);
  }

  @Override
  public double getBalance(OfflinePlayer player, String world) {
    return getBalance(player);
  }

  private double getBalance(UUID uuid) {
    if (primaryCurrency == null) return 0.0;
    Player p = Bukkit.getPlayer(uuid);
    if (p != null) return eeApi().getBalance(p, primaryCurrency);
    try {
      return eeApi().getBalanceAsync(uuid, primaryCurrency).join();
    } catch (Exception e) {
      return 0.0;
    }
  }

  @Override
  public boolean has(String playerName, double amount) {
    return getBalance(playerName) >= amount;
  }

  @Override
  public boolean has(OfflinePlayer player, double amount) {
    return getBalance(player) >= amount;
  }

  @Override
  public boolean has(String playerName, String world, double amount) {
    return has(playerName, amount);
  }

  @Override
  public boolean has(OfflinePlayer player, String world, double amount) {
    return has(player, amount);
  }

  // ==================== Transactions (routed through EcoBridge TransferManager)
  // ====================

  @Override
  public EconomyResponse withdrawPlayer(String playerName, double amount) {
    OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
    return withdrawPlayer(player, amount);
  }

  @Override
  public EconomyResponse depositPlayer(String playerName, double amount) {
    OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
    return depositPlayer(player, amount);
  }

  @Override
  public EconomyResponse withdrawPlayer(String playerName, String world, double amount) {
    return withdrawPlayer(playerName, amount);
  }

  @Override
  public EconomyResponse depositPlayer(String playerName, String world, double amount) {
    return depositPlayer(playerName, amount);
  }

  @Override
  public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
    if (player == null || amount < 0) {
      return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid arguments");
    }
    if (primaryCurrency == null) {
      return new EconomyResponse(
          0, 0, EconomyResponse.ResponseType.FAILURE, "No currency configured");
    }

    double balance = getBalance(player);
    if (balance < amount) {
      return new EconomyResponse(
          0, balance, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
    }

    // Route through EcoBridge transfer regulator if available
    TransferManager tm = TransferManager.getInstance();
    if (tm != null && player.isOnline()) {
      var result = tm.previewTransaction(player.getPlayer(), amount);
      if (result.isBlocked()) {
        return new EconomyResponse(
            0,
            balance,
            EconomyResponse.ResponseType.FAILURE,
            "Blocked by regulator (Code: " + result.warningCode() + ")");
      }
      double tax = result.finalTax();
      double netWithdraw = amount + tax;
      eeApi().withdraw(player.getPlayer(), primaryCurrency, netWithdraw);
      return new EconomyResponse(
          amount,
          balance - netWithdraw,
          EconomyResponse.ResponseType.SUCCESS,
          tax > 0 ? "Tax: " + tax : "");
    }

    // Direct withdrawal (no regulator available)
    eeApi().withdraw(player.getPlayer(), primaryCurrency, amount);
    return new EconomyResponse(amount, balance - amount, EconomyResponse.ResponseType.SUCCESS, "");
  }

  @Override
  public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
    return withdrawPlayer(player, amount);
  }

  @Override
  public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
    if (player == null || amount < 0) {
      return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid arguments");
    }
    if (primaryCurrency == null) {
      return new EconomyResponse(
          0, 0, EconomyResponse.ResponseType.FAILURE, "No currency configured");
    }

    eeApi().deposit(player.getPlayer(), primaryCurrency, amount);
    double newBalance = getBalance(player);
    return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, "");
  }

  @Override
  public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
    return depositPlayer(player, amount);
  }

  // ==================== Bank methods (not supported — return empty) ====================

  @Override
  public EconomyResponse createBank(String name, String player) {
    return new EconomyResponse(
        0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported");
  }

  @Override
  public EconomyResponse createBank(String name, OfflinePlayer player) {
    return new EconomyResponse(
        0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported");
  }

  @Override
  public EconomyResponse deleteBank(String name) {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
  }

  @Override
  public EconomyResponse bankBalance(String name) {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
  }

  @Override
  public EconomyResponse bankHas(String name, double amount) {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
  }

  @Override
  public EconomyResponse bankWithdraw(String name, double amount) {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
  }

  @Override
  public EconomyResponse bankDeposit(String name, double amount) {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
  }

  @Override
  public EconomyResponse isBankOwner(String name, String playerName) {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
  }

  @Override
  public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
  }

  @Override
  public EconomyResponse isBankMember(String name, String playerName) {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
  }

  @Override
  public EconomyResponse isBankMember(String name, OfflinePlayer player) {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
  }

  @Override
  public List<String> getBanks() {
    return List.of();
  }

  // ==================== Metadata ====================

  @Override
  public boolean createPlayerAccount(String playerName) {
    return true;
  }

  @Override
  public boolean createPlayerAccount(OfflinePlayer player) {
    return true;
  }

  @Override
  public boolean createPlayerAccount(String playerName, String world) {
    return true;
  }

  @Override
  public boolean createPlayerAccount(OfflinePlayer player, String world) {
    return true;
  }

  @Override
  public String currencyNamePlural() {
    return primaryCurrency != null ? primaryCurrency.getName() : "coins";
  }

  @Override
  public String currencyNameSingular() {
    return primaryCurrency != null ? primaryCurrency.getName() : "coin";
  }

  @Override
  public String format(double amount) {
    return String.format("%.2f", amount);
  }

  @Override
  public int fractionalDigits() {
    return 2;
  }

  @Override
  public boolean hasAccount(String playerName) {
    return true;
  }

  @Override
  public boolean hasAccount(OfflinePlayer player) {
    return true;
  }

  @Override
  public boolean hasAccount(String playerName, String world) {
    return true;
  }

  @Override
  public boolean hasAccount(OfflinePlayer player, String world) {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return primaryCurrency != null;
  }

  @Override
  public String getName() {
    return "EcoBridge";
  }
}
