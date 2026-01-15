package top.ellan.ecobridge.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import su.nightexpress.coinsengine.api.currency.Currency;
import su.nightexpress.coinsengine.api.event.ChangeBalanceEvent;
import su.nightexpress.coinsengine.data.impl.CoinsUser;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.cache.HotDataCache;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.storage.AsyncLogger;

import java.util.UUID;

/**
 * 经济传感器 (CoinsEngineListener v0.8.5 - SSoT Edition)
 * 职责：作为底层传感器，实时捕获 CoinsEngine 余额变动信号并同步至全服经济系统。
 * 修正：
 * 1. 强制同步镜像到 HotDataCache，确保物理引擎视野对齐。
 * 2. 修复 EconomyManager.onTransaction 参数调用。
 */
public class CoinsEngineListener implements Listener {

    private final String targetCurrencyId;
    private static final double EPSILON = 1e-6; // 过滤计算舍入产生的噪声 [cite: 161]

    public CoinsEngineListener(EcoBridge plugin) {
        // 从配置中锁定主监控货币 ID (如 "coins") [cite: 162]
        this.targetCurrencyId = plugin.getConfig().getString("economy.currency-id", "coins");
    }

    /**
     * 实时监听余额变动事件
     * 优先级设定为 MONITOR，仅观察成交结果，确保不干涉其他插件的业务逻辑。 [cite: 162]
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBalanceChange(ChangeBalanceEvent event) {
        // 1. 货币类型过滤：仅监控目标主货币 [cite: 162]
        Currency currency = event.getCurrency();
        if (!targetCurrencyId.equals(currency.getId())) {
            return;
        }

        // 2. 差分演算 (Delta Calculation) [cite: 163]
        double oldAmount = event.getOldAmount();
        double newAmount = event.getNewAmount();
        double delta = newAmount - oldAmount;

        // 3. 噪声过滤：忽略由于浮点精度产生的微小抖动 [cite: 163]
        if (Math.abs(delta) < EPSILON) {
            return;
        }

        // 4. 宏观层：同步更新全服经济热度指标
        // 传入 true 标识此为市场活动，触发价格波动演算
        EconomyManager.getInstance().onTransaction(delta, true);

        // 5. [SSoT 修复]: 镜像同步逻辑
        // 强制将真相源的最新余额同步到只读缓存镜像 [cite: 104, 164]
        CoinsUser user = event.getUser();
        UUID userUuid = user.getId();

        var cachedData = HotDataCache.get(userUuid);
        if (cachedData != null) {
            cachedData.updateFromTruth(newAmount); //
        }

        // 6. [SSoT 修复]: 异步持久化快照
        // 在数据库中更新该玩家的最终余额快照
        TransactionDao.updateBalance(userUuid, newAmount);

        // 7. 审计层：触发异步持久化日志 (AsyncLogger) [cite: 164, 316]
        long timestamp = System.currentTimeMillis();

        // 委托给虚拟线程执行器异步处理，将流水推向 Rust/DuckDB 管线 [cite: 164, 314, 319]
        AsyncLogger.log(
        userUuid,
        delta,      // 交易变动净值
        newAmount,  // 交易后余额快照
        timestamp   // 物理时间戳
    );
    }
}
