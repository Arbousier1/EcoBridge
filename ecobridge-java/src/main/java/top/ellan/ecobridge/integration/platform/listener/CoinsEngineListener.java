package top.ellan.ecobridge.integration.platform.listener;

import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import su.nightexpress.coinsengine.api.currency.Currency;
import su.nightexpress.coinsengine.api.event.ChangeBalanceEvent;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.context.TransactionContext;
import top.ellan.ecobridge.application.service.EconomyManager;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache.PlayerData;
import top.ellan.ecobridge.infrastructure.persistence.database.TransactionDao;
import top.ellan.ecobridge.infrastructure.persistence.storage.AsyncLogger;

/**
 * CoinsEngine balance listener.
 *
 * <p>Key behavior: 1. Uses {@link TransactionContext} to detect internal market transactions. 2.
 * Prevents double counting by recording market-side velocity only on debit leg (delta &lt; 0). 3.
 * Synchronizes latest balance to cache + DB + async journal.
 */
public class CoinsEngineListener implements Listener {

  private final String targetCurrencyId;
  private static final double EPSILON = 1e-6; // floating-point guard

  public CoinsEngineListener(EcoBridge plugin) {
    // Configure which currency id should be observed (default: coins).
    this.targetCurrencyId = plugin.getConfig().getString("economy.currency-id", "coins");
  }

  /** Observe finalized balance changes (MONITOR) without interfering with other plugins. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBalanceChange(ChangeBalanceEvent event) {
    // 1) Filter by configured currency.
    Currency currency = event.getCurrency();
    if (!targetCurrencyId.equals(currency.getId())) {
      return;
    }

    // 2) Compute delta.
    double oldAmount = event.getOldAmount();
    double newAmount = event.getNewAmount();
    double delta = newAmount - oldAmount;

    // 3) Ignore tiny floating-point noise.
    if (Math.abs(delta) < EPSILON) {
      return;
    }

    // Distinguish internal market trade from external API-triggered balance changes.
    boolean isMarketTrade = TransactionContext.isMarketTrade();

    if (isMarketTrade) {
      // Fix double counting: only count market velocity on debit leg (delta < 0).
      if (delta < 0) {
        EconomyManager.getInstance().onTransaction(Math.abs(delta), true);
      }
    } else {
      // External source: feed signed delta for M1 tracking.
      EconomyManager.getInstance().onTransaction(delta, false);
    }

    // 4) Sync to cache / storage using CoinsEngine user UUID as source of truth.
    syncToStorage(event.getUser().getId(), newAmount, delta, isMarketTrade);
  }

  private void syncToStorage(UUID uuid, double balance, double delta, boolean isMarketTrade) {
    // 1) Hot cache sync.
    PlayerData cachedData = HotDataCache.get(uuid);
    if (cachedData != null) {
      cachedData.updateFromTruth(balance);
    }

    // 2) Persistent balance sync.
    TransactionDao.updateBalance(uuid, balance);

    // 3) Async event journal.
    String meta = isMarketTrade ? "INTERNAL_MARKET_TRADE" : "EXTERNAL_API_CHANGE";

    AsyncLogger.log(uuid, delta, balance, System.currentTimeMillis(), meta);
  }
}
