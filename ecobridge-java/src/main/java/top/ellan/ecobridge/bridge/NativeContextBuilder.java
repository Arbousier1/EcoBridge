package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.util.HolidayManager;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.MemorySegment;
import java.time.OffsetDateTime;

/**
 * Native 内存上下文构建器 (v0.9.4 - Clean Edition)
 * <p>
 * 职责: 封装 VarHandle 操作，确保 Java 填充的数据与 Rust 侧 TradeContext 内存布局完全对齐。
 */
public class NativeContextBuilder {

    // 缓存时区偏移量，减少高频交易时的系统调用开销
    private static final int TIMEZONE_OFFSET = OffsetDateTime.now().getOffset().getTotalSeconds();

    /**
     * 填充标准的交易上下文 (Global Context)
     * * @param ctxSeg 必须是 NativeBridge.Layouts.TRADE_CONTEXT 大小的段
     * @param now    当前 Unix 时间戳
     */
    public static void fillGlobalContext(MemorySegment ctxSeg, long now) {
        if (!NativeBridge.isLoaded()) {
            return;
        }

        try {
            // 1. 基础时间数据 (Offset 24)
            NativeBridge.VH_CTX_TIMESTAMP.set(ctxSeg, 0L, now);

            // 2. 宏观经济指标
            EconomyManager eco = EconomyManager.getInstance();
            if (eco != null) {
                // 通胀率 (Offset 16)
                NativeBridge.VH_CTX_INF_RATE.set(ctxSeg, 0L, eco.getInflationRate());
                
                // 市场热度 (Offset 48) 与 生态饱和度 (Offset 56)
                NativeBridge.VH_CTX_MARKET_HEAT.set(ctxSeg, 0L, eco.getMarketHeat());
                NativeBridge.VH_CTX_ECO_SAT.set(ctxSeg, 0L, eco.getEcoSaturation());
            }

            // 3. 时区偏移 (Offset 40)
            NativeBridge.VH_CTX_TIMEZONE_OFFSET.set(ctxSeg, 0L, TIMEZONE_OFFSET);

            // 4. 特殊状态掩码 (Offset 44)
            // Bit 1: 是否节假日
            int mask = (HolidayManager.isTodayHoliday() ? 1 : 0) << 1;
            NativeBridge.VH_CTX_NEWBIE_MASK.set(ctxSeg, 0L, mask);

            // 5. 默认值初始化
            NativeBridge.VH_CTX_PLAY_TIME.set(ctxSeg, 0L, 0L);
            NativeBridge.VH_CTX_CURR_AMT.set(ctxSeg, 0L, 0.0);

        } catch (Exception e) {
            LogUtil.error("填充 GlobalContext 失败: 内存段可能已失效", e);
        }
    }

    /**
     * 更新特定商品的独立属性 (Offset 0)
     * * @param ctxSeg    上下文内存段
     * @param basePrice 商品基准价
     */
    public static void updateItemContext(MemorySegment ctxSeg, double basePrice) {
        if (!NativeBridge.isLoaded()) return;
        
        try {
            NativeBridge.VH_CTX_BASE_PRICE.set(ctxSeg, 0L, basePrice);
        } catch (Exception e) {
            LogUtil.error("更新 ItemContext 失败", e);
        }
    }
}