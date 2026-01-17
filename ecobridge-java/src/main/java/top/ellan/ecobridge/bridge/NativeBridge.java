package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.gen.*; // jextract 生成的类
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * NativeBridge (API Layer v0.9.2 - Batch SIMD Edition)
 * 负责 Java 方法 <-> Rust 符号的映射，以及内存布局定义。
 * 
 * 更新日志：
 * 1. 升级 ABI 版本至 0x0009_0000（保持兼容）。
 * 2. 对齐自适应 PID 接口，增加财富流速 (market_heat) 参数。
 * 3. 映射宏观调控上下文因子。
 * 4. 【新增】支持 SIMD 批量价格计算：ecobridge_compute_batch_prices
 */
public class NativeBridge {

    // ABI 版本（v0.9.x 兼容）
    private static final int EXPECTED_ABI_VERSION = 0x0009_0000;

    // --- 风控状态码 ---
    public static final int CODE_NORMAL = 0;
    public static final int CODE_WARNING_HIGH_RISK = 1;
    public static final int CODE_BLOCK_REVERSE_FLOW = 2;
    public static final int CODE_BLOCK_INJECTION = 3;
    public static final int CODE_BLOCK_INSUFFICIENT_FUNDS = 4;
    public static final int CODE_BLOCK_VELOCITY_LIMIT = 5;

    // --- Method Handles ---
    private static MethodHandle getAbiVersionMH;
    private static MethodHandle initDBMH;
    private static MethodHandle getVersionMH;
    private static MethodHandle getHealthStatsMH;
    private static MethodHandle shutdownDBMH;
    private static MethodHandle pushToDuckDBMH;
    private static MethodHandle queryNeffVectorizedMH;
    private static MethodHandle computePriceMH; 
    private static MethodHandle calculateEpsilonMH;
    private static MethodHandle checkTransferMH;
    private static MethodHandle computePidMH;
    private static MethodHandle resetPidMH;

    private static MethodHandle calcInflationMH;
    private static MethodHandle calcStabilityMH;
    private static MethodHandle calcDecayMH;
    private static MethodHandle computeTierPriceMH;
    private static MethodHandle computePriceBoundedMH;

    // 【新增】批处理接口
    private static MethodHandle computeBatchPricesMH;

    // --- VarHandles (TradeContext) ---
    public static final VarHandle VH_CTX_BASE_PRICE;
    public static final VarHandle VH_CTX_CURR_AMT;
    public static final VarHandle VH_CTX_INF_RATE;
    public static final VarHandle VH_CTX_TIMESTAMP;
    public static final VarHandle VH_CTX_PLAY_TIME;
    public static final VarHandle VH_CTX_TIMEZONE_OFFSET;
    public static final VarHandle VH_CTX_NEWBIE_MASK;
    public static final VarHandle VH_CTX_MARKET_HEAT;
    public static final VarHandle VH_CTX_ECO_SAT;

    // --- VarHandles (MarketConfig) ---
    public static final VarHandle VH_CFG_LAMBDA;
    public static final VarHandle VH_CFG_VOLATILITY;
    public static final VarHandle VH_CFG_S_AMP;
    public static final VarHandle VH_CFG_W_MULT;
    public static final VarHandle VH_CFG_N_PROT;
    public static final VarHandle VH_CFG_W_SEASONAL;
    public static final VarHandle VH_CFG_W_WEEKEND;
    public static final VarHandle VH_CFG_W_NEWBIE;
    public static final VarHandle VH_CFG_W_INFLATION;

    // --- VarHandles (TransferContext) ---
    public static final VarHandle VH_TCTX_ACTIVITY_SCORE;
    public static final VarHandle VH_TCTX_VELOCITY;

    // --- VarHandles (RegulatorConfig) ---
    public static final VarHandle VH_RCFG_V_THRESHOLD;

    // --- VarHandles (TransferResult) ---
    private static final VarHandle VH_RES_TAX;
    private static final VarHandle VH_RES_BLOCKED;
    private static final VarHandle VH_RES_CODE;

