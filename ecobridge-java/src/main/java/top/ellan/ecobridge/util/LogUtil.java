package top.ellan.ecobridge.util;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 工业级日志工具类 (LogUtil v0.8.8)
 * 职责：
 * 1. 集中式控制台视觉规范管理。
 * 2. 异步 MiniMessage 渲染管线，利用 Java 25 虚拟线程分流 CPU 压力。
 * 3. 高性能交易采样逻辑。
 */
public final class LogUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final AtomicLong TRANSACTION_COUNTER = new AtomicLong(0);

    private static volatile boolean debugEnabled = false;
    private static volatile int sampleRate = 100;

    // 工具类私有化构造函数
    private LogUtil() {}

    /**
     * 初始化/重载配置：确保 volatile 变量在多线程环境中立即可见
     */
    public static void init() {
        var config = EcoBridge.getInstance().getConfig();
        debugEnabled = config.getBoolean("system.debug", false);
        sampleRate = Math.max(1, config.getInt("system.log-sample-rate", 100));

        if (debugEnabled) {
            info("<gradient:aqua:blue>系统调试模式已激活</gradient> <dark_gray>| <gray>采样率: <white>1/<rate>",
            Placeholder.unparsed("rate", String.valueOf(sampleRate)));
        }
    }

    /**
     * 判断调试状态：用于绕过昂贵的参数构造过程
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * [INFO] 基础业务日志
     */
    public static void info(String message, TagResolver... resolvers) {
        sendConsole("<blue>ℹ</blue> <gray>" + message, resolvers);
    }

    /**
     * [DEBUG] 调试细节日志
     */
    public static void debug(String message) {
        if (debugEnabled) {
            sendConsole("<dark_gray>[DEBUG]</dark_gray> <gray>" + message);
        }
    }

    /**
     * [WARN] 业务警告日志
     */
    public static void warn(String message) {
        sendConsole("<yellow>⚠</yellow> <white>" + message);
    }

    /**
     * [SEVERE] 严重系统错误 (无堆栈)
     */
    public static void severe(String message) {
        sendConsole("<red>✘</red> <bold><red>致命故障: </red></bold><white>" + message);
    }

    /**
     * [SAMPLED] 高频交易采样日志
     * 采用虚拟线程异步解析，防止 MiniMessage 反射解析拖慢交易 Tick
     */
    public static void logTransactionSampled(String message, TagResolver... resolvers) {
        if (!debugEnabled) return;

        long count = TRANSACTION_COUNTER.incrementAndGet();
        if (count % sampleRate == 0) {
            // 将渲染任务外包给 Loom 虚拟线程池
            EcoBridge.getInstance().getVirtualExecutor().execute(() -> {
                TagResolver combined = TagResolver.resolver(
                TagResolver.resolver(resolvers),
                Placeholder.unparsed("count", String.valueOf(count))
            );

                sendConsole("<blue>⚖</blue> <gray>" + message + " <dark_gray>(#<count>)", combined);
            });
        }
    }

    /**
     * [ERROR] 结构化灾难日志 (带堆栈)
     */
    public static void error(String message, Throwable e) {
        // 使用虚拟线程处理大型字符串拼接和打印
        EcoBridge.getInstance().getVirtualExecutor().execute(() -> {
            sendConsole("<red>╔══════════════ EcoBridge 异常报告 ══════════════");
            sendConsole("<red>║ <white>描述: <msg>", Placeholder.unparsed("msg", message));

            if (e != null) {
                sendConsole("<red>║ <white>类型: <yellow><type>", Placeholder.unparsed("type", e.getClass().getSimpleName()));
                sendConsole("<red>║ <white>原因: <gray><reason>", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
                sendConsole("<red>╚════════════════════════════════════════════════");

                // 控制台堆栈使用标准日志接口以确保格式正确
                EcoBridge.getInstance().getLogger().severe("--- 详细堆栈追踪 ---");
                e.printStackTrace();
            } else {
                sendConsole("<red>╚════════════════════════════════════════════════");
            }
        });
    }

    /**
     * 底层物理输出：通过 MiniMessage 序列化至控制台
     */
    private static void sendConsole(String msg, TagResolver... resolvers) {
        // 由于控制台 sender 本身是线程安全的，这里直接派发
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }
}
