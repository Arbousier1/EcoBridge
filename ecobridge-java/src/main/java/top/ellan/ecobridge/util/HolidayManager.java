package top.ellan.ecobridge.util;

import com.google.gson.JsonParser;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference; // [新增]

public class HolidayManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter DATE_KEY_FORMAT = DateTimeFormatter.ofPattern("MM-dd");

    // [修复 1] 使用 AtomicReference 替代 volatile，提供更强的原子性保证
    private static final AtomicReference<Set<String>> holidayCache =
    new AtomicReference<>(Collections.emptySet());

    private static double holidayMultiplier = 1.2;
    private static ScheduledExecutorService scheduler;
    private static Path cacheFile;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(10))
    .build();

    public static void init() {
        var config = EcoBridge.getInstance().getConfig();
        holidayMultiplier = config.getDouble("economy.holiday-multiplier", 1.2);
        cacheFile = EcoBridge.getInstance().getDataFolder().toPath().resolve("cache/holidays.json");

        loadFromLocalCache();
        startAutoRefreshTask();
    }

    private static void startAutoRefreshTask() {
        if (scheduler != null && !scheduler.isShutdown()) return;

        scheduler = Executors.newSingleThreadScheduledExecutor(r ->
        Thread.ofVirtual().name("EcoBridge-Holiday-Worker").unstarted(r));

        fetchHolidayData();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay().plusMinutes(5);
        long initialDelaySeconds = Duration.between(now, nextMidnight).getSeconds();

        sendConsole("<gray>[环境] 日历同步已对齐，下次完整对时将在 <white><delay></white> 秒后。",
        Placeholder.unparsed("delay", String.valueOf(initialDelaySeconds)));

        scheduler.scheduleAtFixedRate(HolidayManager::fetchHolidayData, initialDelaySeconds,
        TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    public static double getHolidayEpsilonFactor() {
        return isTodayHoliday() ? holidayMultiplier : 1.0;
    }

    public static boolean isTodayHoliday() {
        return isHoliday(System.currentTimeMillis());
    }

    public static boolean isHoliday(long timestamp) {
        // [修复 2] 获取引用的局部快照，保证后续操作的一致性
        Set<String> currentCache = holidayCache.get();
        if (currentCache.isEmpty()) return false;

        String dateKey = LocalDate.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
        .format(DATE_KEY_FORMAT);
        return currentCache.contains(dateKey);
    }

    private static void fetchHolidayData() {
        int year = LocalDate.now().getYear();
        String url = EcoBridge.getInstance().getConfig().getString(
        "holiday-api.url-template",
        "https://timor.tech/api/holiday/year/{year}"
    ).replace("{year}", String.valueOf(year));

        HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", "EcoBridge-Core/0.6.7")
        .GET()
        .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenAccept(res -> {
            if (res.statusCode() == 200) {
                parseAndCache(res.body());
                saveToLocalCache(res.body());
            }
        })
        .exceptionally(ex -> {
            sendConsole("<red>[环境] 节假日网络同步异常，已回退至本地快照。");
            return null;
        });
    }

    private static void parseAndCache(String json) {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("holiday")) return;

            var holidays = root.getAsJsonObject("holiday");
            Set<String> newSet = new HashSet<>();

            holidays.keySet().forEach(date -> {
                var info = holidays.getAsJsonObject(date);
                if (info.has("holiday") && info.get("holiday").getAsBoolean()) {
                    // 提取 MM-dd 格式 (例如 "2023-10-01" -> "10-01")
                    if (date.length() >= 10) newSet.add(date.substring(5, 10));
                }
            });

            // [修复 3] 原子更新缓存引用
            holidayCache.set(Collections.unmodifiableSet(newSet));
            sendConsole("<green>[环境] 节假日数据库已对齐最新自然日。");

        } catch (Exception e) {
            sendConsole("<red>[环境] 数据格式异常: <white><error>", Placeholder.unparsed("error", e.getMessage()));
        }
    }

    private static void saveToLocalCache(String json) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, json);
        } catch (IOException ignored) {}
    }

    private static void loadFromLocalCache() {
        if (!Files.exists(cacheFile)) return;
        try {
            parseAndCache(Files.readString(cacheFile));
        } catch (IOException ignored) {}
    }

    public static void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private static void sendConsole(String msg, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }
}
