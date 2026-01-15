package top.ellan.ecobridge.collector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/**
 * 活跃度数据采集器 (ActivityCollector v0.6.1)
 * 职责：快速提取玩家画像，为 Native 核心的 TradeContext 提供秒级量纲输入。
 * 优化：严格对齐 Rust 侧的 i64 播放时长与位掩码逻辑。
 */
public class ActivityCollector {

    private static final long TICKS_PER_SECOND = 20L;
    private static final double SECONDS_PER_HOUR = 3600.0;

    /**
     * [主线程] 采集玩家活跃度快照
     * * @param newbieThresholdHours 来自配置的新手判定阈值（小时）
     * @return 包含秒级时长和位掩码标志的快照
     */
    public static ActivitySnapshot capture(Player player, double newbieThresholdHours) {
        // 1. 获取 Tick 级总时长并安全转换为秒 (i64/long)
        // PLAY_ONE_MINUTE 实际上返回的是游戏刻 (Ticks)
        long totalTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long totalSeconds = totalTicks / TICKS_PER_SECOND;
        
        // 2. 转换为小时（用于逻辑判定与 GUI 显示）
        double hours = (double) totalSeconds / SECONDS_PER_HOUR;
        
        // 3. 判定判定新手位标志 (Bit 0)
        // 逻辑：符合新手条件则设 Bit 0 为 1，否则为 0
        int newbieBit = (hours < newbieThresholdHours) ? 1 : 0;
        
        return new ActivitySnapshot(totalSeconds, hours, newbieBit);
    }

    /**
     * 活跃度快照 Record
     * @param seconds 对应 Rust 侧 play_time_seconds (i64)
     * @param hours   双精度小时数
     * @param isNewbie 对应 Rust 侧 is_newbie 的 Bit 0
     */
    public record ActivitySnapshot(long seconds, double hours, int isNewbie) {}

    /**
     * 视觉输出：为玩家提供友好的活跃度展示
     */
    public static Component toComponent(Player player) {
        // 假设默认新手线为 48 小时
        var snapshot = capture(player, 48.0);
        
        // 颜色分级逻辑：<10h 红色，10h~50h 黄色，>50h 绿色
        String color = snapshot.hours() < 10 ? "<red>" : (snapshot.hours() < 50 ? "<yellow>" : "<green>");
        
        // 采用快速数学截断保留一位小数
        double displayHours = Math.floor(snapshot.hours() * 10) / 10.0;

        return MiniMessage.miniMessage().deserialize(
            "<gray>活跃等级: " + color + "<hours>h <dark_gray>(<sec>s) <gray>新手状态: <newbie>",
            Placeholder.unparsed("hours", String.valueOf(displayHours)),
            Placeholder.unparsed("sec", String.valueOf(snapshot.seconds())),
            // 界面显示仅判断 Bit 0
            Placeholder.unparsed("newbie", (snapshot.isNewbie() & 1) == 1 ? "<yellow>是" : "<green>否")
        );
    }
}