package top.ellan.ecobridge.infrastructure.ffi.bridge;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.gen.*; // [核心] 依赖 jextract 生成的绑定类
import top.ellan.ecobridge.infrastructure.ffi.model.NativeTransferResult;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.lang.foreign.ValueLayout.*;

/**
 * NativeBridge (Core v1.7.0 - Layouts Restored & Arena Safety Fix)
 * <p>
 * 修复日志:
 * 1. [Fix] 恢复 public static class Layouts，解决 TransferManager 和 PriceComputeEngine 的编译错误。
 * 2. [Bridge] Layouts 类现在作为代理，直接指向 jextract 生成的 Layout。
 * 3. [Safety] 审计所有 FFM 调用，确保跨线程场景使用 ofConfined()，防止 IllegalStateException。
 */
public class NativeBridge {

    // ==================================================================================
    // 1. 基础状态与常量
    // ==================================================================================

    private enum BridgeState { UNINITIALIZED, RUNNING, SHUTTING_DOWN, CLOSED }
    private static final AtomicReference<BridgeState> STATE = new AtomicReference<>(BridgeState.UNINITIALIZED);

    private static final int EXPECTED_ABI_VERSION = 0x0009_0000;
    private static volatile Arena sharedArena;
    
    // 生命周期锁
    private static final ReentrantReadWriteLock LIFECYCLE_LOCK = new ReentrantReadWriteLock();