    static {
        try {
            // 1. TradeContext 初始化 (对齐 Rust v0.9.1+)
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

            // 2. MarketConfig 初始化
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

            // 3. TransferContext & RegulatorConfig 初始化
            GroupLayout tCtxLayout = TransferContext.layout();
            VH_TCTX_ACTIVITY_SCORE = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("sender_activity_score"));
            VH_TCTX_VELOCITY = tCtxLayout.varHandle(MemoryLayout.PathElement.groupElement("sender_velocity"));

            GroupLayout rCfgLayout = RegulatorConfig.layout();
            VH_RCFG_V_THRESHOLD = rCfgLayout.varHandle(MemoryLayout.PathElement.groupElement("velocity_threshold"));

            // 4. TransferResult 初始化
            GroupLayout resLayout = top.ellan.ecobridge.gen.TransferResult.layout();
            VH_RES_TAX = resLayout.varHandle(MemoryLayout.PathElement.groupElement("final_tax"));
            VH_RES_BLOCKED = resLayout.varHandle(MemoryLayout.PathElement.groupElement("is_blocked"));
            VH_RES_CODE = resLayout.varHandle(MemoryLayout.PathElement.groupElement("warning_code"));
        } catch (Exception e) {
            throw new RuntimeException("Layout Initialization Failed", e);
        }
    }

    public static void init(EcoBridge plugin) {
        if (NativeLoader.isReady()) return;

        try {
            NativeLoader.load(plugin);
            Linker linker = Linker.nativeLinker();

            // 1. ABI 版本检查
            getAbiVersionMH = linker.downcallHandle(
                findOrThrow("ecobridge_abi_version"),
                FunctionDescriptor.of(JAVA_INT)
            );

            int nativeVersion = (int) getAbiVersionMH.invokeExact();
            if (nativeVersion != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException(String.format("ABI Mismatch: Java=0x%08X, Native=0x%08X", EXPECTED_ABI_VERSION, nativeVersion));
            }

            // 2. 核心函数绑定
            initDBMH = linker.downcallHandle(findOrThrow("ecobridge_init_db"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            getVersionMH = linker.downcallHandle(findOrThrow("ecobridge_version"), FunctionDescriptor.of(ADDRESS));
            getHealthStatsMH = linker.downcallHandle(findOrThrow("ecobridge_get_health_stats"), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            shutdownDBMH = linker.downcallHandle(findOrThrow("ecobridge_shutdown_db"), FunctionDescriptor.of(JAVA_INT));
            pushToDuckDBMH = linker.downcallHandle(findOrThrow("ecobridge_log_to_duckdb"), FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
            queryNeffVectorizedMH = linker.downcallHandle(findOrThrow("ecobridge_query_neff_vectorized"), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE));
            computePriceMH = linker.downcallHandle(findOrThrow("ecobridge_compute_price_humane"), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            calculateEpsilonMH = linker.downcallHandle(findOrThrow("ecobridge_calculate_epsilon"), FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS));
            
            checkTransferMH = linker.downcallHandle(
                findOrThrow("ecobridge_compute_transfer_check"),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
            );
            
            computePidMH = linker.downcallHandle(
                findOrThrow("ecobridge_compute_pid_adjustment"), 
                FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE)
            );
            
            resetPidMH = linker.downcallHandle(findOrThrow("ecobridge_reset_pid_state"), FunctionDescriptor.ofVoid(ADDRESS));

            // 3. 数学下沉函数
            calcInflationMH = linker.downcallHandle(findOrThrow("ecobridge_calc_inflation"), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            calcStabilityMH = linker.downcallHandle(findOrThrow("ecobridge_calc_stability"), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_LONG));
            calcDecayMH = linker.downcallHandle(findOrThrow("ecobridge_calc_decay"), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            computeTierPriceMH = linker.downcallHandle(findOrThrow("ecobridge_compute_tier_price"), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_BOOLEAN));
            computePriceBoundedMH = linker.downcallHandle(findOrThrow("ecobridge_compute_price_bounded"), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));

            // 【新增】批量价格计算接口
            computeBatchPricesMH = linker.downcallHandle(
                findOrThrow("ecobridge_compute_batch_prices"),
                FunctionDescriptor.ofVoid(
                    JAVA_LONG,    // count
                    JAVA_DOUBLE,  // neff
                    ADDRESS,      // TradeContext array
                    ADDRESS,      // MarketConfig array
                    ADDRESS,      // histAvgs double array
                    ADDRESS,      // lambdas double array
                    ADDRESS       // output prices double array
                )
            );

            // 4. 初始化数据库
            try (Arena arena = Arena.ofConfined()) {
                String dataPath = plugin.getDataFolder().getAbsolutePath();
                int result = (int) initDBMH.invokeExact(arena.allocateFrom(dataPath));
                if (result != 0 && result != -3) {
                    throw new IllegalStateException("Rust DB Init Failed: " + result);
                }
            }

            MemorySegment v = (MemorySegment) getVersionMH.invokeExact();
            LogUtil.info("<green>Native engine loaded: " + v.getString(0));

        } catch (Throwable e) {
            LogUtil.error("Native Fatal Error", e);
            shutdown();
        }
    }

    private static MemorySegment findOrThrow(String name) {
        return NativeLoader.findSymbol(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
    }

    public static boolean isLoaded() {
        return NativeLoader.isReady();
    }

    public static void shutdown() {
        if (NativeLoader.isReady()) {
            if (shutdownDBMH != null) {
                try { shutdownDBMH.invokeExact(); } catch (Throwable t) {}
            }
            NativeLoader.unload();
        }
    }

    // =================================================================
    // API Wrappers
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
            
            double tax = (double) VH_RES_TAX.get(resultSeg);
            boolean isBlocked = ((int) VH_RES_BLOCKED.get(resultSeg)) != 0;
            int warningCode = (int) VH_RES_CODE.get(resultSeg);
            
            return new TransferResult(tax, isBlocked, warningCode);
        } catch (Throwable t) {
            LogUtil.error("checkTransfer FFI 调用异常", t);
            return new TransferResult(0.0, true, -2);
        }
    }

    public static double computePidAdjustment(MemorySegment pidPtr, double target, double current, double dt, double inflation, double heat) {
        if (!isLoaded()) return 0.0;
        try { 
            return (double) computePidMH.invokeExact(pidPtr, target, current, dt, inflation, heat); 
        } catch (Throwable t) { 
            return 0.0; 
        }
    }

    /**
     * 【新增】SIMD 批量价格计算接口
     * 
     * @param count     商品数量
     * @param neff      当前有效玩家数（全局）
     * @param ctxArr    TradeContext 结构体数组（C 风格连续内存）
     * @param cfgArr    MarketConfig 结构体数组
     * @param histAvgs  每个商品的历史均价数组（double[]）
     * @param lambdas   每个商品的 lambda 调节系数数组（double[]）
     * @param results   输出价格数组（double[]），长度必须 >= count
     */
    public static void computeBatchPrices(
        long count,
        double neff,
        MemorySegment ctxArr,
        MemorySegment cfgArr,
        MemorySegment histAvgs,
        MemorySegment lambdas,
        MemorySegment results
    ) {
        if (!isLoaded()) return;
        try {
            computeBatchPricesMH.invokeExact(count, neff, ctxArr, cfgArr, histAvgs, lambdas, results);
        } catch (Throwable t) {
            LogUtil.error("SIMD 批量计算失败", t);
        }
    }

    public static class Layouts {
        public static final GroupLayout TRADE_CONTEXT = TradeContext.layout();
        public static final GroupLayout TRANSFER_CONTEXT = TransferContext.layout();
        public static final GroupLayout MARKET_CONFIG = MarketConfig.layout();
        public static final GroupLayout REGULATOR_CONFIG = RegulatorConfig.layout();
        public static final GroupLayout PID_STATE = PidState.layout();
        public static final GroupLayout TRANSFER_RESULT = top.ellan.ecobridge.gen.TransferResult.layout();
    }

    public record TransferResult(double tax, boolean isBlocked, int warningCode) {}
}