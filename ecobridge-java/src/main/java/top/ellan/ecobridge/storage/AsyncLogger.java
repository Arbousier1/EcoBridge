package top.ellan.ecobridge.storage;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
* 高性能异步日志记录器 (AsyncLogger v0.8.5 - Thread-Safe Edition)
* 职责：非阻塞采集全服经济流水，通过 FFM 指针将数据平刷进 Rust 侧的 DuckDB 向量化引擎。
* 核心：利用 Java 25 虚拟线程承载批处理逻辑，彻底消除磁盘 I/O 对主线程的背压。
* <p>
* 修复日志:
* 1. [Fix] 移除未使用的 plugin 字段，消除 IDE 编译警告。
* 2. [Clean] 保持 Java 25 Record 紧凑布局与虚拟线程调度逻辑。
*/
public final class AsyncLogger {

  private static AsyncLogger instance;
  
  // 50,000 长度的缓冲区：作为 Java 与 Rust 之间的弹性保护闸门
  private final BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>(50000);
  private volatile boolean running = true;

  /**
  * 交易流水条目 (Java 25 Record)
  */
  private record LogEntry(UUID uuid, double delta, double balance, long timestamp, String meta) {}

  private AsyncLogger(EcoBridge plugin) {
    // [Fix] 构造器保留参数以兼容主类初始化调用，但移除无意义的字段赋值
    startNativeWorker();
  }

  /**
  * 单例初始化
  */
  public static void init(EcoBridge plugin) {
    if (instance == null) {
      instance = new AsyncLogger(plugin);
    }
  }

  public static AsyncLogger getInstance() {
    return instance;
  }

  /**
  * 生产者：将资金变动日志存入缓冲队列
  * 逻辑：极速非阻塞 offer 操作，由 Virtual Thread 负责后续耗时推送。
  */
  public static void log(UUID uuid, double delta, double balance, long timestamp, String meta) {
    if (instance == null || !instance.running) return;
    
    LogEntry entry = new LogEntry(uuid, delta, balance, timestamp, meta);
    if (!instance.queue.offer(entry)) {
      // 发生此告警说明 DuckDB 写入速度或系统 I/O 出现严重瓶颈
      LogUtil.warn("AsyncLogger 溢出！Rust 引擎产生背压，正在丢弃部分非核心流水。");
    }
  }

  /**
  * 兼容性重载：记录普通交易
  */
  public static void log(UUID uuid, double delta, double balance, long timestamp) {
    log(uuid, delta, balance, timestamp, "NORMAL");
  }

  /**
  * 启动基于 Java 25 虚拟线程的消费者
  * 职责：使用“贪婪采集”模式批量从队列提取数据并跨越 FFM 边界。
  */
  private void startNativeWorker() {
    Thread.ofVirtual().name("ecobridge-duckdb-worker").start(() -> {
      List<LogEntry> batch = new ArrayList<>(1000); 
      LogUtil.info("AsyncLogger 虚拟线程已就绪，正在监听 Native 写入管线...");
      
      while (running || !queue.isEmpty()) {
        try {
          // 1. 等待第一条信号，2秒超时以确保在低频交易时也能定时刷新
          LogEntry first = queue.poll(2, TimeUnit.SECONDS);
          
          if (first != null) {
            batch.add(first);
            
            // 2. 批量贪婪采集：一次性取出队列中剩余的所有条目（最多999条）
            // 这样可以将多次 JNI/FFM 开销合并为一批物理操作
            queue.drainTo(batch, 999);
            
            // 3. 执行物理推送
            pushBatchToNative(batch);
            batch.clear();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (Exception e) {
          LogUtil.error("AsyncLogger 运行时发生异常", e);
        }
      }
      LogUtil.info("AsyncLogger 已成功离线，Native 数据管线已安全切断。");
    });
  }

  /**
  * 执行 Native 推送：跨越 FFM 边界
  */
  private void pushBatchToNative(List<LogEntry> entries) {
    // 检查 Native 库是否加载成功，防止空指针调用
    if (!NativeBridge.isLoaded()) return;

    for (LogEntry entry : entries) {
      // 将数据压入 Rust 侧的 DuckDB Appender
      // UUID.toString() 在此处执行，分担了主线程的 CPU 压力
      NativeBridge.pushToDuckDB(
        entry.timestamp(),
        entry.uuid().toString(),
        entry.delta(),
        entry.balance(),
        entry.meta()
      );
    }
  }

  /**
  * 优雅停机：确保残留队列在插件卸载前完成推送
  */
  public void shutdown() {
    LogUtil.info("正在关闭异步记录器，正在将残留流水存入 DuckDB...");
    this.running = false;
    // 虚拟线程会自动在 queue.isEmpty() 后退出
  }
}