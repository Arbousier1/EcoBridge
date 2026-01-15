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
import top.ellan.ecobridge.storage.AsyncLogger;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static top.ellan.ecobridge.bridge.NativeBridge.*;

/**
 * 智能转账管理器 (TransferManager v0.6.9 - Final Hardened)
 * 职责：编排转账审计流，实施主线程原子化结算。
 * 修复：通过“影子审计”解决特权绕过安全盲区，并修正了 FFM 内存布局对齐。
 */
public class TransferManager {

    private static TransferManager instance;
    private final EcoBridge plugin;
    private final ExecutorService vExecutor; 
    private final String mainCurrencyId;
    
    // 细分权限：免税权限 与 免拦截权限
    private static final String BYPASS_TAX_PERMISSION = "ecobridge.bypass.tax";
    private static final String BYPASS_BLOCK_PERMISSION = "ecobridge.bypass.block";

    private TransferManager(EcoBridge plugin) {
        this.plugin = plugin;
        this.vExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.mainCurrencyId = plugin.getConfig().getString("economy.main-currency", "coins");
    }

    public static void init(EcoBridge plugin) {
        instance = new TransferManager(plugin);
    }

    public static TransferManager getInstance() {
        return instance;
    }

    /**
     * 发起转账事务 (入口阶段 - 主线程)
     */
    public void initiateTransfer(Player sender, Player receiver, double amount) {
        Currency currency = CoinsEngineAPI.getCurrency(mainCurrencyId);
        if (currency == null) {
            sender.sendMessage(Component.text("系统故障：找不到核心货币配置。"));
            return;
        }

        // 阶段 1: 物理预检
        double senderBal = CoinsEngineAPI.getBalance(sender, currency);
        if (senderBal < amount) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>余额不足，转账中止。"));
            return;
        }

