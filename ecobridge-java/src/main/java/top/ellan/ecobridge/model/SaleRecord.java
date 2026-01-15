package top.ellan.ecobridge.model;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * 销售记录模型 (v0.6.2)
 * 职责：严格对齐 Rust 端的 HistoryRecord 结构 (i64, f64)
 * 修复：显式补全了 getTimestamp() 和 getAmount() 以兼容 PricingManager 的调用
 */
public record SaleRecord(
long timestamp, // Offset 0 (i64)
double amount   // Offset 8 (f64)
) {

    // =================================================================================
    // 1. FFM 内存布局与性能句柄 (SSoT)
    // =================================================================================

    /** * 内存布局:
     * [0-7]   long   timestamp (对应 Rust i64)
     * [8-15]  double amount    (对应 Rust f64)
     */
    public static final GroupLayout LAYOUT = MemoryLayout.structLayout(
    JAVA_LONG.withName("timestamp"),
    JAVA_DOUBLE.withName("amount")
).withByteAlignment(8);

    /** 结构体总长度：16 字节 */
    public static final long LAYOUT_SIZE = LAYOUT.byteSize();

    private static final VarHandle VH_TIMESTAMP = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("timestamp"));
    private static final VarHandle VH_AMOUNT = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("amount"));

    // =================================================================================
    // 2. [关键修复] 显式 Getter (兼容传统调用)
    // =================================================================================

    /** 获取交易时间戳 (兼容 PricingManager) */
    public long getTimestamp() {
        return timestamp;
    }

    /** 获取交易数量 (兼容 PricingManager) */
    public double getAmount() {
        return amount;
    }

    // =================================================================================
    // 3. 核心内存操作 (FFI 适配)
    // =================================================================================

    /**
     * 将本记录写入堆外内存
     * @param segment 目标 MemorySegment
     * @param baseOffset 起始偏移量
     */
    public void writeToMemory(MemorySegment segment, long baseOffset) {
        VH_TIMESTAMP.set(segment, baseOffset, this.timestamp);
        VH_AMOUNT.set(segment, baseOffset, this.amount);
    }

    /**
     * 从堆外内存恢复 SaleRecord 对象
     */
    public static SaleRecord fromMemory(MemorySegment segment, long baseOffset) {
        return new SaleRecord(
        (long) VH_TIMESTAMP.get(segment, baseOffset),
        (double) VH_AMOUNT.get(segment, baseOffset)
    );
    }

    // =================================================================================
    // 4. 业务逻辑与展示
    // =================================================================================

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault());

    /** 紧凑型构造函数：执行数据质量检查 */
    public SaleRecord {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || Math.abs(amount) > 10_000_000) {
            throw new IllegalArgumentException("交易数据溢出风险: " + amount);
        }
    }

    public Component toComponent() {
        String color = amount >= 0 ? "<aqua>" : "<gold>";
        String prefix = amount >= 0 ? "+" : "";
        String timeStr = DATE_FORMAT.format(Instant.ofEpochMilli(timestamp));
        String amtStr = String.format("%.1f", amount);

        return MiniMessage.miniMessage().deserialize(
        "<gray>[<time>] " + color + "<prefix><amt>",
        Placeholder.unparsed("time", timeStr),
        Placeholder.unparsed("prefix", prefix),
        Placeholder.unparsed("amt", amtStr)
    );
    }
}
