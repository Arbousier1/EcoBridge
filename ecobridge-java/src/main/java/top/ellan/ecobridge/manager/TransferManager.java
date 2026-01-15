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
import top.ellan.ecobridge.network.RedisManager; // [新增] 引入同步管理器
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
 * 智能转账管理器 (TransferManager v0.8.5 - Cross-Server Edition)
 * 职责：编排转账审计流，实施主线程原子化结算，并同步全球经济热度。
 * @ThreadSafe
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

    static {
        var layout = NativeBridge.Layouts.TRANSFER_CONTEXT;
        VH_TR_AMOUNT = layout.varHandle(MemoryLayout.PathElement.groupElement("amount"));
        VH_TR_S_BAL = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_balance"));
        VH_TR_R_BAL = layout.varHandle(MemoryLayout.PathElement.groupElement("receiver_balance"));
        VH_TR_INF = layout.varHandle(MemoryLayout.PathElement.groupElement("inflation_rate"));
        VH_TR_LIMIT = layout.varHandle(MemoryLayout.PathElement.groupElement("newbie_limit"));
        VH_TR_S_TIME = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_play_time"));
        VH_TR_R_TIME = layout.varHandle(MemoryLayout.PathElement.groupElement("receiver_play_time"));
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
        long sPlayTime = (long) sender.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20;
        long rPlayTime = (long) receiver.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20;
        double receiverBal = CoinsEngineAPI.getBalance(receiver, currency);

        if (!sender.hasPermission(BYPASS_BLOCK_PERMISSION)) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<gray><italic>EcoKernel 正在进行实时金融合规性审计..."));
        }

        vExecutor.submit(() -> {
            try (Arena arena = Arena.ofConfined()) {
                double inflation = EconomyManager.getInstance().getInflationRate();

                MemorySegment ctx = arena.allocate(NativeBridge.Layouts.TRANSFER_CONTEXT);
                VH_TR_AMOUNT.set(ctx, 0L, amount);
                VH_TR_S_BAL.set(ctx, 0L, senderBal);
                VH_TR_R_BAL.set(ctx, 0L, receiverBal);
                VH_TR_INF.set(ctx, 0L, inflation);
                VH_TR_LIMIT.set(ctx, 0L, plugin.getConfig().getDouble("economy.audit-settings.newbie-limit", 50000.0));
                VH_TR_S_TIME.set(ctx, 0L, sPlayTime);
                VH_TR_R_TIME.set(ctx, 0L, rPlayTime);

                MemorySegment cfg = arena.allocate(NativeBridge.Layouts.REGULATOR_CONFIG);
                populateRegulatorConfig(cfg);

                TransferResult result = NativeBridge.checkTransfer(ctx, cfg);

                Bukkit.getScheduler().runTask(plugin, () -> 
                    executeSettlement(sender, receiver, currency, amount, result));

            } catch (Throwable e) {
                LogUtil.error("审计内核崩溃 (Memory Access Violation)", e);
                Bukkit.getScheduler().runTask(plugin, () -> 
                    sender.sendMessage(Component.text("内核异常，转账已拦截。")));
            }
        });
    }

    private void executeSettlement(Player sender, Player receiver, Currency currency, double amount, TransferResult audit) {
        boolean canBypassBlock = sender.isOp() || sender.hasPermission(BYPASS_BLOCK_PERMISSION);
        if (audit.isBlocked() && !canBypassBlock) {
            handleBlocked(sender, audit.warningCode());
            return;
        }

        double currentSenderBal = CoinsEngineAPI.getBalance(sender, currency);
        if (currentSenderBal < amount) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>转账失败：账户资金在审计期间已变动。"));
            return;
        }

        boolean canBypassTax = sender.isOp() || sender.hasPermission(BYPASS_TAX_PERMISSION);
        double tax = canBypassTax ? 0.0 : audit.tax();
        double netAmount = amount - tax;

        try {
            if (!CoinsEngineAPI.removeBalance(sender.getUniqueId(), currency, amount)) {
                throw new IllegalStateException("底层经济接口拒绝操作");
            }
            CoinsEngineAPI.addBalance(receiver, currency, netAmount);

            // 1. 记录本地审计流水 (异步)
            long ts = System.currentTimeMillis();
            String meta = canBypassTax ? "BYPASS_TAX" : "NORMAL";
            AsyncLogger.log(sender.getUniqueId(), -amount, currentSenderBal - amount, ts, meta);

            // 2. [跨服同步集成]: 广播资金流动热度至全局 Redis 频道
            if (RedisManager.getInstance() != null) {
                // 使用 "SYSTEM_TRANSFER" 作为虚拟 ID，使全服 EconomyManager 同步通胀感知
                RedisManager.getInstance().publishTrade("SYSTEM_TRANSFER", amount);
            }

            notifySuccess(sender, receiver, currency, amount, netAmount, tax, canBypassTax);

        } catch (Exception e) {
            LogUtil.severe("结算链路致命异常！触发紧急回滚: " + sender.getName());
            CoinsEngineAPI.addBalance(sender, currency, amount); 
            sender.sendMessage(Component.text("系统结算超时，资金已安全回滚。"));
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
    }

    private void handleBlocked(Player sender, int code) {
        String reason = switch (code) {
            case 1 -> "涉嫌非正常资金归集 (洗币防御)";
            case 2 -> "大额流动性异常 (RMT 拦截)";
            default -> "违反服务器金融合规协议 (Code: " + code + ")";
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