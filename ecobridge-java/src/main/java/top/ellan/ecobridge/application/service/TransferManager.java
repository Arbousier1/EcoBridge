package top.ellan.ecobridge.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Preconditions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import su.nightexpress.coinsengine.api.currency.Currency;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.context.TransactionContext;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.infrastructure.ffi.model.NativeTransferResult;
import top.ellan.ecobridge.infrastructure.persistence.redis.RedisManager;
import top.ellan.ecobridge.infrastructure.persistence.storage.ActivityCollector;
import top.ellan.ecobridge.infrastructure.persistence.storage.AsyncLogger;
import top.ellan.ecobridge.infrastructure.persistence.transaction.TransactionJournal;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * 智能转账管理器 (TransferManager v1.7.4 - Panic Safe & WAL)
 * <p>
 * 修复日志:
 * 1. [Safety] 增加 CODE_PANIC (101) 处理，Rust 崩溃时自动降级。
 * 2. [Consistency] 引入 WAL 模式 (markPending)，确保资金扣除前日志已落盘。
 */
public class TransferManager {

    private static volatile TransferManager instance;
    private final EcoBridge plugin;
    private final ExecutorService vExecutor;
    private final String mainCurrencyId;

    private static final String BYPASS_TAX_PERMISSION = "ecobridge.bypass.tax";
    private static final String BYPASS_BLOCK_PERMISSION = "ecobridge.bypass.block";

    // Rust Panic 错误码 (对应 Rust 层的 EconStatus::Panic)
    private static final int CODE_PANIC = 101;

    private final Cache<UUID, VelocityTracker> velocityCache;
    private final ReentrantLock updateLock = new ReentrantLock();
    private double velocityHalfLife = 60.0;
    
    // --- FFI 内存布局句柄 (Micros Adapted) ---
    private static final VarHandle VH_TR_AMOUNT;
    private static final VarHandle VH_TR_S_BAL;
    private static final VarHandle VH_TR_R_BAL;
    private static final VarHandle VH_TR_INF;

    private static final VarHandle VH_TR_ITEM_BASE;
    private static final VarHandle VH_TR_ITEM_GROWTH;
    private static final VarHandle VH_TR_ITEM_MAX;

    private static final VarHandle VH_TR_S_TIME;
    private static final VarHandle VH_TR_R_TIME;

    private static final VarHandle VH_TCTX_SCORE;
    private static final VarHandle VH_TCTX_VELOCITY;
    private static final VarHandle VH_RCFG_V_THRESHOLD;

    static {
        try {
            var layout = NativeBridge.Layouts.TRANSFER_CONTEXT;
            VH_TR_AMOUNT = layout.varHandle(MemoryLayout.PathElement.groupElement("amount_micros"));
            VH_TR_S_BAL = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_balance"));
            VH_TR_R_BAL = layout.varHandle(MemoryLayout.PathElement.groupElement("receiver_balance"));
            VH_TR_INF = layout.varHandle(MemoryLayout.PathElement.groupElement("inflation_rate"));
            VH_TR_ITEM_BASE = layout.varHandle(MemoryLayout.PathElement.groupElement("item_base_limit"));
            VH_TR_ITEM_GROWTH = layout.varHandle(MemoryLayout.PathElement.groupElement("item_growth_rate"));
            VH_TR_ITEM_MAX = layout.varHandle(MemoryLayout.PathElement.groupElement("item_max_limit"));
            VH_TR_S_TIME = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_play_time"));
            VH_TR_R_TIME = layout.varHandle(MemoryLayout.PathElement.groupElement("receiver_play_time"));
            VH_TCTX_SCORE = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_activity_score"));
            VH_TCTX_VELOCITY = layout.varHandle(MemoryLayout.PathElement.groupElement("sender_velocity"));

            var regLayout = NativeBridge.Layouts.REGULATOR_CONFIG;
            VH_RCFG_V_THRESHOLD = regLayout.varHandle(MemoryLayout.PathElement.groupElement("velocity_threshold"));

        } catch (Exception e) {
            throw new RuntimeException("CRITICAL: TransferManager 内存布局初始化失败", e);
        }
    }

    private TransferManager(EcoBridge plugin) {
        this.plugin = plugin;
        this.vExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.mainCurrencyId = plugin.getConfig().getString("economy.currency-id", "coins");
        this.velocityCache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();
        loadConfig();
    }

