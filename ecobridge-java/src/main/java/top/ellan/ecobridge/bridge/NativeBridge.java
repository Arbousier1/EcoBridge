package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.lang.foreign.ValueLayout.*;

/**
 * NativeBridge v0.8.7 - FFM 桥接器 (行为经济学增强版)
 * 职责：作为 Java 25 与 Rust 物理引擎之间的二进制信道。
 * <p>
 * 架构变更 (v0.8.7):
 * 1. **Strict ABI**: 启动时强制执行 Rust ABI 版本全字校验。
 * 2. **人性化演算**: 引入 ecobridge_compute_price_humane，支持 amount 感知。
 * 3. **内存安全**: 严格的 Arena 生命周期检查 (Scope Liveness Check)。
 */
public class NativeBridge {

  private static final String LIB_NAME = "ecobridge_rust";
  // 期望的 ABI 版本 (Hex: 0.8.7.0)
  private static final int EXPECTED_ABI_VERSION = 0x0008_0700; 
  
  private static boolean loaded = false;
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

  // --- 内存字段访问句柄 (VarHandles) ---
  public static final VarHandle VH_CTX_BASE_PRICE;
  public static final VarHandle VH_CTX_CURR_AMT;
  public static final VarHandle VH_CTX_INF_RATE;
  public static final VarHandle VH_CTX_TIMESTAMP;
  public static final VarHandle VH_CTX_PLAY_TIME;
  public static final VarHandle VH_CTX_TIMEZONE_OFFSET; 
  public static final VarHandle VH_CTX_NEWBIE_MASK;

