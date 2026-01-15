package top.ellan.ecobridge.manager;

import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.database.TransactionDao;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 经济状态管理器 (EconomicStateManager v1.2.0 - 人性化重写版)
 * * 核心逻辑：
 * 1. 损失厌恶：玩家怕跌不怕涨。当市场崩溃时，商会出手“托底”，让价格跌得更有尊严。
 * 2. 锚定效应：参考过去 7 天的平均表现，给玩家一个心理预期。
 */
public class EconomicStateManager {

    private static EconomicStateManager instance;
    private final Map<String, MarketPhase> lastKnownPhases = new ConcurrentHashMap<>();

    /**
     * 市场状态（用玩家听得懂的商业术语定义）
     */
    public enum MarketPhase {
        STABLE,           // 贸易活跃：供需平衡的黄金时期
        SATURATED,        // 货物积压：产量过剩，商会正在减缓收购速度
        EMERGENCY,        // 市场危机：恐慌性抛售，商会启动保价协议（托底）
        HEALING           // 秩序恢复：市场正在从冲击中回暖
    }

    private EconomicStateManager() {}

    public static void init(EcoBridge plugin) {
        instance = new EconomicStateManager();
    }

    public static EconomicStateManager getInstance() {
        return instance;
    }

    /**
     * 分析市场情绪并执行“商会干预”
     */
    public MarketPhase analyzeMarketAndNotify(String productId, double currentNeff) {
        // 1. 获取“历史心理价位”（7天均价作为锚点）
        double anchorValue = TransactionDao.get7DayAverage(productId);
        if (anchorValue <= 0) return MarketPhase.STABLE;

        // 2. 计算“冲击指数”（当前流通量与历史均量的偏离度）
        double impactIndex = currentNeff / anchorValue;
        MarketPhase currentPhase;

        // --- 行为经济学决策矩阵 ---
        if (impactIndex > 3.5) {
            // 冲击过猛：视为玩家正在拆箱/大批量倾销
            currentPhase = MarketPhase.EMERGENCY;
        } else if (impactIndex > 1.8) {
            // 产量超标：自动化农场正在全速运转
            currentPhase = MarketPhase.SATURATED;
        } else {
            currentPhase = MarketPhase.STABLE;
        }

        // 3. 执行叙事广播
        checkAndBroadcast(productId, currentPhase);

        return currentPhase;
    }

    /**
     * 只有当市场情绪发生实质性转变时，才发布公告
     */
    private void checkAndBroadcast(String productId, MarketPhase newPhase) {
        MarketPhase oldPhase = lastKnownPhases.getOrDefault(productId, MarketPhase.STABLE);

        if (oldPhase != newPhase) {
            lastKnownPhases.put(productId, newPhase);
            executeBroadcast(productId, newPhase);
        }
    }

    private void executeBroadcast(String productId, MarketPhase phase) {
        String msg = switch (phase) {
            case EMERGENCY -> 
                "<red>⚖ [商会紧急干预] <white><id> <red>遭遇抛售狂潮！为防止财富瞬间缩水，商会已介入调控，开启“价格保护”模式。";
            case SATURATED -> 
                "<yellow>⚠ [市场警告] <white><id> <yellow>产出过高导致库存积压。由于市场消化能力有限，后续收购价将缓慢下调。";
            case STABLE -> 
                "<green>✔ [贸易正常化] <white><id> <green>库存已恢复至健康水平。商会取消紧急干预，恢复自由贸易定价。";
            default -> "";
        };

        if (!msg.isEmpty()) {
            Bukkit.broadcast(EcoBridge.getMiniMessage().deserialize(
                msg.replace("<id>", productId)
            ));
        }
    }

    /**
     * 获取“收购灵敏度”系数
     * * 逻辑背景：
     * - 危机时刻（EMERGENCY）：灵敏度降至最低（0.35），人为制造下行粘性，防止价格瞬间归零导致玩家弃坑。
     * - 饱和时刻（SATURATED）：灵敏度调低（0.6），给玩家一种“商会很努力在吃货但快撑不住了”的感觉。
     */
    public double getBehavioralLambdaModifier(MarketPhase phase) {
        return switch (phase) {
            case EMERGENCY -> 0.35; // 极度粘性，保护玩家财富
            case SATURATED -> 0.60; // 软着陆，缓解工业冲击
            default -> 1.0;         // 尊重自然市场波动
        };
    }
}