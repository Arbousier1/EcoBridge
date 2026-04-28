package top.ellan.ecobridge.integration.platform.hook;

import cn.superiormc.ultimateshop.api.ItemFinishTransactionEvent;
import cn.superiormc.ultimateshop.api.ItemPreTransactionEvent;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.AbstractSingleThing;
import cn.superiormc.ultimateshop.objects.items.GiveResult;
import cn.superiormc.ultimateshop.objects.items.TakeResult;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import top.ellan.ecobridge.application.service.EconomicStateManager;
import top.ellan.ecobridge.application.service.EconomyManager;
import top.ellan.ecobridge.application.service.LimitManager;
import top.ellan.ecobridge.application.service.PlayerMarketPolicyService;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.application.service.TransferManager;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.infrastructure.ffi.model.NativeTransferResult;
import top.ellan.ecobridge.infrastructure.persistence.storage.AsyncLogger;
import top.ellan.ecobridge.integration.platform.compat.UltimateShopCompat;

/** Runtime hook for UltimateShop transaction events. */
public class UltimateShopHook implements Listener {

  private final TransferManager transferManager;
  private final PricingManager pricingManager;
  private final LimitManager limitManager;

  // [v2.0] Expanded economy plugin detection — covers all known types + heuristics
  private static final java.util.Set<String> KNOWN_ECONOMY_TYPES =
      java.util.Set.of(
          "Vault",
          "PlayerPoints",
          "CMI",
          "CoinsEngine",
          "Treasury",
          "EconomyAPI",
          "BedrockEconomy",
          "XP",
          "ItemsAdder",
          "MythicMobs");

  public UltimateShopHook(
      TransferManager transferManager, PricingManager pricingManager, LimitManager limitManager) {
    this.transferManager = transferManager;
    this.pricingManager = pricingManager;
    this.limitManager = limitManager;
  }

  @EventHandler
  public void onShopTransaction(ItemPreTransactionEvent event) {
    boolean isBuy = UltimateShopCompat.resolveBuyFlag(event);
    int amount = UltimateShopCompat.resolveAmount(event);

    ObjectItem item = resolveItemFromEvent(event);
    Player player = event.getPlayer();
    if (item == null || player == null) return;

    String productId = item.getProduct();
    String shopId = item.getShop();

    double unitPrice;
    if (isBuy) {
      unitPrice = processBuy(event, player, shopId, productId, amount);
    } else {
      unitPrice = processSell(event, player, shopId, productId, amount);
    }

    // Record settlement immediately — ItemPreTransactionEvent HAS getTakeResult/getGiveResult
    if (unitPrice > 0) {
      recordSettlement(event, player, shopId, productId, amount, unitPrice, isBuy);
      feedRustKernel(shopId, productId, unitPrice);
    }
  }

  @EventHandler
  public void onShopTransactionFinish(ItemFinishTransactionEvent event) {
    ObjectItem item = resolveItemFromEvent(event);
    Player player = event.getPlayer();
    if (item == null || player == null) return;

    boolean isBuy = UltimateShopCompat.resolveBuyFlag(event);
    int amount = UltimateShopCompat.resolveAmount(event);
    String productId = item.getProduct();
    String shopId = item.getShop();

    if (isBuy) {
      EconomicStateManager.getInstance().recordPurchase(player, shopId, productId, amount);
    } else {
      EconomicStateManager.getInstance().recordSale(player, shopId, productId, amount);
      PlayerMarketPolicyService policy = PlayerMarketPolicyService.getInstance();
      if (policy != null) {
        policy.recordSale(
            player.getUniqueId(), PricingManager.toMarketKey(shopId, productId), amount);
      }
    }
  }

  /**
   * @return the unit price used, or 0 if cancelled
   */
  private double processBuy(
      ItemPreTransactionEvent event, Player player, String shopId, String productId, int amount) {
    if (limitManager.isBlockedByDynamicLimit(player.getUniqueId(), productId, amount)) {
      simulateCancellation(event, player, "Dynamic buy limit reached");
      return 0;
    }

    double dynamicUnitPrice = pricingManager.calculateBuyPrice(shopId, productId);
    if (dynamicUnitPrice <= 0) return 0;

    double totalBasePrice = dynamicUnitPrice * amount;
    NativeTransferResult rustResult = transferManager.previewTransaction(player, totalBasePrice);

    if (rustResult.isBlocked()) {
      simulateCancellation(
          event, player, "Transfer blocked by regulator (Code: " + rustResult.warningCode() + ")");
      return 0;
    }

    double finalTax = rustResult.finalTax();
    double finalCost = totalBasePrice + finalTax;

    TakeResult originalTake = event.getTakeResult();
    boolean modified = modifyMoneyInResult(originalTake.getResultMap(), finalCost);

    if (modified && finalTax > 0) {
      String taxMsg = String.format("(Base: %.1f | Tax: %.1f)", totalBasePrice, finalTax);
      player.sendActionBar(Component.text(taxMsg));
    }
    return dynamicUnitPrice;
  }

  /**
   * @return the unit price used, or 0 if cancelled
   */
  private double processSell(
      ItemPreTransactionEvent event, Player player, String shopId, String productId, int amount) {
    if (limitManager.isBlockedBySellLimit(player.getUniqueId(), productId, amount)) {
      simulateCancellation(event, player, "Dynamic sell limit reached");
      return 0;
    }

    if (limitManager.isBlockedByPlayerQuota(player.getUniqueId(), shopId, productId, amount)) {
      simulateCancellation(event, player, "Player quota pool exhausted");
      return 0;
    }

    double dynamicUnitPrice =
        pricingManager.calculateSellPriceForPlayer(player.getUniqueId(), shopId, productId);
    if (dynamicUnitPrice <= 0) return 0;

    double finalPayout = dynamicUnitPrice * amount;
    GiveResult originalGive = event.getGiveResult();
    modifyMoneyInResult(originalGive.getResultMap(), finalPayout);
    return dynamicUnitPrice;
  }

