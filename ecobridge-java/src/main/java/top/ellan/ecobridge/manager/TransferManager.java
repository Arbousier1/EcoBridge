package top.ellan.ecobridge.manager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import su.nightexpress.coinsengine.api.currency.Currency;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.bridge.NativeBridge.TransferResult;
import top.ellan.ecobridge.network.RedisManager;
import top.ellan.ecobridge.storage.ActivityCollector;
import top.ellan.ecobridge.storage.AsyncLogger;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.*;

/**
 * 智能转账管理器 (TransferManager v0.9.5 - Macro Integrated)
 * 职责：编排转账审计流，将宏观流速、通胀率与玩家行为信用结合，执行全自动风控。
 */
public class TransferManager {

    private static TransferManager instance;
    private final EcoBridge plugin;
    private final ExecutorService vExecutor;
    private final String mainCurrencyId;

    // 权限定义
    private static final String BYPASS_TAX_PERMISSION = "ecobridge.bypass.tax";
    private static final String BYPASS_BLOCK_PERMISSION = "ecobridge.bypass.block";

    // --- FFM 字段映射 (VarHandles) ---
    private static final VarHandle VH_TR_AMOUNT;
    private static final VarHandle VH_TR_S_BAL;
    private static final VarHandle VH_TR_R_BAL;
    private static final VarHandle VH_TR_INF;
    private static final VarHandle VH_TR_LIMIT;
    private static final VarHandle VH_TR_S_TIME;
    private static final VarHandle VH_TR_R_TIME;
    private static final VarHandle VH_TCTX_SCORE;
    private static final VarHandle VH_TCTX_VELOCITY;
    private static final VarHandle VH_RCFG_V_THRESHOLD;

    static {
        var layout = NativeBridge.Layouts.TRANSFER_CONTEXT;
        VH_TR_AMOUNT = layout.varHandle(MemoryLayout.PathElement.groupElement("amount"));
        VH_TR_S_BAL = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_balance"));
        VH_TR_R_BAL = layout.varHandle(MemoryLayout.PathElement.groupElement("receiver_balance"));
        VH_TR_INF = layout.varHandle(MemoryLayout.PathElement.groupElement("inflation_rate"));
        VH_TR_LIMIT = layout.varHandle(MemoryLayout.PathElement.groupElement("newbie_limit"));
        VH_TR_S_TIME = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_play_time"));
        VH_TR_R_TIME = layout.varHandle(MemoryLayout.PathElement.groupElement("receiver_play_time"));
        VH_TCTX_SCORE = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_activity_score"));
        VH_TCTX_VELOCITY = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_velocity"));

        var regLayout = NativeBridge.Layouts.REGULATOR_CONFIG;
        VH_RCFG_V_THRESHOLD = regLayout.varHandle(MemoryLayout.PathElement.groupElement("velocity_threshold"));
    }

    private TransferManager(EcoBridge plugin) {
        this.plugin = plugin;
        this.vExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.mainCurrencyId = plugin.getConfig().getString("economy.currency-id", "coins");
    }

    public static void init(EcoBridge plugin) { instance = new TransferManager(plugin); }
    public static TransferManager getInstance() { return instance; }

    public void initiateTransfer(Player sender, Player receiver, double amount) {
        Currency currency = CoinsEngineAPI.getCurrency(mainCurrencyId);
        if (currency == null) {
            sender.sendMessage(Component.text("系统故障：找不到核心货币配置。"));
            return;
        }

        double senderBal = CoinsEngineAPI.getBalance(sender, currency);
        if (senderBal < amount) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>余额不足，转账中止。"));
            return;
        }