        // 阶段 2: 影子审计流
        // 即使是 OP 也要进入审计流，以确保 Rust 侧统计数据的完整性 (Shadow Audit)
        captureAndAudit(sender, receiver, currency, amount, senderBal);
    }

    private void captureAndAudit(Player sender, Player receiver, Currency currency, double amount, double senderBal) {
        long sPlayTime = (long) sender.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20;
        long rPlayTime = (long) receiver.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20;
        double receiverBal = CoinsEngineAPI.getBalance(receiver, currency);

        // 仅对普通玩家显示审计提示
        if (!sender.hasPermission(BYPASS_BLOCK_PERMISSION)) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<gray><italic>EcoKernel 正在进行实时金融合规性审计..."));
        }

        vExecutor.submit(() -> {
            try (Arena arena = Arena.ofConfined()) {
                double inflation = EconomyManager.getInstance().getInflationRate();

                // [核心修复]: 专用 TRANSFER_CONTEXT (56 字节)
                MemorySegment ctx = arena.allocate(Layouts.TRANSFER_CONTEXT);
                ctx.set(ValueLayout.JAVA_DOUBLE, 0, amount);
                ctx.set(ValueLayout.JAVA_DOUBLE, 8, senderBal);
                ctx.set(ValueLayout.JAVA_DOUBLE, 16, receiverBal);
                ctx.set(ValueLayout.JAVA_DOUBLE, 24, inflation);
                ctx.set(ValueLayout.JAVA_DOUBLE, 32, plugin.getConfig().getDouble("economy.newbie-limit", 50000.0));
                ctx.set(ValueLayout.JAVA_LONG, 40, sPlayTime);
                ctx.set(ValueLayout.JAVA_LONG, 48, rPlayTime);

                // [核心修复]: 专用 REGULATOR_CONFIG (88 字节)
                MemorySegment cfg = arena.allocate(Layouts.REGULATOR_CONFIG);
                populateRegulatorConfig(cfg);

                // 执行 Rust 演算
                TransferResult result = NativeBridge.checkTransfer(ctx, cfg);

                // 同步回主线程结算
                Bukkit.getScheduler().runTask(plugin, () -> 
                    executeSettlement(sender, receiver, currency, amount, result));

            } catch (Throwable e) {
                LogUtil.error("审计内核崩溃 (Memory Access Violation)", e);
                Bukkit.getScheduler().runTask(plugin, () -> 
                    sender.sendMessage(Component.text("内核异常，转账已拦截。")));
            }
        });
    }

    /**
     * 执行资金结算 (最终阶段 - 主线程)
     */
    private void executeSettlement(Player sender, Player receiver, Currency currency, double amount, TransferResult audit) {
        // --- 逻辑分权：拦截校验 ---
        boolean canBypassBlock = sender.isOp() || sender.hasPermission(BYPASS_BLOCK_PERMISSION);
        if (audit.isBlocked() && !canBypassBlock) {
            handleBlocked(sender, audit.warningCode());
            return;
        } else if (audit.isBlocked() && canBypassBlock) {
            LogUtil.warn("特权拦截豁免: " + sender.getName() + " 触发了风险码 " + audit.warningCode());
        }

        // --- 二次平衡校验 (强制执行，杜绝 TOCTOU) ---
        double currentSenderBal = CoinsEngineAPI.getBalance(sender, currency);
        if (currentSenderBal < amount) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>转账失败：账户资金在审计期间已变动。"));
            return;
        }

        // --- 逻辑分权：计税校验 ---
        boolean canBypassTax = sender.isOp() || sender.hasPermission(BYPASS_TAX_PERMISSION);
        double tax = canBypassTax ? 0.0 : audit.tax();
        double netAmount = amount - tax;

        try {
            // 原子化执行
            if (!CoinsEngineAPI.removeBalance(sender.getUniqueId(), currency, amount)) {
                throw new IllegalStateException("底层经济接口拒绝操作");
            }
            CoinsEngineAPI.addBalance(receiver, currency, netAmount);

            // 记录流水 (带特权标记)
            long ts = System.currentTimeMillis();
            String meta = canBypassTax ? "BYPASS_TAX" : "NORMAL";
            AsyncLogger.log(sender.getUniqueId(), -amount, currentSenderBal - amount, ts, meta);

            notifySuccess(sender, receiver, currency, amount, netAmount, tax, canBypassTax);

        } catch (Exception e) {
            LogUtil.severe("结算链路致命异常！触发紧急回滚: " + sender.getName());
            CoinsEngineAPI.addBalance(sender, currency, amount); // 补偿性退款
            sender.sendMessage(Component.text("系统结算超时，资金已安全回滚。"));
        }
    }

    private void populateRegulatorConfig(MemorySegment cfg) {
        var section = plugin.getConfig().getConfigurationSection("economy.audit-settings");
        if (section == null) return;

        // 严格按照 11 个 double 填充 (88 Bytes)
        cfg.set(ValueLayout.JAVA_DOUBLE, 0, section.getDouble("base-tax-rate", 0.05));
        cfg.set(ValueLayout.JAVA_DOUBLE, 8, section.getDouble("luxury-threshold", 100000.0));
        cfg.set(ValueLayout.JAVA_DOUBLE, 16, section.getDouble("luxury-tax-rate", 0.1));
        cfg.set(ValueLayout.JAVA_DOUBLE, 24, section.getDouble("wealth-gap-tax-rate", 0.2));
        cfg.set(ValueLayout.JAVA_DOUBLE, 32, section.getDouble("poor-threshold", 10000.0));
        cfg.set(ValueLayout.JAVA_DOUBLE, 40, section.getDouble("rich-threshold", 1000000.0));
        cfg.set(ValueLayout.JAVA_DOUBLE, 48, section.getDouble("newbie-receive-limit", 50000.0));
        cfg.set(ValueLayout.JAVA_DOUBLE, 56, section.getDouble("warning-ratio", 0.9));
        cfg.set(ValueLayout.JAVA_DOUBLE, 64, section.getDouble("warning-min-amount", 50000.0));
        cfg.set(ValueLayout.JAVA_DOUBLE, 72, section.getDouble("newbie-hours", 10.0));
        cfg.set(ValueLayout.JAVA_DOUBLE, 80, section.getDouble("veteran-hours", 100.0));
    }

    private void handleBlocked(Player sender, int code) {
        String reason = switch (code) {
            case 1 -> "涉嫌非正常资金归集 (洗币防御)";
            case 2 -> "大额流动性异常 (RMT 拦截)";
            case 671 -> "内核对齐校验失败";
            default -> "违反服务器金融合规协议";
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
        try {
            if (!vExecutor.awaitTermination(5, TimeUnit.SECONDS)) vExecutor.shutdownNow();
        } catch (InterruptedException e) { vExecutor.shutdownNow(); }
    }
}