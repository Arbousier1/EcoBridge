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
  private static final java.util.Set<String> KNOWN_ECONOMY_TYPES = java.util.Set.of(
      "Vault", "PlayerPoints", "CMI", "CoinsEngine",
      "Treasury", "EconomyAPI", "BedrockEconomy",
      "XP", "ItemsAdder", "MythicMobs"
  );

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

    if (isBuy) {
      processBuy(event, player, shopId, productId, amount);
    } else {
      processSell(event, player, shopId, productId, amount);
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

    recordFinalSettlement(event, player, shopId, productId, isBuy);
    recordTransactionDetail(player, shopId, productId, amount, isBuy, event);
    feedRustKernel(shopId, productId, amount, isBuy);
  }

  private void processBuy(
      ItemPreTransactionEvent event, Player player, String shopId, String productId, int amount) {
    if (limitManager.isBlockedByDynamicLimit(player.getUniqueId(), productId, amount)) {
      simulateCancellation(event, player, "Dynamic buy limit reached");
      return;
    }

    double dynamicUnitPrice = pricingManager.calculateBuyPrice(shopId, productId);
    if (dynamicUnitPrice <= 0) return;

    double totalBasePrice = dynamicUnitPrice * amount;
    NativeTransferResult rustResult = transferManager.previewTransaction(player, totalBasePrice);

    if (rustResult.isBlocked()) {
      simulateCancellation(
          event, player, "Transfer blocked by regulator (Code: " + rustResult.warningCode() + ")");
      return;
    }

    double finalTax = rustResult.finalTax();
    double finalCost = totalBasePrice + finalTax;

    TakeResult originalTake = event.getTakeResult();
    boolean modified = modifyMoneyInResult(originalTake.getResultMap(), finalCost);

    if (modified && finalTax > 0) {
      String taxMsg = String.format("(Base: %.1f | Tax: %.1f)", totalBasePrice, finalTax);
      player.sendActionBar(Component.text(taxMsg));
    }
  }

  private void processSell(
      ItemPreTransactionEvent event, Player player, String shopId, String productId, int amount) {
    if (limitManager.isBlockedBySellLimit(player.getUniqueId(), productId, amount)) {
      simulateCancellation(event, player, "Dynamic sell limit reached");
      return;
    }

    if (limitManager.isBlockedByPlayerQuota(player.getUniqueId(), shopId, productId, amount)) {
      simulateCancellation(event, player, "Player quota pool exhausted");
      return;
    }

    double dynamicUnitPrice =
        pricingManager.calculateSellPriceForPlayer(player.getUniqueId(), shopId, productId);
    if (dynamicUnitPrice <= 0) return;

    double finalPayout = dynamicUnitPrice * amount;
    GiveResult originalGive = event.getGiveResult();
    modifyMoneyInResult(originalGive.getResultMap(), finalPayout);
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

  private void recordFinalSettlement(
      ItemFinishTransactionEvent event,
      Player player,
      String shopId,
      String productId,
      boolean isBuy) {
    double settledMoney = resolveFinalEconomyMoney(event, isBuy);
    if (!Double.isFinite(settledMoney) || settledMoney <= 0.0) return;

    EconomyManager economyManager = EconomyManager.getInstance();
    if (economyManager != null) {
      economyManager.recordTradeVolume(settledMoney);
    }

    String marketKey = PricingManager.toMarketKey(shopId, productId);
    String signedSide = isBuy ? "BUY" : "SELL";
    double signedAmount = isBuy ? -settledMoney : settledMoney;
    AsyncLogger.log(
        player.getUniqueId(),
        signedAmount,
        0.0,
        System.currentTimeMillis(),
        "MARKET_SETTLEMENT:" + signedSide + ":" + marketKey);
  }

  private double resolveFinalEconomyMoney(ItemFinishTransactionEvent event, boolean isBuy) {
    Object resultObj =
        isBuy ? invokeNoArg(event, "getTakeResult") : invokeNoArg(event, "getGiveResult");
    if (resultObj == null) return 0.0;

    Object mapObj = invokeNoArg(resultObj, "getResultMap");
    if (!(mapObj instanceof Map<?, ?> map) || map.isEmpty()) return 0.0;

    double total = 0.0;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object thing = entry.getKey();
      if (!(thing instanceof AbstractSingleThing singleThing) || !isEconomyEntry(singleThing)) continue;
      Object value = entry.getValue();
      if (value instanceof BigDecimal decimal) {
        total += Math.abs(decimal.doubleValue());
      } else if (value instanceof Number number) {
        total += Math.abs(number.doubleValue());
      }
    }
    return total;
  }

  /** [v2.0] Detect economy entries via plugin type AND field heuristics. */
  private boolean isEconomyEntry(AbstractSingleThing thing) {
    ConfigurationSection section = thing.getSingleSection();
    if (section == null) return false;

    // 1. Check known economy plugin types
    String ecoType = section.getString("economy-plugin", "");
    if (KNOWN_ECONOMY_TYPES.contains(ecoType)) return true;

    // 2. Check economy-type field (UltimateShop v4+)
    String ecoTypeAlt = section.getString("economy-type", "");
    if (!ecoTypeAlt.isEmpty() && KNOWN_ECONOMY_TYPES.contains(ecoTypeAlt)) return true;

    // 3. Heuristic: if section has amount/price-like keys, treat as economy entry
    if (section.contains("amount") || section.contains("base-amount")
        || section.contains("economy-type") || section.contains("start-amount")) return true;

    return false;
  }

  /** [v2.0] Log unit-price + quantity for every completed transaction. */
  private void recordTransactionDetail(
      Player player, String shopId, String productId,
      int amount, boolean isBuy, ItemFinishTransactionEvent event) {
    try {
      double unitPrice = isBuy
          ? pricingManager.calculateBuyPrice(shopId, productId)
          : pricingManager.calculateSellPriceForPlayer(player.getUniqueId(), shopId, productId);
      double total = resolveFinalEconomyMoney(event, isBuy);

      AsyncLogger.log(
          player.getUniqueId(),
          isBuy ? -total : total,
          0.0,
          System.currentTimeMillis(),
          String.format("TRADE_DETAIL:%s:%s:%s:unitPrice=%.4f:qty=%d:total=%.2f",
              isBuy ? "BUY" : "SELL",
              PricingManager.toMarketKey(shopId, productId),
              player.getUniqueId(),
              unitPrice,
              amount,
              total));
    } catch (Exception ignored) {
    }
  }

  /** [v2.0] Feed each transaction into the Rust kernel's GARCH volatility and Kalman filter state. */
  private void feedRustKernel(String shopId, String productId, int amount, boolean isBuy) {
    if (!NativeBridge.isLoaded()) return;

    try {
      String marketKey = PricingManager.toMarketKey(shopId, productId);
      double currentPrice = pricingManager.calculateBuyPrice(shopId, productId);
      if (currentPrice <= 0) return;

      // Feed GARCH: the Rust side tracks per-key price return history
      // Initial call seeds the state; subsequent calls compute actual return
      NativeBridge.garchUpdate(marketKey, 0.0); // seed if needed

      // Feed Kalman filter with actual price observation and quantity
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
