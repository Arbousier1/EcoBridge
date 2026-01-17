package top.ellan.ecobridge.util;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工业级日志工具类 (LogUtil v0.9.0 - Hardened)
 * <p>
 * 更新日志:
 * 1. 修复编译错误：添加 error(String) 重载方法。
 * 2. 优化：统一错误日志的视觉风格。
 */
public final class LogUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final AtomicLong TRANSACTION_COUNTER = new AtomicLong(0);
    
    private static final Map<String, Long> RATE_LIMIT_CACHE = new ConcurrentHashMap<>();
    private static final long LOG_COOLDOWN_MS = 5 * 60 * 1000; // 5分钟冷却

    private static volatile boolean debugEnabled = false;
    private static volatile int sampleRate = 100;

    private LogUtil() {}

    public static void init() {
        var config = EcoBridge.getInstance().getConfig();
        debugEnabled = config.getBoolean("system.debug", false);
        sampleRate = Math.max(1, config.getInt("system.log-sample-rate", 100));
        RATE_LIMIT_CACHE.clear(); 

        if (debugEnabled) {
            info("<gradient:aqua:blue>系统调试模式已激活</gradient> <dark_gray>| <gray>采样率: <white>1/<rate>",
            Placeholder.unparsed("rate", String.valueOf(sampleRate)));
        }
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void info(String message, TagResolver... resolvers) {
        sendConsole("<blue>ℹ</blue> <gray>" + message, resolvers);
    }

    public static void debug(String message) {
        if (debugEnabled) {
            sendConsole("<dark_gray>[DEBUG]</dark_gray> <gray>" + message);
        }
    }

    public static void warn(String message) {
        sendConsole("<yellow>⚠</yellow> <white>" + message);
    }

    public static void warnOnce(String key, String message) {
        if (shouldLog(key)) {
            sendConsole("<yellow>⚠</yellow> <white>" + message + " <dark_gray>(已折叠同类警告)</dark_gray>");
        }
    }

    public static void errorOnce(String key, String message) {
        if (shouldLog(key)) {
            sendConsole("<red>✘</red> <white>" + message + " <dark_gray>(已折叠同类错误)</dark_gray>");
        }
    }

    private static boolean shouldLog(String key) {
        long now = System.currentTimeMillis();
        Long last = RATE_LIMIT_CACHE.get(key);
        if (last == null || (now - last) > LOG_COOLDOWN_MS) {
            RATE_LIMIT_CACHE.put(key, now);
            return true;
        }
        return false;
    }

    public static void severe(String message) {
        sendConsole("<red>✘</red> <bold><red>致命故障: </red></bold><white>" + message);
    }

    public static void logTransactionSampled(String message, TagResolver... resolvers) {
        if (!debugEnabled) return;

        long count = TRANSACTION_COUNTER.incrementAndGet();
        if (count % sampleRate == 0) {
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
     * [修复] 错误日志重载 - 仅描述信息
     * 解决了 ConfigValidator 中的编译冲突
     */
    public static void error(String message) {
        error(message, null);
    }

    /**
     * 错误日志重载 - 描述信息 + 异常堆栈
     */
    public static void error(String message, Throwable e) {
        // 使用虚拟线程执行以防止阻塞主线程（堆栈追踪可能耗时）
        EcoBridge.getInstance().getVirtualExecutor().execute(() -> {
            sendConsole("<red>╔══════════════ EcoBridge 异常报告 ══════════════");
            sendConsole("<red>║ <white>描述: <msg>", Placeholder.unparsed("msg", message));

            if (e != null) {
                sendConsole("<red>║ <white>类型: <yellow><type>", Placeholder.unparsed("type", e.getClass().getSimpleName()));
                sendConsole("<red>║ <white>原因: <gray><reason>", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
                sendConsole("<red>╚════════════════════════════════════════════════");
                
                // 仅在控制台输出详细堆栈，MiniMessage 不适合渲染长堆栈
                EcoBridge.getInstance().getLogger().severe("--- 详细堆栈追踪 ---");
                e.printStackTrace();
            } else {
                sendConsole("<red>╚════════════════════════════════════════════════");
            }
        });
    }

    private static void sendConsole(String msg, TagResolver... resolvers) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }
}