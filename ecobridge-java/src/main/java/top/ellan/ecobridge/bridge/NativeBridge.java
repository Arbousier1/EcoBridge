package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.gen.*; // jextract 生成的类
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * NativeBridge (API Layer v0.9.5 - Final Stable Edition)
 * * 核心优化：
 * 1. 移除了 generateBindings 中的 --library 参数，改为由 NativeLoader 手动加载。
 * 2. 增强了对 jextract 生成类型的兼容性（使用 Number 转型）。
 * 3. 严格管理 Arena 声明周期，确保无原生内存泄漏。
 */
public class NativeBridge {

    // ABI 版本号：必须与 Rust 侧定义严格一致
    private static final int EXPECTED_ABI_VERSION = 0x0009_0000;
    private static Arena bridgeArena;

    // --- 风控状态码 ---
    public static final int CODE_NORMAL = 0;
    public static final int CODE_WARNING_HIGH_RISK = 1;
    public static final int CODE_BLOCK_REVERSE_FLOW = 2;
    public static final int CODE_BLOCK_INJECTION = 3;
    public static final int CODE_BLOCK_INSUFFICIENT_FUNDS = 4;
    public static final int CODE_BLOCK_VELOCITY_LIMIT = 5;

    // --- Method Handles (volatile 保证多线程可见性) ---
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

    // --- VarHandles (通过 jextract 布局动态绑定偏移) ---
    public static final VarHandle VH_CTX_BASE_PRICE;
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

    public static final VarHandle VH_TCTX_ACTIVITY_SCORE;
    public static final VarHandle VH_TCTX_VELOCITY;
    public static final VarHandle VH_RCFG_V_THRESHOLD;

    private static final VarHandle VH_RES_TAX;
    private static final VarHandle VH_RES_BLOCKED;
    private static final VarHandle VH_RES_CODE;