    public static void init(EcoBridge plugin) { instance = new TransferManager(plugin); }
    public static TransferManager getInstance() { return instance; }
    public void loadConfig() { this.velocityHalfLife = plugin.getConfig().getDouble("economy.regulator.velocity-half-life", 60.0); }

    public TagResolver getMarketPriceResolver(String productId) {
        PricingManager pm = PricingManager.getInstance();
        if (pm == null) return TagResolver.empty();
        double buy = pm.calculateBuyPrice(productId);
        double sell = pm.calculateSellPrice(productId);
        return TagResolver.resolver(
            Placeholder.unparsed("buy_price", String.format("%.2f", buy)),
            Placeholder.unparsed("sell_price", String.format("%.2f", sell))
        );
    }

    public void initiateTransfer(Player sender, Player receiver, double amount) {
        Currency currency = CoinsEngineAPI.getCurrency(mainCurrencyId);
        if (currency == null) {
            sender.sendMessage(Component.text("系统故障：找不到核心货币配置 (ID: " + mainCurrencyId + ")。"));
            return;
        }
        double senderBal = CoinsEngineAPI.getBalance(sender, currency);
        if (senderBal < amount) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
                "<red>✘ 交易失败</red> <dark_gray>| <gray>账户余额不足，无法支付 <gold><amount></gold>",
                Placeholder.unparsed("amount", String.format("%.2f", amount))
            ));
            return;
        }
        captureAndAudit(sender, receiver, currency, amount, senderBal);
    }

    private void captureAndAudit(Player sender, Player receiver, Currency currency, double amount, double senderBal) {
        if (!sender.hasPermission(BYPASS_BLOCK_PERMISSION)) {
            sender.sendActionBar(EcoBridge.getMiniMessage().deserialize(
                "<dark_gray>[</dark_gray><gradient:blue:aqua>Audit</gradient><dark_gray>]</dark_gray> <gray>正在同步 <aqua>Native</aqua> 算力核心..."
            ));
        }

        NativeBridge.checkTransferAsync(
            (ctx) -> fillTransferContext(ctx, sender, receiver, currency, amount, senderBal),
            this::populateRegulatorConfig
        ).thenAcceptAsync(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (shouldTriggerFallback(result)) {
                    if (plugin.getConfig().getBoolean("economy.fallback-mode", true)) {
                        LogUtil.warn("Native 内核无响应或发生 Panic (Code 101)，切换至安全降级模式执行交易。");
                        NativeTransferResult fallbackResult = new NativeTransferResult(NativeBridge.computeFallbackTax(amount), false, 0);
                        executeSettlement(sender, receiver, currency, amount, fallbackResult);
                    } else {
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>⚠ 经济核心维护中，交易暂时关闭。"));
                    }
                } else {
                    executeSettlement(sender, receiver, currency, amount, result);
                }
            });
        }, vExecutor);
    }

    private boolean shouldTriggerFallback(NativeTransferResult result) {
        // [Fix] 增加 Rust Panic (101) 检测
        return result.warningCode() == -1 
            || result.warningCode() == CODE_PANIC 
            || (result.isBlocked() && result.warningCode() == 0); 
    }

    public NativeTransferResult previewTransaction(Player player, double amount) {
        if (!NativeBridge.isLoaded()) return NativeTransferResult.PASS;
        
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctxSeg = arena.allocate(NativeBridge.Layouts.TRANSFER_CONTEXT);
            MemorySegment cfgSeg = arena.allocate(NativeBridge.Layouts.REGULATOR_CONFIG);
            MemorySegment resSeg = arena.allocate(NativeBridge.Layouts.TRANSFER_RESULT);

            Currency cur = CoinsEngineAPI.getCurrency(mainCurrencyId);
            double senderBal = (cur != null) ? CoinsEngineAPI.getBalance(player, cur) : 0.0;

            fillTransferContext(ctxSeg, player, null, cur, amount, senderBal);
            populateRegulatorConfig(cfgSeg);

            int status = NativeBridge.checkTransferSync(resSeg, ctxSeg, cfgSeg);
            if (status != 0) return NativeTransferResult.FFI_ERROR;

            long taxMicros = (long) NativeBridge.VH_RES_TAX_MICROS.get(resSeg, 0L);
            double tax = NativeBridge.microsToMoney(taxMicros);
            
            boolean isBlocked = ((Number) NativeBridge.VH_RES_BLOCKED.get(resSeg, 0L)).intValue() != 0;
            int warningCode = ((Number) NativeBridge.VH_RES_CODE.get(resSeg, 0L)).intValue();

            return new NativeTransferResult(tax, isBlocked, warningCode);
        } catch (Throwable t) {
            LogUtil.error("同步审计路径崩溃 (Native Bridge Downcall Error)", t);
            return NativeTransferResult.FFI_ERROR;
        }
    }

    private void fillTransferContext(MemorySegment ctx, Player sender, Player receiver, Currency cur, double amount, double senderBal) throws Throwable {
        var sSnapshot = ActivityCollector.getSafeSnapshot(sender.getUniqueId());
        double individualVelocity = getEstimatedVelocity(sender.getUniqueId());

        final long amountMicros = NativeBridge.moneyToMicros(amount);
        final long senderBalMicros = NativeBridge.moneyToMicros(senderBal);
        final long receiverBalMicros = (receiver != null) ? NativeBridge.moneyToMicros(CoinsEngineAPI.getBalance(receiver, cur)) : 0L;

        VH_TR_AMOUNT.set(ctx, 0L, amountMicros);
        VH_TR_S_BAL.set(ctx, 0L, senderBalMicros);
        VH_TR_R_BAL.set(ctx, 0L, receiverBalMicros);
        VH_TR_INF.set(ctx, 0L, EconomyManager.getInstance().getInflationRate());
        
        VH_TR_ITEM_BASE.set(ctx, 0L, 0L);
        VH_TR_ITEM_GROWTH.set(ctx, 0L, 0.0);
        VH_TR_ITEM_MAX.set(ctx, 0L, 0L);
        
        VH_TR_S_TIME.set(ctx, 0L, sSnapshot.seconds()); 
        VH_TR_R_TIME.set(ctx, 0L, 0L); 
        VH_TCTX_SCORE.set(ctx, 0L, sSnapshot.activityScore());
        VH_TCTX_VELOCITY.set(ctx, 0L, individualVelocity);
    }

    public void executeSettlement(Player sender, Player receiver, Currency currency, double amount, NativeTransferResult audit) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "结算逻辑必须在主线程执行！");

        if (plugin.isShadowMode()) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
                "<dark_gray>[<gradient:aqua:blue>Shadow</gradient>]</dark_gray> <gray>审计模拟: 拦截代码 <red><code/></red>, 预估税费 <gold><tax/></gold>",
                Placeholder.unparsed("code", String.valueOf(audit.warningCode())),
                Placeholder.unparsed("tax", String.format("%.2f", audit.finalTax()))
            ));
            AsyncLogger.getInstance().logTransaction(sender, receiver, amount, audit.finalTax());
            return; 
        }

        boolean canBypassBlock = sender.isOp() || sender.hasPermission(BYPASS_BLOCK_PERMISSION);
        if (audit.isBlocked() && !canBypassBlock) {
            handleBlocked(sender, audit.warningCode());
            return;
        }

        double currentSenderBal = CoinsEngineAPI.getBalance(sender, currency);
        if (currentSenderBal < amount) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>转账失败：账户资金发生并发冲突。"));
            return;
        }

        boolean canBypassTax = sender.isOp() || sender.hasPermission(BYPASS_TAX_PERMISSION);
        NativeBridge.SettlementMath settlementMath = NativeBridge.computeSettlement(amount, audit.finalTax(), canBypassTax);
        double tax = settlementMath.tax();
        double netAmount = settlementMath.netAmount();

        String txId;
        try {
            txId = TransactionJournal.beginTransaction(sender.getUniqueId(), 
                    receiver != null ? receiver.getUniqueId() : null, amount, tax);
        } catch (RuntimeException e) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>系统故障：交易日志子系统离线，操作已取消。"));
            return;
        }

        boolean debitSuccess = false;
        boolean creditSuccess = false;

        try {
            // [Fix] WAL 核心: 在操作资金前强制标记日志为 Pending 状态
            // 这保证了 "①③ 之间崩溃" 时，日志已存在，可用于后续对账
            TransactionJournal.markPending(txId);

            // [Fix] 开启交易上下文，标记为市场行为
            TransactionContext.setMarketTrade(true);

            if (!CoinsEngineAPI.removeBalance(sender.getUniqueId(), currency, amount)) {
                throw new IllegalStateException("余额不足或引擎拒绝");
            }
            debitSuccess = true;

            if (receiver != null) {
                CoinsEngineAPI.addBalance(receiver.getUniqueId(), currency, netAmount);
            }
            creditSuccess = true;

            TransactionJournal.commitTransaction(txId);
            postTransactionActions(sender, receiver, amount, netAmount, tax, canBypassTax, currency);

        } catch (Exception e) {
            LogUtil.severe("严重: 交易执行阶段异常 " + txId + " | 错误: " + e.getMessage());
            
            if (debitSuccess && !creditSuccess) {
                try {
                    boolean rollbackSuccess = CoinsEngineAPI.addBalance(sender.getUniqueId(), currency, amount);
                    
                    if (rollbackSuccess) {
                        TransactionJournal.markForRollback(txId);
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>转账对方失败，资金已自动退回。"));
                    } else {
                        throw new IllegalStateException("API 返回 false");
                    }
                } catch (Throwable rollbackEx) {
                    LogUtil.severe("☣☣☣ 致命: 资金回滚失败！TXID: " + txId + " 玩家: " + sender.getName());
                    LogUtil.severe("回滚错误: " + rollbackEx.getMessage());
                }
            } else if (!debitSuccess) {
                TransactionJournal.markForRollback(txId);
                sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>扣款失败，交易已取消。"));
            }
        } finally {
            // [Fix] 必须清除上下文，防止状态污染
            TransactionContext.clear();
        }
    }

    private void postTransactionActions(Player sender, Player receiver, double amount, double netAmount, double tax, boolean isTaxFree, Currency currency) {
        // [Fix] 移除手动调用 recordTradeVolume，完全交由 Listener 监听
        // EconomyManager.getInstance().recordTradeVolume(amount);
        
        updateVelocity(sender.getUniqueId(), amount);
        AsyncLogger.getInstance().logTransaction(sender, receiver, amount, tax);

        // Do not publish player-to-player transfers to market trade channels.

        notifySuccess(sender, receiver, currency, amount, netAmount, tax, isTaxFree);
    }

    private double getEstimatedVelocity(UUID uuid) {
        VelocityTracker tracker = velocityCache.getIfPresent(uuid);
        return (tracker != null) ? tracker.getRecalculated(System.currentTimeMillis()) : 0.0;
    }

    private void updateVelocity(UUID uuid, double amount) {
        updateLock.lock();
        try {
            velocityCache.get(uuid, k -> new VelocityTracker(velocityHalfLife))
                        .add(amount, System.currentTimeMillis());
        } finally {
            updateLock.unlock();
        }
    }

    private static class VelocityTracker {
        private double velocity; 
        private long lastUpdateTs;
        private final double halfLifeMs; 

        public VelocityTracker(double halfLifeSeconds) {
            this.velocity = 0.0;
            this.lastUpdateTs = System.currentTimeMillis();
            this.halfLifeMs = halfLifeSeconds * 1000.0;
        }

        public synchronized double getRecalculated(long now) {
            long delta = now - lastUpdateTs;
            if (delta <= 0) return velocity;
            return NativeBridge.computeVelocityDecay(velocity, delta, halfLifeMs);
        }

        public synchronized void add(double amount, long now) {
            this.velocity = getRecalculated(now);
            this.velocity += amount;
            this.lastUpdateTs = now;
        }
    }

    private void populateRegulatorConfig(MemorySegment cfg) {
        FileConfiguration config = plugin.getConfig();
        var section = config.getConfigurationSection("economy.audit-settings");
        
        // 读取 Double 配置
        double baseTax = section != null ? section.getDouble("base-tax-rate", 0.05) : 0.05;
        double luxuryThreshold = section != null ? section.getDouble("luxury-threshold", 100000.0) : 100000.0;
        double vThreshold = section != null ? section.getDouble("velocity-threshold", 20.0) : 20.0;

        // [Fix] 类型对齐: c_double -> JAVA_DOUBLE; c_longlong -> JAVA_LONG (并转换微米)
        cfg.set(JAVA_DOUBLE, 0, baseTax);
        
        // luxury_threshold (i64)
        cfg.set(JAVA_LONG, 8, NativeBridge.moneyToMicros(luxuryThreshold));
        
        cfg.set(JAVA_DOUBLE, 16, section != null ? section.getDouble("luxury-tax-rate", 0.1) : 0.1);
        cfg.set(JAVA_DOUBLE, 24, section != null ? section.getDouble("wealth-gap-tax-rate", 0.2) : 0.2);
        
        // poor_threshold (i64)
        double poorTh = section != null ? section.getDouble("poor-threshold", 10000.0) : 10000.0;
        cfg.set(JAVA_LONG, 32, NativeBridge.moneyToMicros(poorTh));
        
        // rich_threshold (i64)
        double richTh = section != null ? section.getDouble("rich-threshold", 1000000.0) : 1000000.0;
        cfg.set(JAVA_LONG, 40, NativeBridge.moneyToMicros(richTh));
        
        cfg.set(JAVA_DOUBLE, 48, 0.0); 
        cfg.set(JAVA_DOUBLE, 56, section != null ? section.getDouble("warning-ratio", 0.9) : 0.9);
        
        // warning_min_amount (i64)
        double warnMin = section != null ? section.getDouble("warning-min-amount", 50000.0) : 50000.0;
        cfg.set(JAVA_LONG, 64, NativeBridge.moneyToMicros(warnMin));
        
        cfg.set(JAVA_DOUBLE, 72, section != null ? section.getDouble("newbie-hours", 10.0) : 10.0);
        cfg.set(JAVA_DOUBLE, 80, section != null ? section.getDouble("veteran-hours", 100.0) : 100.0);
        
        VH_RCFG_V_THRESHOLD.set(cfg, 0L, vThreshold);
    }

    private void handleBlocked(Player sender, int code) {
        String reason = switch (code) {
            case NativeBridge.CODE_WARNING_HIGH_RISK -> "高风险异常交易 (风控拦截)";
            case NativeBridge.CODE_BLOCK_REVERSE_FLOW -> "逆向资金流转 (非法输送)";
            case NativeBridge.CODE_BLOCK_INJECTION -> "非正常资产注资 (违规扶持)";
            case NativeBridge.CODE_BLOCK_INSUFFICIENT_FUNDS -> "账户余额校验失败 (FFI)";
            case NativeBridge.CODE_BLOCK_VELOCITY_LIMIT -> "资金流量异常 (洗钱嫌疑)";
            case NativeBridge.CODE_BLOCK_QUANTITY_LIMIT -> "触发动态限额 (市场保护)";
            case NativeBridge.CODE_BLOCK_VELOCITY_LIMIT + 10000 -> "内部算力错误"; // Fallback for some offset codes
            default -> "违反金融合规协议";
        };
        sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
            "<red><bold>⚠ 交易拦截</bold></red> <dark_gray>» <yellow><reason></yellow> <gray><i>(Code: <code/>)</i>",
            Placeholder.unparsed("reason", reason),
            Placeholder.unparsed("code", String.valueOf(code))
        ));
    }

    private void notifySuccess(Player s, Player r, Currency cur, double total, double net, double tax, boolean isTaxFree) {
        Component suffix = isTaxFree 
            ? EcoBridge.getMiniMessage().deserialize(" <rainbow>★免税特权★</rainbow>") 
            : Component.empty();

        s.sendMessage(EcoBridge.getMiniMessage().deserialize(
            "<gradient:aqua:blue>EcoBridge</gradient> <dark_gray>| <gray>您成功向 <white><player></white> 转账 <gold><amount></gold> <dark_gray>(含税: <red><tax></red>)</dark_gray>",
            Placeholder.unparsed("player", r != null ? r.getName() : "未知"),
            Placeholder.unparsed("amount", cur.format(total)),
            Placeholder.unparsed("tax", cur.format(tax))
        ).append(suffix));

        if (r != null) {
            r.sendMessage(EcoBridge.getMiniMessage().deserialize(
                "<gradient:aqua:blue>EcoBridge</gradient> <dark_gray>| <green>＋<gold><amount></gold> <gray>来自 <white><sender></white> 的汇款",
                Placeholder.unparsed("amount", cur.format(net)),
                Placeholder.unparsed("sender", s.getName())
            ));
        }
    }

    public void shutdown() {
        vExecutor.shutdown();
        try { 
            if (!vExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                vExecutor.shutdownNow();
            }
        } catch (InterruptedException e) { 
            vExecutor.shutdownNow(); 
            Thread.currentThread().interrupt();
        }
    }
}
