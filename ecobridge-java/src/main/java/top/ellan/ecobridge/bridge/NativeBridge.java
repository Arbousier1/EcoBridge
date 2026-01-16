package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;
import top.ellan.ecobridge.gen.*; // 导入生成的类

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

public class NativeBridge {

    private static final String LIB_NAME = "ecobridge_rust";
    private static final int EXPECTED_ABI_VERSION = 0x0008_0700;

    private static volatile boolean loaded = false;
    private static Arena libraryArena;

    // --- Native 函数句柄 ---
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
    private static MethodHandle shutdownDBMH;

    // --- 内存字段访问句柄 (手动提取) ---
    public static final VarHandle VH_CTX_BASE_PRICE;
    public static final VarHandle VH_CTX_CURR_AMT;
    public static final VarHandle VH_CTX_INF_RATE;
    public static final VarHandle VH_CTX_TIMESTAMP;
    public static final VarHandle VH_CTX_PLAY_TIME;
    public static final VarHandle VH_CTX_TIMEZONE_OFFSET;
    public static final VarHandle VH_CTX_NEWBIE_MASK;

    public static final VarHandle VH_CFG_LAMBDA;
    public static final VarHandle VH_CFG_VOLATILITY;
    public static final VarHandle VH_CFG_S_AMP;
    public static final VarHandle VH_CFG_W_MULT;
    public static final VarHandle VH_CFG_N_PROT;
    public static final VarHandle VH_CFG_W_SEASONAL;
    public static final VarHandle VH_CFG_W_WEEKEND;
    public static final VarHandle VH_CFG_W_NEWBIE;
    public static final VarHandle VH_CFG_W_INFLATION;

    // TransferResult 字段
    private static final VarHandle VH_RES_TAX;
    private static final VarHandle VH_RES_BLOCKED;
    private static final VarHandle VH_RES_CODE;