    static {
        try {
            // 1. 获取 TradeContext 字段偏移
            GroupLayout ctxLayout = TradeContext.layout();
            VH_CTX_BASE_PRICE = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("base_price"));
            VH_CTX_CURR_AMT = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("current_amount"));
            VH_CTX_INF_RATE = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("inflation_rate"));
            VH_CTX_TIMESTAMP = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("current_timestamp"));
            VH_CTX_PLAY_TIME = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("play_time_seconds"));
            VH_CTX_TIMEZONE_OFFSET = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("timezone_offset"));
            VH_CTX_NEWBIE_MASK = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("newbie_mask"));
            VH_CTX_MARKET_HEAT = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("market_heat"));
            VH_CTX_ECO_SAT = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("eco_saturation"));

            // 2. 获取 MarketConfig 字段偏移
            GroupLayout cfgLayout = MarketConfig.layout();
            VH_CFG_LAMBDA = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("base_lambda"));
            VH_CFG_VOLATILITY = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("volatility_factor"));
            VH_CFG_S_AMP = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("seasonal_amplitude"));
            VH_CFG_W_MULT = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("weekend_multiplier"));
            VH_CFG_N_PROT = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("newbie_protection_rate"));
            VH_CFG_W_SEASONAL = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("seasonal_weight"));
            VH_CFG_W_WEEKEND = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("weekend_weight"));
            VH_CFG_W_NEWBIE = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("newbie_weight"));
            VH_CFG_W_INFLATION = cfgLayout.varHandle(MemoryLayout.PathElement.groupElement("inflation_weight"));

            // 3. 获取 Transfer 逻辑相关字段
            GroupLayout tCtxLayout = TransferContext.layout();
            VH_TCTX_ACTIVITY_SCORE = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("sender_activity_score"));
            VH_TCTX_VELOCITY = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("sender_velocity"));

            GroupLayout rCfgLayout = RegulatorConfig.layout();
            VH_RCFG_V_THRESHOLD = rCfgLayout.varHandle(MemoryLayout.PathElement.groupElement("velocity_threshold"));

            GroupLayout resLayout = top.ellan.ecobridge.gen.TransferResult.layout();
            VH_RES_TAX = resLayout.varHandle(MemoryLayout.PathElement.groupElement("final_tax"));
            VH_RES_BLOCKED = resLayout.varHandle(MemoryLayout.PathElement.groupElement("is_blocked"));
            VH_RES_CODE = resLayout.varHandle(MemoryLayout.PathElement.groupElement("warning_code"));

        } catch (Exception e) {
            throw new RuntimeException("CRITICAL: FFM Memory Layout Initialization Failed! Check your Rust structs.", e);
        }
    }

    public static synchronized void init(EcoBridge plugin) {
        if (isLoaded()) return;

        try {
            // 步骤 1：调用 NativeLoader 手动加载 .dll/.so
            NativeLoader.load(plugin);
            Linker linker = Linker.nativeLinker();

            // 步骤 2：ABI 版本握手
            getAbiVersionMH = bind(linker, "ecobridge_abi_version", FunctionDescriptor.of(JAVA_INT));
            int nativeVersion = (int) getAbiVersionMH.invokeExact();
            if (nativeVersion != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException(String.format("ABI Mismatch! Java=0x%08X, Native=0x%08X. Please rebuild native libs.", EXPECTED_ABI_VERSION, nativeVersion));
            }

            // 步骤 3：批量绑定 C 函数符号
            initDBMH = bind(linker, "ecobridge_init_db", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            getVersionMH = bind(linker, "ecobridge_version", FunctionDescriptor.of(ADDRESS));
            getHealthStatsMH = bind(linker, "ecobridge_get_health_stats", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            shutdownDBMH = bind(linker, "ecobridge_shutdown_db", FunctionDescriptor.of(JAVA_INT));
            pushToDuckDBMH = bind(linker, "ecobridge_log_to_duckdb", FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
            queryNeffVectorizedMH = bind(linker, "ecobridge_query_neff_vectorized", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE));
            computePriceMH = bind(linker, "ecobridge_compute_price_humane", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            calculateEpsilonMH = bind(linker, "ecobridge_calculate_epsilon", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS));
            checkTransferMH = bind(linker, "ecobridge_compute_transfer_check", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
            computePidMH = bind(linker, "ecobridge_compute_pid_adjustment", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            resetPidMH = bind(linker, "ecobridge_reset_pid_state", FunctionDescriptor.ofVoid(ADDRESS));
            calcInflationMH = bind(linker, "ecobridge_calc_inflation", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            calcStabilityMH = bind(linker, "ecobridge_calc_stability", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_LONG));
            calcDecayMH = bind(linker, "ecobridge_calc_decay", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            computeTierPriceMH = bind(linker, "ecobridge_compute_tier_price", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_BOOLEAN));
            computePriceBoundedMH = bind(linker, "ecobridge_compute_price_bounded", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            computeBatchPricesMH = bind(linker, "ecobridge_compute_batch_prices", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_DOUBLE, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

            // 步骤 4：初始化原生数据库
            try (Arena arena = Arena.ofConfined()) {
                String dataPath = plugin.getDataFolder().getAbsolutePath();
                int result = (int) initDBMH.invokeExact(arena.allocateFrom(dataPath));
                if (result != 0 && result != -3) { // -3 通常代表数据库已处于打开状态
                    throw new IllegalStateException("Rust Engine DB Initialization Failed: Error Code " + result);
                }
            }

            bridgeArena = Arena.ofShared(); // 成功标志
            MemorySegment v = (MemorySegment) getVersionMH.invokeExact();
            LogUtil.info("<green>Native engine loaded successfully! Version: " + v.getString(0));

        } catch (Throwable e) {
            LogUtil.error("FATAL: Native Bridge failed to initialize.", e);
            shutdown();
        }
    }

    private static MethodHandle bind(Linker linker, String name, FunctionDescriptor desc) {
        return NativeLoader.findSymbol(name)
                .map(symbol -> linker.downcallHandle(symbol, desc))
                .orElseThrow(() -> new UnsatisfiedLinkError("CRITICAL: Failed to find symbol in native library: " + name));
    }

    public static boolean isLoaded() {
        return NativeLoader.isReady() && bridgeArena != null;
    }

    public static synchronized void shutdown() {
        if (isLoaded()) {
            try {
                if (shutdownDBMH != null) shutdownDBMH.invokeExact();
            } catch (Throwable t) {
                LogUtil.error("Error during native database shutdown.", t);
            } finally {
                if (bridgeArena != null) {
                    bridgeArena.close();
                    bridgeArena = null;
                }
                NativeLoader.unload();
                clearMethodHandles();
            }
        }
    }

    private static void clearMethodHandles() {
        getAbiVersionMH = null; initDBMH = null; getVersionMH = null;
        getHealthStatsMH = null; shutdownDBMH = null; pushToDuckDBMH = null;
        queryNeffVectorizedMH = null; computePriceMH = null; calculateEpsilonMH = null;
        checkTransferMH = null; computePidMH = null; resetPidMH = null;
        calcInflationMH = null; calcStabilityMH = null; calcDecayMH = null;
        computeTierPriceMH = null; computePriceBoundedMH = null; computeBatchPricesMH = null;
    }

    // =================================================================
    // API Wrappers (带有类型安全保护的 FFI 调用)
    // =================================================================

    public static double calcInflation(double heat, double m1) {
        if (!isLoaded()) return 0.0;
        try { return (double) calcInflationMH.invokeExact(heat, m1); } catch (Throwable t) { return 0.0; }
    }

    public static double calcStability(long lastTs, long currTs) {
        if (!isLoaded()) return 1.0;
        try { return (double) calcStabilityMH.invokeExact(lastTs, currTs); } catch (Throwable t) { return 1.0; }
    }

    public static double calcDecay(double heat, double rate) {
        if (!isLoaded()) return 0.0;
        try { return (double) calcDecayMH.invokeExact(heat, rate); } catch (Throwable t) { return 0.0; }
    }

    public static double computeTierPrice(double base, double qty, boolean isSell) {
        if (!isLoaded()) return base;
        try { return (double) computeTierPriceMH.invokeExact(base, qty, isSell); } catch (Throwable t) { return base; }
    }

    public static double computePriceBounded(double base, double neff, double amt, double lambda, double eps, double histAvg) {
        if (!isLoaded()) return base;
        try { return (double) computePriceBoundedMH.invokeExact(base, neff, amt, lambda, eps, histAvg); } catch (Throwable t) { return base; }
    }

    public static void getHealthStats(MemorySegment outTotal, MemorySegment outDropped) {
        if (!isLoaded()) return;
        try { getHealthStatsMH.invokeExact(outTotal, outDropped); } catch (Throwable t) {}
    }

    public static void resetPidState(MemorySegment pidPtr) {
        if (!isLoaded()) return;
        try { resetPidMH.invokeExact(pidPtr); } catch (Throwable t) { LogUtil.error("PID reset failed", t); }
    }

    public static double computePrice(double base, double nEff, double amount, double lambda, double epsilon) {
        if (!isLoaded()) return base;
        try { return (double) computePriceMH.invokeExact(base, nEff, amount, lambda, epsilon); } catch (Throwable t) { return base; }
    }

    public static double queryNeffVectorized(long now, double tau) {
        if (!isLoaded()) return 0.0;
        try { return (double) queryNeffVectorizedMH.invokeExact(now, tau); } catch (Throwable t) { return 0.0; }
    }

    public static void pushToDuckDB(long ts, String uuid, double amount, double bal, String meta) {
        if (!isLoaded()) return;
        try (Arena arena = Arena.ofConfined()) {
            pushToDuckDBMH.invokeExact(ts, arena.allocateFrom(uuid), amount, bal, arena.allocateFrom(meta));
        } catch (Throwable t) { LogUtil.error("DuckDB logging failed", t); }
    }

    public static double calculateEpsilon(MemorySegment tradeCtx, MemorySegment marketCfg) {
        if (!isLoaded()) return 1.0;
        try { return (double) calculateEpsilonMH.invokeExact(tradeCtx, marketCfg); } catch (Throwable t) { return 1.0; }
    }

    public static TransferResult checkTransfer(MemorySegment ctxSeg, MemorySegment cfgSeg) {
        if (!isLoaded()) return new TransferResult(0.0, true, -1);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment resultSeg = arena.allocate(Layouts.TRANSFER_RESULT);
            checkTransferMH.invokeExact(resultSeg, ctxSeg, cfgSeg);
            
            // ✅ 安全转换逻辑：jextract 可能会将 bool 映射为 byte
            double tax = ((Number) VH_RES_TAX.get(resultSeg)).doubleValue();
            boolean isBlocked = ((Number) VH_RES_BLOCKED.get(resultSeg)).intValue() != 0;
            int warningCode = ((Number) VH_RES_CODE.get(resultSeg)).intValue();
            
            return new TransferResult(tax, isBlocked, warningCode);
        } catch (Throwable t) {
            LogUtil.error("Critical error in checkTransfer FFI call", t);
            return new TransferResult(0.0, true, -2);
        }
    }

    public static double computePidAdjustment(MemorySegment pidPtr, double target, double current, double dt, double inflation, double heat) {
        if (!isLoaded()) return 0.0;
        try { return (double) computePidMH.invokeExact(pidPtr, target, current, dt, inflation, heat); } catch (Throwable t) { return 0.0; }
    }

    public static void computeBatchPrices(long count, double neff, MemorySegment ctxArr, MemorySegment cfgArr, MemorySegment histAvgs, MemorySegment lambdas, MemorySegment results) {
        if (!isLoaded()) return;
        try {
            computeBatchPricesMH.invokeExact(count, neff, ctxArr, cfgArr, histAvgs, lambdas, results);
        } catch (Throwable t) {
            LogUtil.error("SIMD Batch calculation failed.", t);
        }
    }

    // =================================================================
    // 布局常量类：供其他 Manager 分配内存使用
    // =================================================================
    public static class Layouts {
        public static final GroupLayout TRADE_CONTEXT = TradeContext.layout();
        public static final GroupLayout MARKET_CONFIG = MarketConfig.layout();
        public static final GroupLayout TRANSFER_CONTEXT = TransferContext.layout();
        public static final GroupLayout REGULATOR_CONFIG = RegulatorConfig.layout();
        public static final GroupLayout PID_STATE = PidState.layout();
        public static final GroupLayout TRANSFER_RESULT = top.ellan.ecobridge.gen.TransferResult.layout();
    }

    public record TransferResult(double tax, boolean isBlocked, int warningCode) {}
}