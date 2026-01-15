package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

import static java.lang.foreign.ValueLayout.*;

/**
 * NativeBridge v0.8.7 - FFM 桥接器 (行为经济学增强版)
 * 职责：作为 Java 25 与 Rust 物理引擎之间的二进制信道。
 * <p>
 * 架构变更 (v0.8.7):
 * 1. **ABI 握手**: 启动时强制执行 Rust ABI 版本全字校验。
 * 2. **人性化演算**: 引入 ecobridge_compute_price_humane，支持 amount 感知。
 * 3. **内存安全**: 严格的 Arena 生命周期检查 (Scope Liveness Check)。
 * 4. **资源保护**: 增加文件哈希校验，防止 /reload 时文件锁定冲突。
 */
public class NativeBridge {

    private static final String LIB_NAME = "ecobridge_rust";
    // 期望的 ABI 版本 (Hex: 0.8.7.0)
    private static final int EXPECTED_ABI_VERSION = 0x0008_0700;

    private static volatile boolean loaded = false;
    private static Arena libraryArena;

    // --- Native 函数句柄 (MethodHandles) ---
    private static MethodHandle getAbiVersionMH;
    private static MethodHandle initDBMH;
    private static MethodHandle getVersionMH;
    private static MethodHandle getHealthStatsMH;
    private static MethodHandle pushToDuckDBMH;
    private static MethodHandle queryNeffVectorizedMH;
    private static MethodHandle computePriceMH;
    private static MethodHandle calculateEpsilonMH;
    private static MethodHandle checkTransferMH;
    private static MethodHandle computePidMH;
    private static MethodHandle resetPidMH;
    private static MethodHandle shutdownDBMH; // v0.8.7 新增：数据库安全关闭句柄

    // --- 内存字段访问句柄 (VarHandles - Context) ---
    public static final VarHandle VH_CTX_BASE_PRICE;
    public static final VarHandle VH_CTX_CURR_AMT;
    public static final VarHandle VH_CTX_INF_RATE;
    public static final VarHandle VH_CTX_TIMESTAMP;
    public static final VarHandle VH_CTX_PLAY_TIME;
    public static final VarHandle VH_CTX_TIMEZONE_OFFSET;
    public static final VarHandle VH_CTX_NEWBIE_MASK;

    // --- 内存字段访问句柄 (VarHandles - MarketConfig) ---
    public static final VarHandle VH_CFG_LAMBDA;
    public static final VarHandle VH_CFG_VOLATILITY;
    public static final VarHandle VH_CFG_S_AMP;
    public static final VarHandle VH_CFG_W_MULT;
    public static final VarHandle VH_CFG_N_PROT;
    public static final VarHandle VH_CFG_W_SEASONAL;
    public static final VarHandle VH_CFG_W_WEEKEND;
    public static final VarHandle VH_CFG_W_NEWBIE;
    public static final VarHandle VH_CFG_W_INFLATION;

