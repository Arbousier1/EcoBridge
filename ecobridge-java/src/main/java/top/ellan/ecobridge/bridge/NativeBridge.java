package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.gen.*; // jextract 生成的类
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * NativeBridge (API Layer v0.9.4 - Memory-Safe & Integrated Edition)
 * <p>
 * 修复说明：
 * 1. 适配 NativeLoader：使用 findSymbol 替代 getLookup 以符合你现有的 NativeLoader 接口。
 * 2. 补全 Layouts：显式定义所有被 PriceComputeEngine 和 TransferManager 引用的静态布局常量。
 * 3. 消除警告：通过实现所有 Wrapper 方法，确保所有 MethodHandle 字段均被正确引用。
 */
public class NativeBridge {

    private static final int EXPECTED_ABI_VERSION = 0x0009_0000;
    private static Arena bridgeArena;

    // --- 风控状态码 ---
    public static final int CODE_NORMAL = 0;
    public static final int CODE_WARNING_HIGH_RISK = 1;
    public static final int CODE_BLOCK_REVERSE_FLOW = 2;
    public static final int CODE_BLOCK_INJECTION = 3;
    public static final int CODE_BLOCK_INSUFFICIENT_FUNDS = 4;
    public static final int CODE_BLOCK_VELOCITY_LIMIT = 5;

    // --- Method Handles (volatile 保证可见性) ---
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

    // --- VarHandles (对齐 TradeContext 内存布局) ---
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
            // 1. 初始化所有内存偏移 Handle
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
            throw new RuntimeException("Layout Initialization Failed", e);
        }
    }

    public static synchronized void init(EcoBridge plugin) {
        if (isLoaded()) return;

        try {
            NativeLoader.load(plugin);
            // 这里我们不再新建 Arena，因为 NativeLoader 已经管理了一个 Shared Arena
            // 我们只需将符号绑定到 MethodHandle 上。
            Linker linker = Linker.nativeLinker();

            // 1. ABI 版本检查
            getAbiVersionMH = bind(linker, "ecobridge_abi_version", FunctionDescriptor.of(JAVA_INT));
            int nativeVersion = (int) getAbiVersionMH.invokeExact();
            if (nativeVersion != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException(String.format("ABI Mismatch: Java=0x%08X, Native=0x%08X", EXPECTED_ABI_VERSION, nativeVersion));
            }

            // 2. 绑定核心函数
            initDBMH = bind(linker, "ecobridge_init_db", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            getVersionMH = bind(linker, "ecobridge_version", FunctionDescriptor.of(ADDRESS));
            getHealthStatsMH = bind(linker, "ecobridge_get_health_stats", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            shutdownDBMH = bind(linker, "ecobridge_shutdown_db", FunctionDescriptor.of(JAVA_INT));
            pushToDuckDBMH = bind(linker, "ecobridge_log_to_duckdb", FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
            queryNeffVectorizedMH = bind(linker, "ecobridge_query_neff_vectorized", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE));
            computePriceMH = bind(linker, "ecobridge_compute_price_humane", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
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

            // 3. 初始化数据库
            try (Arena arena = Arena.ofConfined()) {
                String dataPath = plugin.getDataFolder().getAbsolutePath();
                int result = (int) initDBMH.invokeExact(arena.allocateFrom(dataPath));
                if (result != 0 && result != -3) {
                    throw new IllegalStateException("Rust DB Init Failed: " + result);
                }
            }

            // 建立一个伪 Arena 标记加载状态
            bridgeArena = Arena.ofShared(); 
            
            MemorySegment v = (MemorySegment) getVersionMH.invokeExact();
            LogUtil.info("<green>Native engine loaded: " + v.getString(0));

        } catch (Throwable e) {
            LogUtil.error("Native Fatal Error", e);
            shutdown();
        }
    }

    private static MethodHandle bind(Linker linker, String name, FunctionDescriptor desc) {
        // ✅ 适配 NativeLoader：直接调用 findSymbol 接口
        MemorySegment symbol = NativeLoader.findSymbol(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return linker.downcallHandle(symbol, desc);
    }

    public static boolean isLoaded() {
        return NativeLoader.isReady() && bridgeArena != null;
    }

    public static synchronized void shutdown() {
        if (isLoaded()) {
            if (shutdownDBMH != null) {
                try { shutdownDBMH.invokeExact(); } catch (Throwable t) {}
            }
            if (bridgeArena != null) {
                bridgeArena.close();
                bridgeArena = null;
            }
            NativeLoader.unload();
            clearMethodHandles();
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
    // API Wrappers (确保所有 MethodHandle 都被调用，消除编译警告)
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
        } catch (Throwable t) { LogUtil.error("DuckDB log failed", t); }
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
            return new TransferResult((double) VH_RES_TAX.get(resultSeg), ((int) VH_RES_BLOCKED.get(resultSeg)) != 0, (int) VH_RES_CODE.get(resultSeg));
        } catch (Throwable t) {
            LogUtil.error("checkTransfer FFI 调用异常", t);
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
            LogUtil.error("SIMD 批量计算失败", t);
        }
    }

    // =================================================================
    // ✅ 修复：补全被外部 Manager 引用的所有布局常量
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