package top.ellan.ecobridge.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.util.LogUtil;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
* 玩家数据热点缓存 (HotDataCache v0.8.5 - SSoT Read-Only Mirror)
* 职责：维护在线玩家的高频交易数据快照，作为物理引擎的高性能只读视图。
* <p>
* 修复日志 (v0.8.5):
* - [SSoT] 移除 addAndGet 写权限，确保 CoinsEngine 为唯一余额真相源 。
* - [Sync] 引入 updateFromTruth 用于单向镜像同步，彻底解决双账本冲突。
* - [Safety] 严格保留 v0.7.5 的内存布局与 FFM 适配结构。
*/
public class HotDataCache {

  // 缓存配置：写入后 2 小时过期（防止僵尸数据），最大容量 2000 人
  private static final Cache<UUID, PlayerData> CACHE = Caffeine.newBuilder()
      .maximumSize(2000)
      .expireAfterAccess(Duration.ofHours(2))
      .removalListener((UUID uuid, PlayerData data, RemovalCause cause) -> {
        if (data == null) return;
        // 只有非替换操作（如过期、手动移除）才触发异步回写，避免 update 时的冗余 IO
        if (cause != RemovalCause.REPLACED) {
          saveAsync(uuid, data, "CACHE_" + cause.name());
        }
      })
      .build();

  /**
  * 异步加载玩家数据到缓存
  * 通常在玩家 PlayerJoinEvent 时调用
  */
  public static void load(UUID uuid) {
    CompletableFuture.runAsync(() -> {
      try {
        // 调用 DAO 获取最近一次的持久化快照 [cite: 98]
        PlayerData data = TransactionDao.loadPlayerData(uuid);
        
        // 回到主线程（或安全线程）放入缓存
        Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
          Player p = Bukkit.getPlayer(uuid);
          if (p != null && p.isOnline()) {
            CACHE.put(uuid, data);
            LogUtil.debug("已为玩家 " + p.getName() + " 完成原子路径数据挂载。");
          } else {
            // 如果加载完成时玩家已离线，为了数据安全，立即回写一次
            saveAsync(uuid, data, "LATE_LOAD_PROTECT");
          }
        });
      } catch (Exception e) {
        LogUtil.error("玩家 " + uuid + " 数据热加载发生致命错误！", e);
      }
    }, EcoBridge.getInstance().getVirtualExecutor());
  }

  /**
  * 获取玩家热数据
  * @return 可能为 null (如果未命中缓存)
  */
  public static PlayerData get(UUID uuid) {
    return CACHE.getIfPresent(uuid);
  }

  /**
  * 使缓存失效（触发下线回写）
  */
  public static void invalidate(UUID uuid) {
    CACHE.invalidate(uuid);
  }

  /**
  * 内部异步保存逻辑
  */
  private static void saveAsync(UUID uuid, PlayerData data, String reason) {
    EcoBridge.getInstance().getVirtualExecutor().execute(() -> {
      TransactionDao.updateBalance(uuid, data.getBalance());
      
      if (LogUtil.isDebugEnabled()) {
        LogUtil.debug("数据写回成功 [" + reason + "]: " + uuid + " (Balance: " + data.getBalance() + ")");
      }
    });
  }

  /**
  * 关机时的全量同步保存
  * 警告：此操作会阻塞主线程，仅在 onDisable 使用
  */
  public static void saveAllSync() {
    LogUtil.info("正在执行关机前的全量热数据强制同步...");
    var snapshotMap = CACHE.asMap();
    
    for (var entry : snapshotMap.entrySet()) {
      TransactionDao.updateBalanceSync(entry.getKey(), entry.getValue().getBalance());
    }
    
    CACHE.invalidateAll();
    LogUtil.info("所有活跃数据已安全落盘。");
  }

  /**
  * 线程安全的玩家数据容器
  * 使用 AtomicLong 存储 double 的位表示，实现无锁并发读取快照 [cite: 103]
  */
  public static class PlayerData {
    private final UUID uuid;
    private final AtomicLong balanceBits;

    public PlayerData(UUID uuid, double initialBalance) {
      this.uuid = uuid;
      this.balanceBits = new AtomicLong(Double.doubleToRawLongBits(initialBalance));
    }

    public UUID getUuid() { return uuid; }

    /**
     * 获取当前余额快照（仅用于物理演算和预览）
     */
    public double getBalance() {
      return Double.longBitsToDouble(balanceBits.get());
    }

    /**
     * [v0.8.5 Fix] 镜像同步方法
     * 由 CoinsEngineListener 调用，将外部真相源的变动强制同步到本地镜像。
     * 废弃了原有的 addAndGet 自旋逻辑，彻底解决双账本不一致风险。
     */
    public void updateFromTruth(double newBalance) {
      balanceBits.set(Double.doubleToRawLongBits(newBalance));
    }

    /**
     * 兼容性方法，内部调用 updateFromTruth
     */
    public void setBalance(double newBalance) {
      updateFromTruth(newBalance);
    }

  }
}