        captureAndAudit(sender, receiver, currency, amount, senderBal);
    }

    private void captureAndAudit(Player sender, Player receiver, Currency currency, double amount, double senderBal) {
        // 采集参与者行为快照
        var sSnapshot = ActivityCollector.getSafeSnapshot(sender.getUniqueId());
        var rSnapshot = ActivityCollector.getSafeSnapshot(receiver.getUniqueId());
        
        double receiverBal = CoinsEngineAPI.getBalance(receiver, currency);

        if (!sender.hasPermission(BYPASS_BLOCK_PERMISSION)) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<gray><italic>EcoKernel 正在注入宏观流速并进行行为审计..."));
        }

        // 使用虚拟线程执行 Native 审计，防止阻塞主线程
        vExecutor.submit(() -> {
            try (Arena arena = Arena.ofConfined()) {
                // 1. 获取最新宏观数据
                EconomyManager macro = EconomyManager.getInstance();
                double inflation = macro.getInflationRate();
                int currentMarketHeat = (int) macro.getMarketHeat();

                // 2. 分配并填充 TransferContext
                MemorySegment ctx = arena.allocate(NativeBridge.Layouts.TRANSFER_CONTEXT);
                VH_TR_AMOUNT.set(ctx, 0L, amount);
                VH_TR_S_BAL.set(ctx, 0L, senderBal);
                VH_TR_R_BAL.set(ctx, 0L, receiverBal);
                VH_TR_INF.set(ctx, 0L, inflation);
                VH_TR_LIMIT.set(ctx, 0L, plugin.getConfig().getDouble("economy.audit-settings.newbie-limit", 50000.0));
                VH_TR_S_TIME.set(ctx, 0L, sSnapshot.playTimeSeconds());
                VH_TR_R_TIME.set(ctx, 0L, rSnapshot.playTimeSeconds());
                
                // [核心集成] 写入信用分与宏观流速
                VH_TCTX_SCORE.set(ctx, 0L, sSnapshot.activityScore());
                VH_TCTX_VELOCITY.set(ctx, 0L, currentMarketHeat);

                // 3. 填充配置
                MemorySegment cfg = arena.allocate(NativeBridge.Layouts.REGULATOR_CONFIG);
                populateRegulatorConfig(cfg);

                // 4. 调用 Rust 审计核心
                TransferResult result = NativeBridge.checkTransfer(ctx, cfg);

                // 5. 回到主线程执行结算
                Bukkit.getScheduler().runTask(plugin, () ->
                        executeSettlement(sender, receiver, currency, amount, result));

            } catch (Throwable e) {
                LogUtil.error("审计内核响应超时或崩溃 (Macro Violation)", e);
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(Component.text("内核安全屏障异常，转账已拦截。")));
            }
        });
    }

    private void executeSettlement(Player sender, Player receiver, Currency currency, double amount, TransferResult audit) {
        boolean canBypassBlock = sender.isOp() || sender.hasPermission(BYPASS_BLOCK_PERMISSION);
        if (audit.isBlocked() && !canBypassBlock) {
            handleBlocked(sender, audit.warningCode());
            return;
        }

        // 资金原子性二次校验
        double currentSenderBal = CoinsEngineAPI.getBalance(sender, currency);
        if (currentSenderBal < amount) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>转账失败：审计期间账户资金发生异常变动。"));
            return;
        }

        boolean canBypassTax = sender.isOp() || sender.hasPermission(BYPASS_TAX_PERMISSION);
        double tax = canBypassTax ? 0.0 : audit.tax();
        double netAmount = amount - tax;

        try {
            if (!CoinsEngineAPI.removeBalance(sender.getUniqueId(), currency, amount)) {
                throw new IllegalStateException("底层经济接口 CoinsEngine 拒绝扣款");
            }
            CoinsEngineAPI.addBalance(receiver, currency, netAmount);

            // [闭环] 将此笔转账金额记入宏观热度累加器
            EconomyManager.getInstance().recordTradeVolume(amount);

            long ts = System.currentTimeMillis();
            String meta = "TAX:" + tax + "|SCORE:" + ActivityCollector.getScore(sender.getUniqueId());
            AsyncLogger.log(sender.getUniqueId(), -amount, currentSenderBal - amount, ts, meta);

            if (RedisManager.getInstance() != null) {
                RedisManager.getInstance().publishTrade("SYSTEM_TRANSFER", amount);
            }

            notifySuccess(sender, receiver, currency, amount, netAmount, tax, canBypassTax);

        } catch (Exception e) {
            LogUtil.severe("结算链路断裂！正在回滚玩家资产: " + sender.getName());
            CoinsEngineAPI.addBalance(sender, currency, amount);
            sender.sendMessage(Component.text("§c[系统] 结算冲突，资金已安全原路回滚。"));
        }
    }

    private void populateRegulatorConfig(MemorySegment cfg) {
        var section = plugin.getConfig().getConfigurationSection("economy.audit-settings");
        if (section == null) return;

        cfg.set(JAVA_DOUBLE, 0, section.getDouble("base-tax-rate", 0.05));
        cfg.set(JAVA_DOUBLE, 8, section.getDouble("luxury-threshold", 100000.0));
        cfg.set(JAVA_DOUBLE, 16, section.getDouble("luxury-tax-rate", 0.1));
        cfg.set(JAVA_DOUBLE, 24, section.getDouble("wealth-gap-tax-rate", 0.2));
        cfg.set(JAVA_DOUBLE, 32, section.getDouble("poor-threshold", 10000.0));
        cfg.set(JAVA_DOUBLE, 40, section.getDouble("rich-threshold", 1000000.0));
        cfg.set(JAVA_DOUBLE, 48, section.getDouble("newbie-receive-limit", 50000.0));
        cfg.set(JAVA_DOUBLE, 56, section.getDouble("warning-ratio", 0.9));
        cfg.set(JAVA_DOUBLE, 64, section.getDouble("warning-min-amount", 50000.0));
        cfg.set(JAVA_DOUBLE, 72, section.getDouble("newbie-hours", 10.0));
        cfg.set(JAVA_DOUBLE, 80, section.getDouble("veteran-hours", 100.0));
        
        // [New] 动态频率审计阈值
        VH_RCFG_V_THRESHOLD.set(cfg, 0L, section.getDouble("velocity-threshold", 20.0));
    }

    private void handleBlocked(Player sender, int code) {
        String reason = switch (code) {
            case 1 -> "涉嫌非正常资金归集 (风险评级过高)";
            case 2 -> "拦截逆向流转 (新手向老手异常输送)";
            case 3 -> "拦截非正常注资 (老手向新手违规注资)";
            case 4 -> "账户余额不足 (结算冲突)";
            case 5 -> "财富流速异常 (疑似洗钱/拆分转账)";
            default -> "违反服务器金融合规协议 (Audit Code: " + code + ")";
        };
        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>⚠ 审计拒绝: <yellow>" + reason));
    }

    private void notifySuccess(Player s, Player r, Currency cur, double total, double net, double tax, boolean isTaxFree) {
        String suffix = isTaxFree ? " <dark_gray>[免税特权]</dark_gray>" : "";
        s.sendMessage(EcoBridge.getMiniMessage().deserialize("<green>✔ 成功转出 <gold><amt><gray> (税费: <tax>)" + suffix,
                Placeholder.unparsed("amt", cur.format(total)),
                Placeholder.unparsed("tax", cur.format(tax))));
        r.sendMessage(EcoBridge.getMiniMessage().deserialize("<green>➕ 收到 <gold><amt><gray> 来自 <p>",
                Placeholder.unparsed("amt", cur.format(net)),
                Placeholder.unparsed("p", s.getName())));
    }

    public void shutdown() {
        vExecutor.shutdown();
        try { if (!vExecutor.awaitTermination(5, TimeUnit.SECONDS)) vExecutor.shutdownNow(); }
        catch (InterruptedException e) { vExecutor.shutdownNow(); }
    }
}