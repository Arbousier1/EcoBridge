// ==================================================
// FILE: ecobridge-java/src/main/java/top/ellan/ecobridge/bridge/NativeContextBuilder.java
// ==================================================

package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.util.HolidayManager;

import java.lang.foreign.MemorySegment;
import java.time.OffsetDateTime;

/**
 * Native 内存上下文构建器
 * <p>
 * 职责: 封装繁琐的 VarHandle 操作，为 PricingManager 提供清晰的 API。
 * 必须与 NativeBridge.Layouts.TRADE_CONTEXT 内存布局保持一致。
 */
public class NativeContextBuilder {

    /**
     * 填充标准的交易上下文 (Global Context)
     * 包含: 时间戳, 通胀率, 时区, 节假日掩码, 默认积分等
     * * @param ctxSeg 必须是 NativeBridge.Layouts.TRADE_CONTEXT 大小的段
     * @param now 当前时间戳
     */
    public static void fillGlobalContext(MemorySegment ctxSeg, long now) {
        // 1. 基础时间数据
        NativeBridge.VH_CTX_TIMESTAMP.set(ctxSeg, 0L, now);
        
        // 2. 宏观经济数据 (自动从 Manager 获取)
        double inflation = EconomyManager.getInstance().getInflationRate();
        NativeBridge.VH_CTX_INF_RATE.set(ctxSeg, 0L, inflation);
        
        // 3. 时区计算 (用于 Rust 端的季节性/周末判断)
        long offset = OffsetDateTime.now().getOffset().getTotalSeconds();
        NativeBridge.VH_CTX_TIMEZONE_OFFSET.set(ctxSeg, 0L, offset);
        
        // 4. 特殊日期掩码 (Bit 1: Holiday)
        int mask = (HolidayManager.isTodayHoliday() ? 1 : 0) << 1;
        NativeBridge.VH_CTX_NEWBIE_MASK.set(ctxSeg, 0L, mask);
        
        // 5. 初始化默认值 (全局定价不针对特定玩家，设为 0)
        NativeBridge.VH_CTX_PLAY_TIME.set(ctxSeg, 0L, 0L);
        NativeBridge.VH_CTX_CURR_AMT.set(ctxSeg, 0L, 0.0);
    }
    
    /**
     * 更新特定商品的上下文 (只需更新基准价)
     * * @param ctxSeg 上下文内存段
     * @param basePrice 商品基准价 (p0)
     */
    public static void updateItemContext(MemorySegment ctxSeg, double basePrice) {
        NativeBridge.VH_CTX_BASE_PRICE.set(ctxSeg, 0L, basePrice);
    }
}