  private ObjectItem resolveItemFromEvent(Object event) {
    try {
      Object direct = event.getClass().getMethod("getItem").invoke(event);
      if (direct instanceof ObjectItem objectItem) return objectItem;
    } catch (Exception ignored) {
    }

    try {
      Field field = event.getClass().getDeclaredField("item");
      field.setAccessible(true);
      Object reflected = field.get(event);
      if (reflected instanceof ObjectItem objectItem) return objectItem;
    } catch (Exception ignored) {
    }

    return null;
  }

  private void simulateCancellation(ItemPreTransactionEvent event, Player player, String reason) {
    if (event.getTakeResult() != null && event.getTakeResult().getResultMap() != null) {
      event.getTakeResult().getResultMap().clear();
    }
    if (event.getGiveResult() != null && event.getGiveResult().getResultMap() != null) {
      event.getGiveResult().getResultMap().clear();
    }

    String color = "&c";
    try {
      ObjectItem item = event.getItem();
      if (item != null) color = limitManager.getMarketColor(item.getProduct());
    } catch (Exception ignored) {
    }

    player.sendMessage(color.replace('<', '&').replace(">", "") + "Transaction blocked: " + reason);
    player.playSound(player.getLocation(), "entity.villager.no", 1f, 1f);
  }

  private boolean modifyMoneyInResult(
      Map<AbstractSingleThing, BigDecimal> resultMap, double newAmount) {
    if (resultMap == null) return false;
    boolean modified = false;

    for (Map.Entry<AbstractSingleThing, BigDecimal> entry : resultMap.entrySet()) {
      AbstractSingleThing thing = entry.getKey();
      if (isEconomyEntry(thing)) {
        entry.setValue(BigDecimal.valueOf(newAmount));
        modified = true;
      }
    }
    return modified;
  }

  /** [v2.0] Record settlement from pre-event (which HAS getTakeResult/getGiveResult). */
  private void recordSettlement(
      ItemPreTransactionEvent event,
      Player player,
      String shopId,
      String productId,
      int amount,
      double unitPrice,
      boolean isBuy) {
    double total =
        isBuy
            ? resolveEconomyTotal(event.getTakeResult())
            : resolveEconomyTotal(event.getGiveResult());
    if (!Double.isFinite(total) || total <= 0.0) {
      total = unitPrice * amount;
    }

    EconomyManager economyManager = EconomyManager.getInstance();
    if (economyManager != null) {
      economyManager.recordTradeVolume(total);
    }

    String marketKey = PricingManager.toMarketKey(shopId, productId);
    double signedAmount = isBuy ? -total : total;
    AsyncLogger.log(
        player.getUniqueId(),
        signedAmount,
        0.0,
        System.currentTimeMillis(),
        String.format(
            "TRADE_DETAIL:%s:%s:player=%s:unitPrice=%.4f:qty=%d:total=%.2f",
            isBuy ? "BUY" : "SELL", marketKey, player.getUniqueId(), unitPrice, amount, total));
  }

  private double resolveEconomyTotal(Object resultObj) {
    if (resultObj == null) return 0.0;
    Object mapObj = invokeNoArg(resultObj, "getResultMap");
    if (!(mapObj instanceof Map<?, ?> map) || map.isEmpty()) return 0.0;
    double total = 0.0;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof AbstractSingleThing thing) || !isEconomyEntry(thing))
        continue;
      if (entry.getValue() instanceof BigDecimal d) total += Math.abs(d.doubleValue());
      else if (entry.getValue() instanceof Number n) total += Math.abs(n.doubleValue());
    }
    return total;
  }

  /** [v2.0] Detect economy entries — matches UltimateShop's ThingType classification. */
  private boolean isEconomyEntry(AbstractSingleThing thing) {
    ConfigurationSection section = thing.getSingleSection();
    if (section == null) return false;

    // Match UltimateShop's AbstractSingleThing.initType():
    // HOOK_ECONOMY uses "economy-plugin", VANILLA_ECONOMY uses "economy-type"
    String ecoPlugin = section.getString("economy-plugin", "");
    if (!ecoPlugin.isEmpty() && KNOWN_ECONOMY_TYPES.contains(ecoPlugin)) return true;

    String ecoType = section.getString("economy-type", "");
    return !ecoType.isEmpty() && KNOWN_ECONOMY_TYPES.contains(ecoType);
  }

  /** [v2.0] Feed transaction price into Rust GARCH/Kalman state tracking. */
  private void feedRustKernel(String shopId, String productId, double currentPrice) {
    if (!NativeBridge.isLoaded() || currentPrice <= 0) return;
    try {
      String marketKey = PricingManager.toMarketKey(shopId, productId);
      NativeBridge.garchUpdate(marketKey, 0.0);
      NativeBridge.kalmanFilter(marketKey, currentPrice, 3600.0);
    } catch (Exception ignored) {
    }
  }

  private Object invokeNoArg(Object target, String methodName) {
    if (target == null) return null;
    try {
      Method method = target.getClass().getMethod(methodName);
      return method.invoke(target);
    } catch (Exception ignored) {
      try {
        Method declared = target.getClass().getDeclaredMethod(methodName);
        declared.setAccessible(true);
        return declared.invoke(target);
      } catch (Exception ignoredAgain) {
        return null;
      }
    }
  }
}