  static {
    // 预绑定 VarHandles，最大化提升热点路径性能
    VH_CTX_BASE_PRICE = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("base_price"));
    VH_CTX_CURR_AMT = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("current_amount"));
    VH_CTX_INF_RATE = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("inflation_rate"));
    VH_CTX_TIMESTAMP = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("current_timestamp"));
    VH_CTX_PLAY_TIME = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("play_time_seconds"));
    VH_CTX_TIMEZONE_OFFSET = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("timezone_offset"));
    VH_CTX_NEWBIE_MASK = Layouts.TRADE_CONTEXT.varHandle(MemoryLayout.PathElement.groupElement("newbie_mask"));
  }

  public static void init(EcoBridge plugin) {
    if (loaded) return;
    try {
      Path libPath = extractLibrary(plugin);
      libraryArena = Arena.ofShared();
      SymbolLookup libLookup = SymbolLookup.libraryLookup(libPath, libraryArena);
      Linker linker = Linker.nativeLinker();

      // ---------------------------------------------------------
      // 0. ABI 版本握手 (全字匹配校验，确保 Struct 布局绝对对齐)
      // ---------------------------------------------------------
      getAbiVersionMH = linker.downcallHandle(
        libLookup.find("ecobridge_abi_version").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT)
      );

      // 执行握手检查
      int nativeVersion = (int) getAbiVersionMH.invokeExact();
      if (nativeVersion != EXPECTED_ABI_VERSION) {
        throw new IllegalStateException(String.format(
          "Native ABI 版本不匹配！Java驱动: 0x%08X, Native库: 0x%08X。请更新二进制文件。",
          EXPECTED_ABI_VERSION, nativeVersion
        ));
      }

      // ---------------------------------------------------------
      // 1. 系统管理函数绑定
      // ---------------------------------------------------------
      initDBMH = linker.downcallHandle(libLookup.find("ecobridge_init_db").get(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS));
      
      getVersionMH = linker.downcallHandle(libLookup.find("ecobridge_version").get(), 
        FunctionDescriptor.of(ADDRESS));

      getHealthStatsMH = linker.downcallHandle(libLookup.find("ecobridge_get_health_stats").get(),
        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

      // ---------------------------------------------------------
      // 2. 存储与查询
      // ---------------------------------------------------------
      pushToDuckDBMH = linker.downcallHandle(libLookup.find("ecobridge_log_to_duckdb").get(),
        FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));

      queryNeffVectorizedMH = linker.downcallHandle(libLookup.find("ecobridge_query_neff_vectorized").get(),
        FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE));

      // ---------------------------------------------------------
      // 3. 核心演算
      // ---------------------------------------------------------
      computePriceMH = linker.downcallHandle(libLookup.find("ecobridge_compute_price_humane").get(),
        FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));

      calculateEpsilonMH = linker.downcallHandle(libLookup.find("ecobridge_calculate_epsilon").get(),
        FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS));

      // ---------------------------------------------------------
      // 4. 风控与 PID
      // ---------------------------------------------------------
      checkTransferMH = linker.downcallHandle(libLookup.find("ecobridge_compute_transfer_check").get(),
        FunctionDescriptor.of(Layouts.TRANSFER_RESULT, ADDRESS, ADDRESS));

      computePidMH = linker.downcallHandle(libLookup.find("ecobridge_compute_pid_adjustment").get(),
        FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));

      resetPidMH = linker.downcallHandle(libLookup.find("ecobridge_reset_pid_state").get(),
        FunctionDescriptor.ofVoid(ADDRESS));

      // ---------------------------------------------------------
      // 5. 初始化 Rust 侧数据库连接
      // ---------------------------------------------------------
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
      LogUtil.info("<green>Native 引擎 v0.8.7 载入成功! 内核: " + v.getString(0));

    } catch (Throwable e) {
      LogUtil.error("Native 链路绑定或初始化致命错误！", e);
      shutdown();
    }
  }

  public static boolean isLoaded() { return loaded; }

  // ==================== 监控与状态 API ====================

  public static void getHealthStats(MemorySegment outTotal, MemorySegment outDropped) {
    if (!loaded) return;
    
    // [Safety Check] 防止向已关闭的 Arena 写入数据
    if (!outTotal.scope().isAlive() || !outDropped.scope().isAlive()) {
      return;
    }

    try {
      getHealthStatsMH.invokeExact(outTotal, outDropped);
    } catch (Throwable t) {
      // 监控接口静默失败
    }
  }

  /**
   * 重置指定 PID 控制器的内部状态
   */
  public static void resetPidState(MemorySegment pidPtr) {
    if (!loaded) return;
    if (!pidPtr.scope().isAlive()) return;

    try {
      resetPidMH.invokeExact(pidPtr);
    } catch (Throwable t) {
      LogUtil.error("PID 状态重置失败", t);
    }
  }

  // ==================== 核心逻辑包装 API ====================

  /**
   * 演算动态价格
   * @param amount 此次交易带来的供应变化量
   */
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

  /**
   * 计算环境修正因子 (Epsilon)
   */
  public static double calculateEpsilon(MemorySegment tradeCtx, MemorySegment marketCfg) {
    if (!loaded) return 1.0;
    
    // 1. 生存期预检查 (Liveness Check)
    if (!tradeCtx.scope().isAlive() || !marketCfg.scope().isAlive()) {
      LogUtil.warn("FFI 安全拦截：calculateEpsilon 调用时内存段已失效 (Arena Closed)");
      return 1.0;
    }

    try { 
      // 2. 执行调用
      return (double) calculateEpsilonMH.invokeExact(tradeCtx, marketCfg); 
    } catch (Throwable t) { 
      LogUtil.error("Native 演算调用异常", t);
      return 1.0; 
    }
  }

  /**
   * 执行转账审计
   */
  public static TransferResult checkTransfer(MemorySegment ctxSeg, MemorySegment cfgSeg) {
    if (!loaded) return new TransferResult(0.0, true, -1);
    
    // 1. 生存期预检查
    if (!ctxSeg.scope().isAlive() || !cfgSeg.scope().isAlive()) {
      LogUtil.warn("FFI 安全拦截：checkTransfer 审计上下文内存失效");
      return new TransferResult(0.0, true, -3); 
    }

    try (Arena localArena = Arena.ofConfined()) {
      MemorySegment res = (MemorySegment) checkTransferMH.invokeExact(localArena, ctxSeg, cfgSeg);
      return new TransferResult(res.get(JAVA_DOUBLE, 0), res.get(JAVA_INT, 8) == 1, res.get(JAVA_INT, 12));
    } catch (Throwable t) { 
      return new TransferResult(0.0, true, -2); 
    }
  }

  /**
   * PID 状态更新
   */
  public static double computePidAdjustment(MemorySegment pidPtr, double target, double current, double dt, double inflation) {
    if (!loaded) return 0.0;
    
    // 1. 生存期预检查
    if (!pidPtr.scope().isAlive()) {
      LogUtil.error("FFI 安全拦截：PID 状态机内存失效", null);
      return 0.0;
    }

    try { return (double) computePidMH.invokeExact(pidPtr, target, current, dt, inflation); }
    catch (Throwable t) { return 0.0; }
  }

  // ==================== 内存布局定义 (SSoT) ====================

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
      JAVA_DOUBLE.withName("base_lambda"), 
      JAVA_DOUBLE.withName("volatility_factor"), 
      JAVA_DOUBLE.withName("seasonal_amplitude"), 
      JAVA_DOUBLE.withName("weekend_multiplier"), 
      JAVA_DOUBLE.withName("newbie_protection_rate"), 
      JAVA_DOUBLE.withName("seasonal_weight"), 
      JAVA_DOUBLE.withName("weekend_weight"), 
      JAVA_DOUBLE.withName("newbie_weight"), 
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
      JAVA_DOUBLE.withName("final_tax"), 
      JAVA_INT.withName("is_blocked"), 
      JAVA_INT.withName("warning_code")
    ).withByteAlignment(8);
  }

  public record TransferResult(double tax, boolean isBlocked, int warningCode) {}

  public static void shutdown() { 
    if (libraryArena != null && libraryArena.scope().isAlive()) libraryArena.close(); 
    loaded = false; 
  }

  private static Path extractLibrary(EcoBridge plugin) throws IOException {
    String os = System.getProperty("os.name").toLowerCase();
    String suffix = os.contains("win") ? ".dll" : (os.contains("mac") ? ".dylib" : ".so");
    String name = (os.contains("win") ? "" : "lib") + LIB_NAME + suffix;
    Path target = plugin.getDataFolder().toPath().resolve("natives").resolve(name);
    Files.createDirectories(target.getParent());
    try (var in = plugin.getResource(name)) { 
      if (in == null) throw new IOException("Resource not found: " + name);
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); 
    }
    return target;
  }
}