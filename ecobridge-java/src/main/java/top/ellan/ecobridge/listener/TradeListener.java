package top.ellan.ecobridge.listener;

import cn.superiormc.ultimateshop.api.ItemFinishTransactionEvent;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
 * 交易监听器 (TradeListener v0.6.6)
 * 职责：
 * 1. 拦截高频无效交易请求（基于 UUID 的物理限流）。
 * 2. 映射市场物理量纲：买入 = 供应减少 (-)，卖出 = 供应增加 (+)。
 * 3. 驱动跨语言演算流水线（Java -> Rust FFM）。
 */
public class TradeListener implements Listener {

    private final EcoBridge plugin;
    
    // 频控缓存：拦截恶意连点或脚本操作，保护 Rust 核心算力
    private final Map<UUID, Long> tradeThrottle = new ConcurrentHashMap<>();
    private final long throttleThresholdMs;

    public TradeListener(EcoBridge plugin) {
        this.plugin = plugin;
        this.throttleThresholdMs = plugin.getConfig().getLong("system.trade-throttle-ms", 150L);
    }

    /**
     * 核心监听：UltimateShop 事务完成事件
     * 使用 MONITOR 优先级确保在所有交易逻辑确认完成后再进行重算
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShopTrade(ItemFinishTransactionEvent event) {
        final Player player = event.getPlayer();
        final ObjectItem item = event.getItem();
        
        // 1. 防御性空值检查
        if (item == null || item.empty) return;

        final double rawAmount = (double) event.getAmount();
        final boolean isBuy = event.isBuyOrSell(); // true 为玩家买入，false 为玩家卖出
        final long now = System.currentTimeMillis();

        // 2. 物理层频控拦截
        if (isThrottled(player.getUniqueId(), now)) return;

        // 3. [主线程同步] 配额与限购自检
        // 涉及 UltimateShop NBT/Metadata 的操作必须保留在主线程执行
        TimeMonitor.checkAndResetQuota(player, item);

        // 4. [Java 25 虚拟线程] 启动异步演算与持久化链
        // 确保不阻塞 Minecraft 主线程（TPS 保护）
        plugin.getVirtualExecutor().execute(() -> {
            try {
                /*
                 * 核心物理映射逻辑：
                 * 玩家买入(Buy) -> 系统存量减少 -> 传入负值 -> 驱动指数价格曲线上升
                 * 玩家卖出(Sell) -> 系统存量增加 -> 传入正值 -> 驱动价格回归基准
                 */
                double effectiveAmount = isBuy ? -rawAmount : rawAmount;

                // Step A: 宏观大脑响应 (更新通胀因子 ε)
                EconomyManager.getInstance().onTransaction(effectiveAmount);

                // Step B: 微观价格响应 (调用 Rust 执行 FFM 向量化衰减重算)
                // 此处内部会触发 TransactionDao.saveSaleAsync
                PricingManager.getInstance().onTradeComplete(item, effectiveAmount);

                // Step C: 审计日志采样记录
                logTrade(player, item, isBuy, rawAmount, effectiveAmount);

            } catch (Throwable e) {
                LogUtil.error("交易演算流水线发生致命异常 [" + item.getProduct() + "]", e);
            }
        });
    }

    /**
     * 实现基于时间窗口的物理节流逻辑
     */
    private boolean isThrottled(UUID uuid, long now) {
        Long lastTime = tradeThrottle.get(uuid);
        if (lastTime != null && (now - lastTime) < throttleThresholdMs) {
            return true;
        }
        tradeThrottle.put(uuid, now);
        return false;
    }

    /**
     * 结构化日志输出：利用 MiniMessage 渲染采样数据
     */
    private void logTrade(Player p, ObjectItem item, boolean isBuy, double raw, double eff) {
        if (!plugin.getConfig().getBoolean("system.log-transactions", true)) return;

        LogUtil.logTransactionSampled(
            "<gray>[EcoBridge] <action> <white><id> <gray>x<amt> <dark_gray>(权重: <eff>) <gray>玩家: <p>",
            Placeholder.unparsed("action", isBuy ? "<gold>收购" : "<aqua>出售"),
            Placeholder.unparsed("id", item.getProduct()),
            Placeholder.unparsed("amt", String.format("%.1f", raw)),
            Placeholder.unparsed("eff", String.format("%.1f", eff)),
            Placeholder.unparsed("p", p.getName())
        );
    }

    /**
     * 清理资源，防止离线玩家数据堆积造成的内存泄漏
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        tradeThrottle.remove(e.getPlayer().getUniqueId());
    }
}