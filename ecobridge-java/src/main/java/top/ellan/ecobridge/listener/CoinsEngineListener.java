package top.ellan.ecobridge.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import su.nightexpress.coinsengine.api.currency.Currency;
import su.nightexpress.coinsengine.api.event.ChangeBalanceEvent;
import su.nightexpress.coinsengine.data.impl.CoinsUser; // [新增]: 导入用户模型
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.storage.AsyncLogger;

import java.util.UUID;

/**
 * 经济传感器 (CoinsEngineListener v0.6.7)
 * 职责：作为底层传感器，实时捕获 CoinsEngine 余额变动信号并喂给宏观经济脑。
 * 修正：校准 CoinsUser 的 UUID 获取方法为 getId()。
 */
public class CoinsEngineListener implements Listener {

    private final String targetCurrencyId;
    private static final double EPSILON = 1e-6; // 过滤计算舍入产生的噪声
    
    public CoinsEngineListener(EcoBridge plugin) {
        // 从配置中锁定主监控货币 ID (如 "coins")
        this.targetCurrencyId = plugin.getConfig().getString("economy.currency-id", "coins");
    }

    /**
     * 实时监听余额变动事件
     * 优先级设定为 MONITOR，仅观察成交结果，确保不干涉其他插件的业务逻辑。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBalanceChange(ChangeBalanceEvent event) {
        // 1. 货币类型过滤：仅监控目标主货币
        Currency currency = event.getCurrency();
        if (!targetCurrencyId.equals(currency.getId())) {
            return;
        }

        // 2. 差分演算 (Delta Calculation)
        double oldAmount = event.getOldAmount();
        double newAmount = event.getNewAmount();
        double delta = newAmount - oldAmount;

        // 3. 噪声过滤：忽略由于浮点精度产生的微小抖动
        if (Math.abs(delta) < EPSILON) {
            return;
        }

        // 4. 宏观层：同步更新全服经济热度指标
        // EconomyManager 内部使用 Atomic 结构，对主线程执行几乎无感
        EconomyManager.getInstance().onTransaction(delta);

        // 5. 审计层：触发异步持久化日志 (AsyncLogger)
        // [修正]: 根据 CoinsEngine 源码，使用 getId() 获取 UUID
        CoinsUser user = event.getUser();
        UUID userUuid = user.getId(); 
        long timestamp = System.currentTimeMillis();

        // 委托给虚拟线程执行器异步处理
        AsyncLogger.log(
            userUuid,
            delta,      // 交易变动净值
            newAmount,  // 交易后余额快照
            timestamp   // 物理时间戳
        );
    }
}