package top.ellan.ecobridge.util;

import cn.superiormc.ultimateshop.api.ShopHelper;
import cn.superiormc.ultimateshop.objects.caches.ObjectUseTimesCache;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.ellan.ecobridge.EcoBridge;

/**
* 时间监控器 (TimeMonitor v0.8.5 - Thread Safety Patch)
* 职责：作为 UltimateShop 的影子组件，管理玩家交易配额的生命周期。
* 核心：利用原生 API 驱动限额重置逻辑，确保动态价格计算前的状态准确性。
* <p>
* 修复日志 (v0.8.5):
* - [Safety] 强制将 UltimateShop 缓存修改逻辑同步到主线程执行。
* - [Safety] 修复虚拟线程调用下 player.sendMessage 的不安全性。
*/
public final class TimeMonitor {

  // 工具类私有化构造
  private TimeMonitor() {}

  /**
  * 检查并执行配额刷新自检
  * 逻辑：调用 UltimateShop 内核，依据 TIMED 规则判定是否需要重置。
  * @param player 目标玩家
  * @param item  目标商品
  */
  public static void checkAndResetQuota(@NotNull Player player, @NotNull ObjectItem item) {
    // [v0.8.5 Fix] 由于此方法可能由虚拟线程调用，必须确保 UltimateShop 的状态修改在主线程执行
    Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
      // 1. 获取玩家针对该商品的交易次数缓存对象
      ObjectUseTimesCache cache = ShopHelper.getPlayerUseTimesCache(item, player);
      if (cache == null) return;

      // 2. 触发原生刷新逻辑 (Atomic Operation in Main Thread)
      // UltimateShop 会根据 getSellRefreshTime() 判定当前是否处于新周期。
      cache.refreshSellTimes();

      // 3. 采样审计：仅在调试开启时记录
      if (LogUtil.isDebugEnabled()) {
        LogUtil.logTransactionSampled(
          "<gray>[限额] 触发玩家 <white><p></white> 的配额状态同步自检。",
          Placeholder.unparsed("p", player.getName())
        );
      }
    });
  }

  /**
  * 强制物理重置 (管理员/系统任务专用)
  * 职责：绕过时间规则，强行将交易计数清零并全服同步。
  */
  public static void forceReset(@NotNull Player player, @NotNull ObjectItem item) {
    // [v0.8.5 Fix] 强制重置涉及跨服 Bungee 数据分发，必须在主线程执行以保证原子性
    Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
      ObjectUseTimesCache cache = ShopHelper.getPlayerUseTimesCache(item, player);
      if (cache == null) return;

      /*
      * 调用 UltimateShop 同步方法：
      * 参数 1 (int): 重置后的次数 (0)
      * 参数 2 (boolean): notUseBungee = false (设为 false 即开启 Bungee 同步)
      * 参数 3 (boolean): isReset = true (标记为系统重置行为)
      */
      cache.setSellUseTimes(0, false, true);

      // 交互反馈 (安全地在主线程发送消息)
      player.sendMessage(EcoBridge.getMiniMessage().deserialize(
        "<green>⚖</green> <gray>您的商品 <white><id></white> 交易配额已被重置！",
        Placeholder.unparsed("id", item.getProduct())
      ));
      
      // 全量日志记录
      LogUtil.info("管理员/系统 已强制重置玩家 <p> 的商品 <id> 配额", 
          Placeholder.unparsed("p", player.getName()),
          Placeholder.unparsed("id", item.getProduct()));
    });
  }
}