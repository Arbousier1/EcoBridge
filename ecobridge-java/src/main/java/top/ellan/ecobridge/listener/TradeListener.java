package top.ellan.ecobridge.listener;

import cn.superiormc.ultimateshop.api.ItemFinishTransactionEvent;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.manager.PricingManager;
import top.ellan.ecobridge.util.LogUtil;
import top.ellan.ecobridge.util.TimeMonitor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行为引导型交易监听器 (TradeListener v0.8.6 - Async Safety Hardened)
 * 职责：驱动演算流水线，并针对大宗交易实施行为引导。
 */
public class TradeListener implements Listener {

    private final EcoBridge plugin;
    private final Map<UUID, Long> tradeThrottle = new ConcurrentHashMap<>();
    private final long throttleThresholdMs;

    public TradeListener(EcoBridge plugin) {
        this.plugin = plugin;
        this.throttleThresholdMs = plugin.getConfig().getLong("system.trade-throttle-ms", 150L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShopTrade(ItemFinishTransactionEvent event) {
        final Player player = event.getPlayer();
        final ObjectItem item = event.getItem();

        if (item == null || item.empty) return;

        final double rawAmount = (double) event.getAmount();
        final boolean isBuy = event.isBuyOrSell();
        final long now = System.currentTimeMillis();

        // 1. 物理层频控拦截 (ConcurrentHashMap 线程安全)
        if (isThrottled(player.getUniqueId(), now)) return;

        // 2. 同步状态自检 (涉及 UltimateShop 缓存操作，必须在主线程)
        TimeMonitor.checkAndResetQuota(player, item);

        // [关键] 捕获玩家名称，防止在异步线程调用 player.getName() (虽然 getName 通常安全，但最佳实践是预先获取)
        final String playerName = player.getName();
        final UUID playerUuid = player.getUniqueId(); // UUID 是安全的

        // 3. 异步演算流水线 (使用 Java 25 虚拟线程)
        plugin.getVirtualExecutor().execute(() -> {
            try {
                // 物理量纲映射：买入为负(消耗库存)，卖出为正(增加库存)
                double effectiveAmount = isBuy ? -rawAmount : rawAmount;

                // Step A: 更新宏观经济热度 (纯数值计算，线程安全)
                EconomyManager.getInstance().onTransaction(effectiveAmount, true);

                // Step B: 触发本地与跨服价格同步 (Redis + SQL，线程安全)
                PricingManager.getInstance().onTradeComplete(item, effectiveAmount);

                // Step C: 行为引导 (大宗交易心理疏导)
                if (!isBuy && rawAmount > 500) {
                    // [Safety Fix] 调度回主线程发送消息
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        // 再次检查玩家是否在线，防止 NPE
                        Player onlinePlayer = Bukkit.getPlayer(playerUuid);
                        if (onlinePlayer != null && onlinePlayer.isOnline()) {
                            sendBehavioralGuidance(onlinePlayer, rawAmount);
                        }
                    });
                }

                // Step D: 采样日志记录 (传入预先获取的 playerName)
                logTrade(playerName, item, isBuy, rawAmount, effectiveAmount);

            } catch (Throwable e) {
                LogUtil.error("交易演算流水线异常 [" + item.getProduct() + "]", e);
            }
        });
    }

    private void sendBehavioralGuidance(Player player, double amount) {
        player.sendMessage(EcoBridge.getMiniMessage().deserialize(
                "<blue>⚖</blue> <gray>大宗交易提醒：本次出售量为 <white><amt></white>。",
                Placeholder.unparsed("amt", String.format("%.0f", amount))
        ));

        if (amount > 2000) {
            player.sendMessage(EcoBridge.getMiniMessage().deserialize(
                    "<red>⚠</red> <yellow>市场饱和警告：单次抛售超 2000 件已触发深度折价。建议分段出售以保护利润。"
            ));
        } else {
            player.sendMessage(EcoBridge.getMiniMessage().deserialize(
                    "<aqua>ℹ</aqua> <gray>提示：单次交易过大会产生边际效用递减，小额多次交易收益更高。"
            ));
        }
    }

    private boolean isThrottled(UUID uuid, long now) {
        Long lastTime = tradeThrottle.get(uuid);
        if (lastTime != null && (now - lastTime) < throttleThresholdMs) {
            return true;
        }
        tradeThrottle.put(uuid, now);
        return false;
    }

    // [Safety Fix] 参数改为 String playerName
    private void logTrade(String playerName, ObjectItem item, boolean isBuy, double raw, double eff) {
        if (!plugin.getConfig().getBoolean("system.log-transactions", true)) return;

        LogUtil.logTransactionSampled(
                "<gray>[EcoBridge] <action> <white><id> <gray>x<amt> <dark_gray>(权重: <eff>) <gray>玩家: <p>",
                Placeholder.unparsed("action", isBuy ? "<gold>买入" : "<aqua>卖出"),
                Placeholder.unparsed("id", item.getProduct()),
                Placeholder.unparsed("amt", String.format("%.1f", raw)),
                Placeholder.unparsed("eff", String.format("%.1f", eff)),
                Placeholder.unparsed("p", playerName)
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        tradeThrottle.remove(e.getPlayer().getUniqueId());
    }
}