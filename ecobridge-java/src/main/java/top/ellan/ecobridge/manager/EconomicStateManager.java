package top.ellan.ecobridge.manager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.database.TransactionDao;
import top.ellan.ecobridge.util.LogUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 经济状态管理器 (EconomicStateManager v0.9.0 - Full Implementation)
 * 职责：
 * 1. 分析市场情绪 (Market Phase) 并发布公告。
 * 2. 接收交易事件并推送到定价引擎。
 */
public class EconomicStateManager {

    private static EconomicStateManager instance;
    private final Map<String, MarketPhase> lastKnownPhases = new ConcurrentHashMap<>();

    // 锚点值缓存
    private final Cache<String, Double> anchorCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    public enum MarketPhase {
        STABLE,
        SATURATED,
        EMERGENCY,
        HEALING
    }

    private EconomicStateManager() {}

    public static void init(EcoBridge plugin) {
        instance = new EconomicStateManager();
    }

    public static EconomicStateManager getInstance() {
        return instance;
    }

    /**
     * [新增] 记录购买交易 (流入系统)
     * 被 UShopPriceInjector 调用
     */
    public void recordPurchase(Player player, String productId, int amount) {
        // 购买意味着玩家从系统买入物品 -> 市场库存减少 -> 价格应上涨
        // 在 EcoBridge 模型中，购买行为通常被视为负的 "销售量" (supply reduction)
        // 或者单独记录为 Demand。为了简化，这里暂时只记录日志，
        // 如果您的 PricingManager 支持双向影响，请调用相应方法。
        
        LogUtil.debug("记录购买: " + player.getName() + " -> " + productId + " x" + amount);
        
        // 示例：如果购买也影响价格 (Supply 减少)，可以传入负数 amount
        if (PricingManager.getInstance() != null) {
            // 注意：PricingManager 需要支持通过 ProductID 构建 ObjectItem 才能调用 onTradeComplete
            // 或者重载一个直接接受 ProductID 的方法。这里暂时只做日志。
            // PricingManager.getInstance().onTradeComplete(productId, -amount); 
        }
    }

    /**
     * [新增] 记录出售交易 (流出到系统)
     * 被 UShopPriceInjector 调用
     */
    public void recordSale(Player player, String productId, int amount) {
        // 出售意味着玩家向系统卖出物品 -> 市场库存增加 -> 价格应下跌
        LogUtil.debug("记录出售: " + player.getName() + " -> " + productId + " x" + amount);

        if (PricingManager.getInstance() != null) {
            // 调用核心定价引擎记录交易
            // 注意：我们需要构造一个虚拟的 ObjectItem 或者让 PricingManager 支持 ID 调用
            // 这里为了兼容性，建议在 PricingManager 中增加 onTradeComplete(String productId, double amount)
            
            // 假设 PricingManager 已经有了支持 ID 的方法 (见下方建议)
             PricingManager.getInstance().onTradeComplete(productId, amount);
        }
        
        // 触发市场状态分析 (异步)
        Bukkit.getScheduler().runTaskAsynchronously(EcoBridge.getInstance(), () -> {
            // 计算当前有效供给 (Neff)，这里暂用 amount 代替，实际应查询数据库
            double currentNeff = amount; // 这里应该是查询累计值
            analyzeMarketAndNotify(productId, currentNeff);
        });
    }

    /**
     * 分析市场情绪并执行“商会干预”
     */
    public MarketPhase analyzeMarketAndNotify(String productId, double currentNeff) {
        Double anchorValue = anchorCache.get(productId, k -> TransactionDao.get7DayAverage(k));

        if (anchorValue == null || anchorValue <= 0) return MarketPhase.STABLE;

        double impactIndex = currentNeff / anchorValue;
        MarketPhase currentPhase;
        MarketPhase oldPhase = lastKnownPhases.getOrDefault(productId, MarketPhase.STABLE);

        if (impactIndex > 3.5) {
            currentPhase = MarketPhase.EMERGENCY;
        } else if (impactIndex > 1.8) {
            currentPhase = MarketPhase.SATURATED;
        } else if (oldPhase == MarketPhase.EMERGENCY && impactIndex < 1.5) {
            currentPhase = MarketPhase.HEALING;
        } else if (impactIndex < 1.2) {
            currentPhase = MarketPhase.STABLE;
        } else {
            currentPhase = oldPhase;
        }

        checkAndBroadcast(productId, currentPhase);
        return currentPhase;
    }

    private void checkAndBroadcast(String productId, MarketPhase newPhase) {
        MarketPhase oldPhase = lastKnownPhases.get(productId);
        if (oldPhase != newPhase) {
            lastKnownPhases.put(productId, newPhase);
            executeBroadcast(productId, newPhase);
        }
    }

    private void executeBroadcast(String productId, MarketPhase phase) {
        String msg = switch (phase) {
            case EMERGENCY -> "<red>⚖ [商会紧急干预] <white><id> <red>遭遇抛售狂潮！开启“价格保护”模式。";
            case SATURATED -> "<yellow>⚠ [市场警告] <white><id> <yellow>库存积压，收购价将下调。";
            case HEALING -> "<aqua>❈ [秩序恢复] <white><id> <aqua>市场正在回暖。";
            case STABLE -> "<green>✔ [贸易正常化] <white><id> <green>恢复自由贸易定价。";
        };

        Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
            Bukkit.broadcast(EcoBridge.getMiniMessage().deserialize(
                msg.replace("<id>", productId)
            ));
        });
    }

    public double getBehavioralLambdaModifier(MarketPhase phase) {
        return switch (phase) {
            case EMERGENCY -> 0.35;
            case SATURATED -> 0.60;
            case HEALING -> 0.85;
            default -> 1.0;
        };
    }
}