    // 专用 FFI 线程池
    private static final ExecutorService nativeLane = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors()),
        Thread.ofPlatform().name("Eco-Native-Lane-", 0).factory()
    );

    private static final double MICROS_DIVISOR = 1_000_000.0;

    // 状态码常量
    public static final int CODE_NORMAL = 0;
    public static final int CODE_WARNING_HIGH_RISK = 1;
    public static final int CODE_BLOCK_REVERSE_FLOW = 2;
    public static final int CODE_BLOCK_INJECTION = 3;
    public static final int CODE_BLOCK_INSUFFICIENT_FUNDS = 4;
    public static final int CODE_BLOCK_VELOCITY_LIMIT = 5;
    public static final int CODE_BLOCK_QUANTITY_LIMIT = 6;

    // ==================================================================================
    // 2. VarHandles (内部使用)
    // ==================================================================================

    public static final VarHandle VH_CTX_BASE_PRICE_MICROS;
    public static final VarHandle VH_CTX_CURR_AMT;
    public static final VarHandle VH_CTX_INF_RATE;
    public static final VarHandle VH_CTX_TIMESTAMP;
    public static final VarHandle VH_CTX_PLAY_TIME;
    public static final VarHandle VH_CTX_TIMEZONE_OFFSET;
    public static final VarHandle VH_CTX_NEWBIE_MASK;
    public static final VarHandle VH_CTX_MARKET_HEAT;
    public static final VarHandle VH_CTX_ECO_SAT;

    public static final VarHandle VH_CFG_LAMBDA;
    public static final VarHandle VH_CFG_VOLATILITY;
    public static final VarHandle VH_CFG_S_AMP;
    public static final VarHandle VH_CFG_W_MULT;
    public static final VarHandle VH_CFG_N_PROT;
    public static final VarHandle VH_CFG_W_SEASONAL;
    public static final VarHandle VH_CFG_W_WEEKEND;
    public static final VarHandle VH_CFG_W_NEWBIE;
    public static final VarHandle VH_CFG_W_INFLATION;

    public static final VarHandle VH_TCTX_SENDER_BAL;
    public static final VarHandle VH_TCTX_RECEIVER_BAL;
    public static final VarHandle VH_TCTX_INFLATION;
    public static final VarHandle VH_TCTX_ITEM_BASE;
    public static final VarHandle VH_TCTX_ITEM_GROWTH;
    public static final VarHandle VH_TCTX_ITEM_MAX;
    public static final VarHandle VH_TCTX_SENDER_TIME;
    public static final VarHandle VH_TCTX_RECEIVER_TIME;
    public static final VarHandle VH_TCTX_ACTIVITY_SCORE;
    public static final VarHandle VH_TCTX_VELOCITY;
    
    public static final VarHandle VH_RCFG_V_THRESHOLD;

    public static final VarHandle VH_RES_TAX_MICROS;
    public static final VarHandle VH_RES_BLOCKED;
    public static final VarHandle VH_RES_CODE;

    public static final VarHandle VH_PID_KP;
    public static final VarHandle VH_PID_KI;
    public static final VarHandle VH_PID_KD;

    // Method Handles
    private static volatile MethodHandle initThreadingMH;
    private static volatile MethodHandle getAbiVersionMH;
    private static volatile MethodHandle initDBMH;
    private static volatile MethodHandle getVersionMH;
    private static volatile MethodHandle getHealthStatsMH;
    private static volatile MethodHandle shutdownDBMH;
    private static volatile MethodHandle pushToDuckDBMH;
    private static volatile MethodHandle queryNeffVectorizedMH;
    private static volatile MethodHandle computePriceMH;
    private static volatile MethodHandle calculateEpsilonMH;
    private static volatile MethodHandle checkTransferMH;
    private static volatile MethodHandle computePidMH;
    private static volatile MethodHandle resetPidMH;
    private static volatile MethodHandle calcInflationMH;
    private static volatile MethodHandle calcStabilityMH;
    private static volatile MethodHandle calcDecayMH;
    private static volatile MethodHandle computeTierPriceMH;
    private static volatile MethodHandle computePriceBoundedMH;
    private static volatile MethodHandle computeBatchPricesMH;
    private static volatile MethodHandle injectRemoteTradeMH;
    private static volatile MethodHandle injectRemoteTradeForKeyMH;
    private static volatile MethodHandle getDynamicLimitMH;
    private static volatile MethodHandle queryNeffForKeyMH;
    private static volatile MethodHandle moneyToMicrosMH;
    private static volatile MethodHandle microsToMoneyMH;
    private static volatile MethodHandle computeVolatilityFromStabilityMH;
    private static volatile MethodHandle computeVelocityDecayMH;
    private static volatile MethodHandle computeFallbackTaxMH;
    private static volatile MethodHandle computeSettlementMH;
    private static volatile MethodHandle garchInitMH;
    private static volatile MethodHandle garchUpdateMH;
    private static volatile MethodHandle garchForecastMH;
    private static volatile MethodHandle garchMultiplierMH;
    private static volatile MethodHandle garchFreeMH;
    private static volatile MethodHandle kalmanInitMH;
    private static volatile MethodHandle kalmanFilterMH;
    private static volatile MethodHandle kalmanVelocityMH;
    private static volatile MethodHandle kalmanFreeMH;
    private static volatile MethodHandle arimaInitMH;
    private static volatile MethodHandle arimaAddObsMH;
    private static volatile MethodHandle arimaPredictMH;
    private static volatile MethodHandle arimaFreeMH;

    static {
        try {
            // 从生成的 Layout 中提取 VarHandle
            MemoryLayout ctxLayout = TradeContext.layout();
            VH_CTX_BASE_PRICE_MICROS = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("base_price_micros"));
            VH_CTX_CURR_AMT = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("current_amount"));
            VH_CTX_INF_RATE = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("inflation_rate"));
            VH_CTX_TIMESTAMP = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("current_timestamp"));
            VH_CTX_PLAY_TIME = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("play_time_seconds"));
            VH_CTX_TIMEZONE_OFFSET = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("timezone_offset"));
            VH_CTX_NEWBIE_MASK = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("newbie_mask"));
            VH_CTX_MARKET_HEAT = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("market_heat"));
            VH_CTX_ECO_SAT = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("eco_saturation"));

            MemoryLayout cfgLayout = MarketConfig.layout();
            VH_CFG_LAMBDA = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("base_lambda"));
            VH_CFG_VOLATILITY = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("volatility_factor"));
            VH_CFG_S_AMP = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("seasonal_amplitude"));
            VH_CFG_W_MULT = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("weekend_multiplier"));
            VH_CFG_N_PROT = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("newbie_protection_rate"));
            VH_CFG_W_SEASONAL = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("seasonal_weight"));
            VH_CFG_W_WEEKEND = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("weekend_weight"));
            VH_CFG_W_NEWBIE = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("newbie_weight"));
            VH_CFG_W_INFLATION = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("inflation_weight"));

            MemoryLayout tCtxLayout = TransferContext.layout();
            VH_TCTX_SENDER_BAL = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("sender_balance"));
            VH_TCTX_RECEIVER_BAL = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("receiver_balance"));
            VH_TCTX_INFLATION = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("inflation_rate"));
            VH_TCTX_ITEM_BASE = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("item_base_limit"));
            VH_TCTX_ITEM_GROWTH = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("item_growth_rate"));
            VH_TCTX_ITEM_MAX = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("item_max_limit"));
            VH_TCTX_SENDER_TIME = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("sender_play_time"));
            VH_TCTX_RECEIVER_TIME = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("receiver_play_time"));
            VH_TCTX_ACTIVITY_SCORE = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("sender_activity_score"));
            VH_TCTX_VELOCITY = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("sender_velocity"));

            MemoryLayout rCfgLayout = RegulatorConfig.layout();
            VH_RCFG_V_THRESHOLD = rCfgLayout.varHandle(MemoryLayout.PathElement.groupElement("velocity_threshold"));

            MemoryLayout resLayout = top.ellan.ecobridge.gen.TransferResult.layout();
            VH_RES_TAX_MICROS = resLayout.varHandle(MemoryLayout.PathElement.groupElement("final_tax_micros"));
            VH_RES_BLOCKED = resLayout.varHandle(MemoryLayout.PathElement.groupElement("is_blocked"));
            VH_RES_CODE = resLayout.varHandle(MemoryLayout.PathElement.groupElement("warning_code"));

            MemoryLayout pidLayout = PidState.layout();
            VH_PID_KP = pidLayout.varHandle(MemoryLayout.PathElement.groupElement("kp"));
            VH_PID_KI = pidLayout.varHandle(MemoryLayout.PathElement.groupElement("ki"));
            VH_PID_KD = pidLayout.varHandle(MemoryLayout.PathElement.groupElement("kd"));

        } catch (Exception e) {
            throw new RuntimeException("CRITICAL: Failed to extract VarHandles from Generated Layouts!", e);
        }
    }

    // ==================================================================================
    // 3. 初始化与 FFI 绑定
    // ==================================================================================

    public static synchronized void init(EcoBridge plugin) {
        if (STATE.get() == BridgeState.RUNNING) return;

        LIFECYCLE_LOCK.writeLock().lock();
        try {
            NativeLoader.load(plugin);
            Linker linker = Linker.nativeLinker();

            bindAllHandles(linker);

            int threads = Runtime.getRuntime().availableProcessors();
            int initStatus = (int) initThreadingMH.invokeExact(threads);
            if (initStatus != 0) {
                LogUtil.warn("Rayon 全局线程池初始化失败（可能已存在）。");
            }

            getAbiVersionMH = bind(linker, "ecobridge_abi_version", FunctionDescriptor.of(JAVA_INT));
            int abiVersion = (int) getAbiVersionMH.invokeExact();
            if (abiVersion != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException("ABI Version Mismatch! Expected: " + EXPECTED_ABI_VERSION + " Got: " + abiVersion);
            }

            try (Arena arena = Arena.ofConfined()) {
                String dataPath = plugin.getDataFolder().getAbsolutePath();
                int result = (int) initDBMH.invokeExact(arena.allocateFrom(dataPath));
                if (result != 0 && result != -3) throw new IllegalStateException("DB Init Failed: " + result);
            }

            sharedArena = Arena.ofShared();
            STATE.set(BridgeState.RUNNING);

            MemorySegment v = (MemorySegment) getVersionMH.invokeExact();
            LogUtil.info("<green>Native engine loaded! Mode: Gen-Dependent (Safe Executor). Version: " + v.reinterpret(1024).getString(0));

        } catch (Throwable e) {
            STATE.set(BridgeState.CLOSED);
            LogUtil.error("FATAL: Native Bridge failed to initialize.", e);
        } finally {
            LIFECYCLE_LOCK.writeLock().unlock();
        }
    }

    private static void bindAllHandles(Linker linker) {
        initThreadingMH = bind(linker, "ecobridge_init_threading", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        initDBMH = bind(linker, "ecobridge_init_db", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getVersionMH = bind(linker, "ecobridge_version", FunctionDescriptor.of(ADDRESS));
        shutdownDBMH = bind(linker, "ecobridge_shutdown_db", FunctionDescriptor.of(JAVA_INT));
        getHealthStatsMH = bind(linker, "ecobridge_get_health_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        pushToDuckDBMH = bind(linker, "ecobridge_log_to_duckdb", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
        queryNeffVectorizedMH = bind(linker, "ecobridge_query_neff_vectorized", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_DOUBLE, ADDRESS));
        queryNeffForKeyMH = bind(linker, "ecobridge_query_neff_for_key", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_DOUBLE, ADDRESS, ADDRESS));
        
        computePriceMH = bind(linker, "ecobridge_compute_price_humane", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS), Linker.Option.critical(true));
        calculateEpsilonMH = bind(linker, "ecobridge_calculate_epsilon", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), Linker.Option.critical(true));
        calcInflationMH = bind(linker, "ecobridge_calc_inflation", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS), Linker.Option.critical(true));
        calcStabilityMH = bind(linker, "ecobridge_calc_stability", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS), Linker.Option.critical(true));
        calcDecayMH = bind(linker, "ecobridge_calc_decay", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS), Linker.Option.critical(true));
        computeTierPriceMH = bind(linker, "ecobridge_compute_tier_price", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT, ADDRESS), Linker.Option.critical(true));
        computePriceBoundedMH = bind(linker, "ecobridge_compute_price_bounded", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS), Linker.Option.critical(true));
        
        checkTransferMH = bind(linker, "ecobridge_compute_transfer_check", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        computePidMH = bind(linker, "ecobridge_compute_pid_adjustment", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
        resetPidMH = bind(linker, "ecobridge_reset_pid_state", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        computeBatchPricesMH = bind(linker, "ecobridge_compute_batch_prices", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_DOUBLE, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        injectRemoteTradeMH = bind(linker, "inject_remote_trade", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        injectRemoteTradeForKeyMH = bind(linker, "inject_remote_trade_for_key", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        getDynamicLimitMH = bind(linker, "ecobridge_get_dynamic_limit", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
        moneyToMicrosMH = bind(linker, "ecobridge_money_to_micros", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, ADDRESS));
        microsToMoneyMH = bind(linker, "ecobridge_micros_to_money", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        computeVolatilityFromStabilityMH = bind(linker, "ecobridge_compute_volatility_from_stability", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, ADDRESS));
        computeVelocityDecayMH = bind(linker, "ecobridge_compute_velocity_decay", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE, ADDRESS));
        computeFallbackTaxMH = bind(linker, "ecobridge_compute_fallback_tax", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, ADDRESS));
        computeSettlementMH = bind(linker, "ecobridge_compute_settlement", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT, ADDRESS, ADDRESS));

        garchInitMH = bind(linker, "ecobridge_garch_init", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
        garchUpdateMH = bind(linker, "ecobridge_garch_update", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, ADDRESS));
        garchForecastMH = bind(linker, "ecobridge_garch_forecast", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        garchMultiplierMH = bind(linker, "ecobridge_garch_multiplier", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        garchFreeMH = bind(linker, "ecobridge_garch_free", FunctionDescriptor.of(JAVA_INT, ADDRESS));

        kalmanInitMH = bind(linker, "ecobridge_kalman_init", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        kalmanFilterMH = bind(linker, "ecobridge_kalman_filter", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
        kalmanVelocityMH = bind(linker, "ecobridge_kalman_velocity", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        kalmanFreeMH = bind(linker, "ecobridge_kalman_free", FunctionDescriptor.of(JAVA_INT, ADDRESS));

        arimaInitMH = bind(linker, "ecobridge_arima_init", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        arimaAddObsMH = bind(linker, "ecobridge_arima_add_obs", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE));
        arimaPredictMH = bind(linker, "ecobridge_arima_predict", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        arimaFreeMH = bind(linker, "ecobridge_arima_free", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }

    private static MethodHandle bind(Linker linker, String name, FunctionDescriptor desc, Linker.Option... options) {
        return NativeLoader.findSymbol(name)
                .map(symbol -> linker.downcallHandle(symbol, desc, options))
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
    }

    public static boolean isLoaded() {
        return STATE.get() == BridgeState.RUNNING && sharedArena != null && sharedArena.scope().isAlive();
    }

    public static synchronized void shutdown() {
        if (!STATE.compareAndSet(BridgeState.RUNNING, BridgeState.SHUTTING_DOWN)) return;
        
        LogUtil.info("Native Bridge 正在等待任务排空...");
        
        LIFECYCLE_LOCK.writeLock().lock();
        try {
            nativeLane.shutdown();
            try {
                if (shutdownDBMH != null) shutdownDBMH.invokeExact();
            } catch (Throwable ignored) {}

            if (sharedArena != null) {
                sharedArena.close();
                sharedArena = null;
            }
            
            clearMethodHandles();
            NativeLoader.unload();
            STATE.set(BridgeState.CLOSED);
            LogUtil.info("Native 内核已安全物理隔离。");
        } catch (Throwable t) {
            LogUtil.error("Native Bridge 关闭时发生异常", t);
        } finally {
            LIFECYCLE_LOCK.writeLock().unlock();
        }
    }

    private static void clearMethodHandles() {
        initThreadingMH = null; getAbiVersionMH = null; initDBMH = null; getVersionMH = null;
        getHealthStatsMH = null; shutdownDBMH = null; pushToDuckDBMH = null;
        queryNeffVectorizedMH = null; computePriceMH = null; calculateEpsilonMH = null;
        checkTransferMH = null; computePidMH = null; resetPidMH = null;
        calcInflationMH = null; calcStabilityMH = null; calcDecayMH = null;
        computeTierPriceMH = null; computePriceBoundedMH = null; computeBatchPricesMH = null;
        injectRemoteTradeMH = null; getDynamicLimitMH = null;
        injectRemoteTradeForKeyMH = null; queryNeffForKeyMH = null;
        moneyToMicrosMH = null; microsToMoneyMH = null; computeVolatilityFromStabilityMH = null;
        computeVelocityDecayMH = null; computeFallbackTaxMH = null; computeSettlementMH = null;
        garchInitMH = null; garchUpdateMH = null; garchForecastMH = null; garchMultiplierMH = null; garchFreeMH = null;
        kalmanInitMH = null; kalmanFilterMH = null; kalmanVelocityMH = null; kalmanFreeMH = null;
        arimaInitMH = null; arimaAddObsMH = null; arimaPredictMH = null; arimaFreeMH = null;
    }

    // --- 安全执行器 ---
    private static <T> T executeSafely(ThrowingSupplier<T> action, T fallback, boolean isCritical) {
        if (!LIFECYCLE_LOCK.readLock().tryLock()) return fallback;
        try {
            if (!isLoaded()) return fallback;
            return action.get();
        } catch (Throwable t) {
            if (isCritical) {
                LogUtil.severe("CRITICAL FFI PANIC: " + t.getMessage(), t);
            }
            return fallback;
        } finally {
            LIFECYCLE_LOCK.readLock().unlock();
        }
    }

    // ==================================================================================
    // 4. 核心业务方法
    // ==================================================================================

    public static CompletableFuture<NativeTransferResult> checkTransferAsync(
            MemoryConsumer ctxFiller,
            MemoryConsumer cfgFiller) {
        
        // [修复] 在异步任务中使用 Arena.ofConfined()，确保线程安全且不混用 SharedArena
        return CompletableFuture.supplyAsync(() -> executeSafely(() -> {
            try (Arena threadArena = Arena.ofConfined()) {
                // [Fix] 使用 jextract 生成的 layout()
                MemorySegment ctxSeg = threadArena.allocate(TransferContext.layout());
                MemorySegment cfgSeg = threadArena.allocate(RegulatorConfig.layout());
                MemorySegment resSeg = threadArena.allocate(top.ellan.ecobridge.gen.TransferResult.layout());

                ctxFiller.accept(ctxSeg);
                cfgFiller.accept(cfgSeg);

                int status = (int) checkTransferMH.invokeExact(resSeg, ctxSeg, cfgSeg);
                if (status != 0) throw new RuntimeException("Rust error status: " + status);

                long taxMicros = (long) VH_RES_TAX_MICROS.get(resSeg, 0L);
                double tax = taxMicros / MICROS_DIVISOR;
                
                boolean blocked = ((int) VH_RES_BLOCKED.get(resSeg, 0L)) != 0;
                int code = ((Number) VH_RES_CODE.get(resSeg, 0L)).intValue();

                return new NativeTransferResult(tax, blocked, code);
            }
        }, new NativeTransferResult(0, true, -1), true), nativeLane);
    }

    public static int checkTransferSync(MemorySegment result, MemorySegment ctx, MemorySegment cfg) {
        return executeSafely(() -> (int) checkTransferMH.invokeExact(result, ctx, cfg), -1, false);
    }

    // ... 其他计算方法 ...

    public static double computePrice(double base, double nEff, double amount, double lambda, double epsilon) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) computePriceMH.invokeExact(base, nEff, amount, lambda, epsilon, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : base;
            }
        }, base, false);
    }

    public static double computePriceBounded(double base, double neff, double amt, double lambda, double eps, double histAvg) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) computePriceBoundedMH.invokeExact(base, neff, amt, lambda, eps, histAvg, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : base;
            }
        }, base, false);
    }

    public static double calculateEpsilon(MemorySegment tradeCtx, MemorySegment marketCfg) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) calculateEpsilonMH.invokeExact(tradeCtx, marketCfg, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : 1.0;
            }
        }, 1.0, false);
    }

    public static double getDynamicLimit(long playTimeSecs, double base, double rate, double max) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) getDynamicLimitMH.invokeExact(playTimeSecs, base, rate, max, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : base;
            }
        }, base, false);
    }

    public static long moneyToMicros(double amount) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_LONG);
                int status = (int) moneyToMicrosMH.invokeExact(amount, out);
                return status == 0 ? out.get(JAVA_LONG, 0L) : 0L;
            }
        }, 0L, false);
    }

    public static double microsToMoney(long micros) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) microsToMoneyMH.invokeExact(micros, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : 0.0;
            }
        }, 0.0, false);
    }

    public static double computeVolatilityFromStability(double stability) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) computeVolatilityFromStabilityMH.invokeExact(stability, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : 1.0;
            }
        }, 1.0, false);
    }

    public static double computeVelocityDecay(double velocity, long deltaMs, double halfLifeMs) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) computeVelocityDecayMH.invokeExact(velocity, deltaMs, halfLifeMs, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : velocity;
            }
        }, velocity, false);
    }

    public static double computeFallbackTax(double amount) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) computeFallbackTaxMH.invokeExact(amount, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : amount * 0.05;
            }
        }, amount * 0.05, false);
    }

    public record SettlementMath(double tax, double netAmount) {}

    public static SettlementMath computeSettlement(double amount, double suggestedTax, boolean bypassTax) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment outTax = arena.allocate(JAVA_DOUBLE);
                MemorySegment outNet = arena.allocate(JAVA_DOUBLE);
                int status = (int) computeSettlementMH.invokeExact(amount, suggestedTax, bypassTax ? 1 : 0, outTax, outNet);
                if (status != 0) {
                    double taxFallback = bypassTax ? 0.0 : Math.max(0.0, Math.min(suggestedTax, amount));
                    return new SettlementMath(taxFallback, amount - taxFallback);
                }
                return new SettlementMath(outTax.get(JAVA_DOUBLE, 0L), outNet.get(JAVA_DOUBLE, 0L));
            }
        }, new SettlementMath(0.0, amount), false);
    }

    public static void injectRemoteTrade(long amountMicros) {
        executeSafely(() -> {
            injectRemoteTradeMH.invokeExact(amountMicros);
            return null;
        }, null, false);
    }

    public static void injectRemoteTradeForKey(String marketKey, long amountMicros) {
        if (marketKey == null || marketKey.isBlank()) return;
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                injectRemoteTradeForKeyMH.invokeExact(arena.allocateFrom(marketKey), amountMicros);
            }
            return null;
        }, null, false);
    }

    public static void pushToDuckDB(long ts, String uuid, double amount, double bal, String meta) {
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                long amountMicros = moneyToMicros(amount);
                long balMicros = moneyToMicros(bal);
                pushToDuckDBMH.invokeExact(ts, arena.allocateFrom(uuid), amountMicros, balMicros, arena.allocateFrom(meta));
            }
            return null;
        }, null, false);
    }
    
    public static void getHealthStats(long[] results) {
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment total = arena.allocate(JAVA_LONG);
                MemorySegment dropped = arena.allocate(JAVA_LONG);
                if ((int)getHealthStatsMH.invokeExact(total, dropped) == 0) {
                    results[0] = total.get(JAVA_LONG, 0);
                    results[1] = dropped.get(JAVA_LONG, 0);
                }
            }
            return null;
        }, null, false);
    }

    public static void computeBatchPrices(long count, double neff, MemorySegment ctxArr, MemorySegment cfgArr, MemorySegment histAvgs, MemorySegment lambdas, MemorySegment results) {
        executeSafely(() -> {
            computeBatchPricesMH.invokeExact(count, neff, ctxArr, cfgArr, histAvgs, lambdas, results);
            return null;
        }, null, false);
    }

    public static double calcInflation(double heat, double m1) {
         return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) calcInflationMH.invokeExact(heat, m1, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : 0.0;
            }
        }, 0.0, false);
    }

    public static double calcStability(long lastTs, long currTs) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) calcStabilityMH.invokeExact(lastTs, currTs, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : 1.0;
            }
        }, 1.0, false);
    }

    public static double calcDecay(double heat, double rate) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) calcDecayMH.invokeExact(heat, rate, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : 0.0;
            }
        }, 0.0, false);
    }
    
    public static double computeTierPrice(double base, double qty, boolean isSell) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) computeTierPriceMH.invokeExact(base, qty, isSell ? 1 : 0, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : base;
            }
        }, base, false);
    }
    
    public static double queryNeffVectorized(long now, double tau) {
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) queryNeffVectorizedMH.invokeExact(now, tau, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : 0.0;
            }
        }, 0.0, false);
    }

    public static double queryNeffForKey(long now, double tau, String marketKey) {
        if (marketKey == null || marketKey.isBlank()) return 0.0;
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) queryNeffForKeyMH.invokeExact(now, tau, arena.allocateFrom(marketKey), out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : 0.0;
            }
        }, 0.0, false);
    }
    
    public static double computePidAdjustment(MemorySegment pidPtr, double target, double current, double dt, double inflation, double heat) {
         return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) computePidMH.invokeExact(pidPtr, target, current, dt, inflation, heat, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0) : 0.0;
            }
        }, 0.0, false);
    }
    
    public static void resetPidState(MemorySegment pidPtr) {
        executeSafely(() -> {
            resetPidMH.invokeExact(pidPtr);
            return null;
        }, null, false);
    }

    // ==================================================================================
    // 7. GARCH 波动率建模
    // ==================================================================================

    public static void garchInit(String key, double alpha, double beta, double omega) {
        if (key == null) key = "__global__";
        String finalKey = key;
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                garchInitMH.invokeExact(arena.allocateFrom(finalKey), alpha, beta, omega);
            }
            return null;
        }, null, false);
    }

    public static double garchUpdate(String key, double returnVal) {
        if (key == null) key = "__global__";
        String finalKey = key;
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) garchUpdateMH.invokeExact(arena.allocateFrom(finalKey), returnVal, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : 0.0;
            }
        }, 0.0, false);
    }

    public static double garchForecast(String key, int steps) {
        if (key == null) key = "__global__";
        String finalKey = key;
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) garchForecastMH.invokeExact(arena.allocateFrom(finalKey), steps, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : 0.0;
            }
        }, 0.0, false);
    }

    public static double garchVolatilityMultiplier(String key) {
        if (key == null) key = "__global__";
        String finalKey = key;
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) garchMultiplierMH.invokeExact(arena.allocateFrom(finalKey), out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : 1.0;
            }
        }, 1.0, false);
    }

    public static void garchFree(String key) {
        if (key == null) key = "__global__";
        String finalKey = key;
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                garchFreeMH.invokeExact(arena.allocateFrom(finalKey));
            }
            return null;
        }, null, false);
    }

    // ==================================================================================
    // 8. 卡尔曼滤波
    // ==================================================================================

    public static void kalmanInit(String key) {
        if (key == null) key = "__global__";
        String finalKey = key;
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                kalmanInitMH.invokeExact(arena.allocateFrom(finalKey));
            }
            return null;
        }, null, false);
    }

    /**
     * Apply Kalman predict + update cycle.
     * @return filtered (posterior) position estimate
     */
    public static double kalmanFilter(String key, double measurement, double dt) {
        if (key == null) key = "__global__";
        String finalKey = key;
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) kalmanFilterMH.invokeExact(arena.allocateFrom(finalKey), measurement, dt, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : measurement;
            }
        }, measurement, false);
    }

    /**
     * @return current filtered velocity (rate of change) estimate
     */
    public static double kalmanVelocity(String key) {
        if (key == null) key = "__global__";
        String finalKey = key;
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) kalmanVelocityMH.invokeExact(arena.allocateFrom(finalKey), out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : 0.0;
            }
        }, 0.0, false);
    }

    public static void kalmanFree(String key) {
        if (key == null) key = "__global__";
        String finalKey = key;
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                kalmanFreeMH.invokeExact(arena.allocateFrom(finalKey));
            }
            return null;
        }, null, false);
    }

    // ==================================================================================
    // 9. ARIMA 时序预测
    // ==================================================================================

    public static void arimaInit(String key, int p, int d) {
        if (key == null) key = "__global__";
        String finalKey = key;
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                arimaInitMH.invokeExact(arena.allocateFrom(finalKey), p, d);
            }
            return null;
        }, null, false);
    }

    public static void arimaAddObservation(String key, double value) {
        if (key == null) key = "__global__";
        String finalKey = key;
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                arimaAddObsMH.invokeExact(arena.allocateFrom(finalKey), value);
            }
            return null;
        }, null, false);
    }

    /**
     * @return H-step ahead prediction
     */
    public static double arimaPredict(String key, int horizon) {
        if (key == null) key = "__global__";
        String finalKey = key;
        return executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE);
                int status = (int) arimaPredictMH.invokeExact(arena.allocateFrom(finalKey), horizon, out);
                return status == 0 ? out.get(JAVA_DOUBLE, 0L) : 0.0;
            }
        }, 0.0, false);
    }

    public static void arimaFree(String key) {
        if (key == null) key = "__global__";
        String finalKey = key;
        executeSafely(() -> {
            try (Arena arena = Arena.ofConfined()) {
                arimaFreeMH.invokeExact(arena.allocateFrom(finalKey));
            }
            return null;
        }, null, false);
    }

    @FunctionalInterface public interface MemoryConsumer { void accept(MemorySegment segment) throws Throwable; }
    @FunctionalInterface public interface ThrowingSupplier<T> { T get() throws Throwable; }

    // ==================================================================================
    // 5. Layouts 代理类 (Fix for TransferManager & PriceComputeEngine)
    // ==================================================================================

    /**
     * 将生成的 Layout 重新暴露给外部业务类使用。
     * 这解决了 "Layouts cannot be resolved" 的编译错误。
     */
    public static class Layouts {
        public static final MemoryLayout TRADE_CONTEXT = TradeContext.layout();
        public static final MemoryLayout MARKET_CONFIG = MarketConfig.layout();
        public static final MemoryLayout TRANSFER_CONTEXT = TransferContext.layout();
        public static final MemoryLayout REGULATOR_CONFIG = RegulatorConfig.layout();
        public static final MemoryLayout PID_STATE = PidState.layout();
        public static final MemoryLayout TRANSFER_RESULT = top.ellan.ecobridge.gen.TransferResult.layout();
    }
}
