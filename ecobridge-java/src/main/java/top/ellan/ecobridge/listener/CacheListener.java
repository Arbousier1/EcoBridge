package top.ellan.ecobridge.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import top.ellan.ecobridge.cache.HotDataCache;
import top.ellan.ecobridge.util.LogUtil;

import java.util.UUID;

/**
 * 缓存生命周期监听器
 * 职责：实现“随玩随走”的内存管理策略，减轻 Rust 侧在处理非在线玩家数据时的压力。
 */
public class CacheListener implements Listener {

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // 1. 触发异步缓存加载
        // 这里假设 load() 内部已经处理了 CompletableFuture 异步逻辑
        HotDataCache.load(uuid);

        // 2. 采样记录
        LogUtil.debug("已为玩家 " + event.getPlayer().getName() + " 开启数据热路径缓存。");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // 1. 触发缓存失效
        // Caffeine 的失效监听器会在此处自动触发数据持久化 (TransactionDao.save)
        HotDataCache.invalidate(uuid);

        // 2. 采样记录
        LogUtil.debug("玩家 " + event.getPlayer().getName() + " 已下线，正在执行热数据卸载。");
    }
}