    static {
        // 预绑定 TradeContext VarHandles
        VH_CTX_BASE_PRICE = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("base_price"));
        VH_CTX_CURR_AMT = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("current_amount"));
        VH_CTX_INF_RATE = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("inflation_rate"));
        VH_CTX_TIMESTAMP = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("current_timestamp"));
        VH_CTX_PLAY_TIME = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("play_time_seconds"));
        VH_CTX_TIMEZONE_OFFSET = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("timezone_offset"));
        VH_CTX_NEWBIE_MASK = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("newbie_mask"));

        // 预绑定 MarketConfig VarHandles
        var mkt = Layouts.MARKET_CONFIG;
        VH_CFG_LAMBDA = mkt.varHandle(MemoryLayout.PathElement.groupElement("base_lambda"));
        VH_CFG_VOLATILITY = mkt.varHandle(MemoryLayout.PathElement.groupElement("volatility_factor"));
        VH_CFG_S_AMP = mkt.varHandle(MemoryLayout.PathElement.groupElement("seasonal_amplitude"));
        VH_CFG_W_MULT = mkt.varHandle(MemoryLayout.PathElement.groupElement("weekend_multiplier"));
        VH_CFG_N_PROT = mkt.varHandle(MemoryLayout.PathElement.groupElement("newbie_protection_rate"));
        VH_CFG_W_SEASONAL = mkt.varHandle(MemoryLayout.PathElement.groupElement("seasonal_weight"));
        VH_CFG_W_WEEKEND = mkt.varHandle(MemoryLayout.PathElement.groupElement("weekend_weight"));
        VH_CFG_W_NEWBIE = mkt.varHandle(MemoryLayout.PathElement.groupElement("newbie_weight"));
        VH_CFG_W_INFLATION = mkt.varHandle(MemoryLayout.PathElement.groupElement("inflation_weight"));
    }

    public static void init(EcoBridge plugin) {
        if (loaded) return;
        try {
            Path libPath = extractLibrary(plugin);
            // 使用 ofAuto() 托管 Arena，防止异步任务存取时手动关闭导致的 JVM Crash [cite: 71]
            libraryArena = Arena.ofAuto();
            SymbolLookup libLookup = SymbolLookup.libraryLookup(libPath, libraryArena);
            Linker linker = Linker.nativeLinker();

            // 0. ABI 版本握手
            getAbiVersionMH = linker.downcallHandle(
                libLookup.find("ecobridge_abi_version").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT)
            );

            int nativeVersion = (int) getAbiVersionMH.invokeExact();
            if (nativeVersion != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException(String.format(
                    "Native ABI 版本不匹配！Java期望: 0x%08X, Native返回: 0x%08X。",
                    EXPECTED_ABI_VERSION, nativeVersion
                ));
            }

            // 1. 系统管理函数绑定
            initDBMH = linker.downcallHandle(libLookup.find("ecobridge_init_db").get(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

            getVersionMH = linker.downcallHandle(libLookup.find("ecobridge_version").get(),
                FunctionDescriptor.of(ADDRESS));

            getHealthStatsMH = linker.downcallHandle(libLookup.find("ecobridge_get_health_stats").get(),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

            shutdownDBMH = linker.downcallHandle(libLookup.find("ecobridge_shutdown_db").get(),
                FunctionDescriptor.of(JAVA_INT));

            // 2. 存储与查询 [cite: 74]
            pushToDuckDBMH = linker.downcallHandle(libLookup.find("ecobridge_log_to_duckdb").get(),
                FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));

            queryNeffVectorizedMH = linker.downcallHandle(libLookup.find("ecobridge_query_neff_vectorized").get(),
                FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE));

            // 3. 核心演算 [cite: 75]
            computePriceMH = linker.downcallHandle(libLookup.find("ecobridge_compute_price_humane").get(),
                FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));

            calculateEpsilonMH = linker.downcallHandle(libLookup.find("ecobridge_calculate_epsilon").get(),
                FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS));

            // 4. 风控与 PID [cite: 75-76]
            checkTransferMH = linker.downcallHandle(libLookup.find("ecobridge_compute_transfer_check").get(),
                FunctionDescriptor.of(Layouts.TRANSFER_RESULT, ADDRESS, ADDRESS));

            computePidMH = linker.downcallHandle(libLookup.find("ecobridge_compute_pid_adjustment").get(),
                FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));

            resetPidMH = linker.downcallHandle(libLookup.find("ecobridge_reset_pid_state").get(),
                FunctionDescriptor.ofVoid(ADDRESS));

            // 5. 初始化 Rust 侧数据库连接 [cite: 77]
            try (Arena arena = Arena.ofConfined()) {
                String dataPath = plugin.getDataFolder().getAbsolutePath();
                MemorySegment pathSeg = arena.allocateFrom(dataPath);
                int result = (int) initDBMH.invokeExact(pathSeg);

                if (result != 0 && result != -3) {
                    throw new IllegalStateException("Rust 物理内核初始化失败，错误码: " + result);
                }
            }

            loaded = true;
            MemorySegment v = (MemorySegment) getVersionMH.invokeExact();
            LogUtil.info("<green>Native 引擎载入成功! 内核: " + v.getString(0));

        } catch (Throwable e) {
            LogUtil.error("Native 链路绑定或初始化致命错误！", e);
            shutdown();
        }
    }

    public static boolean isLoaded() { return loaded; }

    // ==================== 核心逻辑包装 API ====================

    public static void getHealthStats(MemorySegment outTotal, MemorySegment outDropped) {
        if (!loaded) return;
        if (!outTotal.scope().isAlive() || !outDropped.scope().isAlive()) return;
        try { getHealthStatsMH.invokeExact(outTotal, outDropped); } catch (Throwable t) {}
    }

    public static void resetPidState(MemorySegment pidPtr) {
        if (!loaded || !pidPtr.scope().isAlive()) return;
        try { resetPidMH.invokeExact(pidPtr); } catch (Throwable t) { LogUtil.error("PID 状态重置失败", t); }
    }

    public static double computePrice(double base, double nEff, double amount, double lambda, double epsilon) {
        if (!loaded) return base;
        try { return (double) computePriceMH.invokeExact(base, nEff, amount, lambda, epsilon); }
        catch (Throwable t) { return base; }
    }

    public static double queryNeffVectorized(long now, double tau) {
        if (!loaded) return 0.0;
        try { return (double) queryNeffVectorizedMH.invokeExact(now, tau); }
        catch (Throwable t) { return 0.0; }
    }

    public static void pushToDuckDB(long ts, String uuid, double amount, double bal, String meta) {
        if (!loaded) return;
        try (Arena arena = Arena.ofConfined()) {
            pushToDuckDBMH.invokeExact(ts, arena.allocateFrom(uuid), amount, bal, arena.allocateFrom(meta));
        } catch (Throwable t) { LogUtil.error("DuckDB 推送失败", t); }
    }

    public static double calculateEpsilon(MemorySegment tradeCtx, MemorySegment marketCfg) {
        if (!loaded || !tradeCtx.scope().isAlive() || !marketCfg.scope().isAlive()) return 1.0;
        try { return (double) calculateEpsilonMH.invokeExact(tradeCtx, marketCfg); }
        catch (Throwable t) { return 1.0; }
    }

    public static TransferResult checkTransfer(MemorySegment ctxSeg, MemorySegment cfgSeg) {
        if (!loaded || !ctxSeg.scope().isAlive() || !cfgSeg.scope().isAlive()) return new TransferResult(0.0, true, -1);
        try (Arena localArena = Arena.ofConfined()) {
            MemorySegment res = (MemorySegment) checkTransferMH.invokeExact(localArena, ctxSeg, cfgSeg);
            return new TransferResult(res.get(JAVA_DOUBLE, 0), res.get(JAVA_INT, 8) == 1, res.get(JAVA_INT, 12));
        } catch (Throwable t) { return new TransferResult(0.0, true, -2); }
    }

    public static double computePidAdjustment(MemorySegment pidPtr, double target, double current, double dt, double inflation) {
        if (!loaded || !pidPtr.scope().isAlive()) return 0.0;
        try { return (double) computePidMH.invokeExact(pidPtr, target, current, dt, inflation); }
        catch (Throwable t) { return 0.0; }
    }

    // ==================== 内存布局定义 (SSoT) [cite: 95-98] ====================

    public static class Layouts {
        public static final GroupLayout TRADE_CONTEXT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("base_price"), JAVA_DOUBLE.withName("current_amount"),
            JAVA_DOUBLE.withName("inflation_rate"), JAVA_LONG.withName("current_timestamp"),
            JAVA_LONG.withName("play_time_seconds"), JAVA_INT.withName("timezone_offset"),
            JAVA_INT.withName("newbie_mask")
        ).withByteAlignment(8);

        public static final GroupLayout TRANSFER_CONTEXT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("amount"), JAVA_DOUBLE.withName("sender_balance"),
            JAVA_DOUBLE.withName("receiver_balance"), JAVA_DOUBLE.withName("inflation_rate"),
            JAVA_DOUBLE.withName("newbie_limit"), JAVA_LONG.withName("sender_play_time"),
            JAVA_LONG.withName("receiver_play_time")
        ).withByteAlignment(8);

        public static final GroupLayout MARKET_CONFIG = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("base_lambda"), JAVA_DOUBLE.withName("volatility_factor"),
            JAVA_DOUBLE.withName("seasonal_amplitude"), JAVA_DOUBLE.withName("weekend_multiplier"),
            JAVA_DOUBLE.withName("newbie_protection_rate"), JAVA_DOUBLE.withName("seasonal_weight"),
            JAVA_DOUBLE.withName("weekend_weight"), JAVA_DOUBLE.withName("newbie_weight"),
            JAVA_DOUBLE.withName("inflation_weight")
        ).withByteAlignment(8);

        public static final GroupLayout REGULATOR_CONFIG = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("base_tax_rate"), JAVA_DOUBLE.withName("luxury_threshold"),
            JAVA_DOUBLE.withName("luxury_tax_rate"), JAVA_DOUBLE.withName("wealth_gap_tax_rate"),
            JAVA_DOUBLE.withName("poor_threshold"), JAVA_DOUBLE.withName("rich_threshold"),
            JAVA_DOUBLE.withName("newbie_receive_limit"), JAVA_DOUBLE.withName("warning_ratio"),
            JAVA_DOUBLE.withName("warning_min_amount"), JAVA_DOUBLE.withName("newbie_hours"),
            JAVA_DOUBLE.withName("veteran_hours")
        ).withByteAlignment(8);

        public static final GroupLayout PID_STATE = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("kp"), JAVA_DOUBLE.withName("ki"), JAVA_DOUBLE.withName("kd"),
            JAVA_DOUBLE.withName("lambda"), JAVA_DOUBLE.withName("integral"),
            JAVA_DOUBLE.withName("prev_pv"), JAVA_DOUBLE.withName("filtered_d"),
            JAVA_DOUBLE.withName("integration_limit"), JAVA_INT.withName("is_saturated"),
            MemoryLayout.paddingLayout(4)
        ).withByteAlignment(8);

        public static final GroupLayout TRANSFER_RESULT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("final_tax"), JAVA_INT.withName("is_blocked"),
            JAVA_INT.withName("warning_code")
        ).withByteAlignment(8);
    }

    public record TransferResult(double tax, boolean isBlocked, int warningCode) {}

    public static void shutdown() {
        if (loaded && shutdownDBMH != null) {
            try {
                int res = (int) shutdownDBMH.invokeExact();
                LogUtil.info("Native 数据库管线关闭信号已发送: " + res);
            } catch (Throwable t) { LogUtil.error("无法关闭 Native 数据库", t); }
        }
        loaded = false;
        LogUtil.info("Native 流量入口已安全切断。");
    }

    private static Path extractLibrary(EcoBridge plugin) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String suffix = os.contains("win") ? ".dll" : (os.contains("mac") ? ".dylib" : ".so");
        String name = (os.contains("win") ? "" : "lib") + LIB_NAME + suffix;
        Path target = plugin.getDataFolder().toPath().resolve("natives").resolve(name);

        try (InputStream in = plugin.getResource(name)) {
            if (in == null) throw new IOException("Resource not found: " + name);
            
            // 哈希校验逻辑：防止文件占用时覆盖失败 
            if (Files.exists(target)) {
                byte[] resourceBytes = in.readAllBytes();
                if (calculateHash(resourceBytes).equals(calculateHash(Files.readAllBytes(target)))) {
                    return target;
                }
            }
            
            Files.createDirectories(target.getParent());
            try (InputStream inFresh = plugin.getResource(name)) {
                Files.copy(inFresh, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            if (Files.exists(target)) return target;
            throw new IOException("无法初始化 Native 库", e);
        }
        return target;
    }

    private static String calculateHash(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}