    static {
        try {
            // 1. TradeContext 初始化
            GroupLayout ctxLayout = TradeContext.layout();
            VH_CTX_BASE_PRICE = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("base_price"));
            VH_CTX_CURR_AMT = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("current_amount"));
            VH_CTX_INF_RATE = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("inflation_rate"));
            VH_CTX_TIMESTAMP = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("current_timestamp"));
            VH_CTX_PLAY_TIME = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("play_time_seconds"));
            VH_CTX_TIMEZONE_OFFSET = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("timezone_offset"));
            VH_CTX_NEWBIE_MASK = ctxLayout.varHandle(MemoryLayout.PathElement.groupElement("newbie_mask"));

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

            // 3. TransferResult 初始化
            GroupLayout resLayout = top.ellan.ecobridge.gen.TransferResult.layout();
            VH_RES_TAX = resLayout.varHandle(MemoryLayout.PathElement.groupElement("final_tax"));
            VH_RES_BLOCKED = resLayout.varHandle(MemoryLayout.PathElement.groupElement("is_blocked"));
            VH_RES_CODE = resLayout.varHandle(MemoryLayout.PathElement.groupElement("warning_code"));

        } catch (Exception e) {
            throw new RuntimeException("Init Layout failed", e);
        }
    }

    public static void init(EcoBridge plugin) {
        if (loaded) return;
        try {
            Path libPath = extractLibrary(plugin);
            libraryArena = Arena.ofAuto();
            SymbolLookup libLookup = SymbolLookup.libraryLookup(libPath, libraryArena);
            Linker linker = Linker.nativeLinker();

            getAbiVersionMH = linker.downcallHandle(libLookup.find("ecobridge_abi_version").orElseThrow(), FunctionDescriptor.of(JAVA_INT));

            int nativeVersion = (int) getAbiVersionMH.invokeExact();
            if (nativeVersion != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException(String.format("ABI Mismatch: 0x%08X != 0x%08X", EXPECTED_ABI_VERSION, nativeVersion));
            }

            // Bindings
            initDBMH = linker.downcallHandle(libLookup.find("ecobridge_init_db").get(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            getVersionMH = linker.downcallHandle(libLookup.find("ecobridge_version").get(), FunctionDescriptor.of(ADDRESS));
            getHealthStatsMH = linker.downcallHandle(libLookup.find("ecobridge_get_health_stats").get(), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            shutdownDBMH = linker.downcallHandle(libLookup.find("ecobridge_shutdown_db").get(), FunctionDescriptor.of(JAVA_INT));
            pushToDuckDBMH = linker.downcallHandle(libLookup.find("ecobridge_log_to_duckdb").get(), FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
            queryNeffVectorizedMH = linker.downcallHandle(libLookup.find("ecobridge_query_neff_vectorized").get(), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE));
            computePriceMH = linker.downcallHandle(libLookup.find("ecobridge_compute_price_humane").get(), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            calculateEpsilonMH = linker.downcallHandle(libLookup.find("ecobridge_calculate_epsilon").get(), FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS));
            checkTransferMH = linker.downcallHandle(libLookup.find("ecobridge_compute_transfer_check").get(), FunctionDescriptor.of(Layouts.TRANSFER_RESULT, ADDRESS, ADDRESS));
            computePidMH = linker.downcallHandle(libLookup.find("ecobridge_compute_pid_adjustment").get(), FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            resetPidMH = linker.downcallHandle(libLookup.find("ecobridge_reset_pid_state").get(), FunctionDescriptor.ofVoid(ADDRESS));

            try (Arena arena = Arena.ofConfined()) {
                String dataPath = plugin.getDataFolder().getAbsolutePath();
                int result = (int) initDBMH.invokeExact(arena.allocateFrom(dataPath));
                if (result != 0 && result != -3) throw new IllegalStateException("Rust init failed: " + result);
            }

            loaded = true;
            MemorySegment v = (MemorySegment) getVersionMH.invokeExact();
            LogUtil.info("<green>Native engine loaded: " + v.getString(0));

        } catch (Throwable e) {
            LogUtil.error("Native fatal error", e);
            shutdown();
        }
    }

    public static boolean isLoaded() { return loaded; }

    // API Wrappers
    public static void getHealthStats(MemorySegment outTotal, MemorySegment outDropped) {
        if (!loaded) return;
        try { getHealthStatsMH.invokeExact(outTotal, outDropped); } catch (Throwable t) {}
    }

    public static void resetPidState(MemorySegment pidPtr) {
        if (!loaded) return;
        try { resetPidMH.invokeExact(pidPtr); } catch (Throwable t) { LogUtil.error("PID reset failed", t); }
    }

    public static double computePrice(double base, double nEff, double amount, double lambda, double epsilon) {
        if (!loaded) return base;
        try { return (double) computePriceMH.invokeExact(base, nEff, amount, lambda, epsilon); } catch (Throwable t) { return base; }
    }

    public static double queryNeffVectorized(long now, double tau) {
        if (!loaded) return 0.0;
        try { return (double) queryNeffVectorizedMH.invokeExact(now, tau); } catch (Throwable t) { return 0.0; }
    }

    public static void pushToDuckDB(long ts, String uuid, double amount, double bal, String meta) {
        if (!loaded) return;
        try (Arena arena = Arena.ofConfined()) {
            pushToDuckDBMH.invokeExact(ts, arena.allocateFrom(uuid), amount, bal, arena.allocateFrom(meta));
        } catch (Throwable t) { LogUtil.error("DuckDB log failed", t); }
    }

    public static double calculateEpsilon(MemorySegment tradeCtx, MemorySegment marketCfg) {
        if (!loaded) return 1.0;
        try { return (double) calculateEpsilonMH.invokeExact(tradeCtx, marketCfg); } catch (Throwable t) { return 1.0; }
    }

    public static TransferResult checkTransfer(MemorySegment ctxSeg, MemorySegment cfgSeg) {
        if (!loaded) return new TransferResult(0.0, true, -1);
        try (Arena localArena = Arena.ofConfined()) {
            MemorySegment res = (MemorySegment) checkTransferMH.invokeExact(localArena, ctxSeg, cfgSeg);
            double tax = (double) VH_RES_TAX.get(res);
            boolean isBlocked = ((int) VH_RES_BLOCKED.get(res)) != 0;
            int warningCode = (int) VH_RES_CODE.get(res);
            return new TransferResult(tax, isBlocked, warningCode);
        } catch (Throwable t) { return new TransferResult(0.0, true, -2); }
    }

    public static double computePidAdjustment(MemorySegment pidPtr, double target, double current, double dt, double inflation) {
        if (!loaded) return 0.0;
        try { return (double) computePidMH.invokeExact(pidPtr, target, current, dt, inflation); } catch (Throwable t) { return 0.0; }
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

    public static void shutdown() {
        if (loaded && shutdownDBMH != null) {
            try { shutdownDBMH.invokeExact(); } catch (Throwable t) {}
        }
        loaded = false;
    }

    // 修复了异常处理
    private static Path extractLibrary(EcoBridge plugin) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String suffix = os.contains("win") ? ".dll" : (os.contains("mac") ? ".dylib" : ".so");
        String name = (os.contains("win") ? "" : "lib") + LIB_NAME + suffix;
        Path target = plugin.getDataFolder().toPath().resolve("natives").resolve(name);

        try (InputStream in = plugin.getResource(name)) {
            if (in == null) throw new IOException("Native lib not found: " + name);
            if (Files.exists(target)) {
                String h1 = calculateHash(in.readAllBytes());
                String h2 = calculateHash(Files.readAllBytes(target));
                if (h1.equals(h2)) return target;
            }
            Files.createDirectories(target.getParent());
            try (InputStream inFresh = plugin.getResource(name)) {
                if (inFresh == null) throw new IOException("Stream null");
                Files.copy(inFresh, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (Files.exists(target)) return target;
            throw e;
        } catch (Exception e) {
            if (Files.exists(target)) return target;
            throw new IOException("Extraction failed", e);
        }
        return target;
    }

    // 修复了不抛出受检异常
    private static String calculateHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}