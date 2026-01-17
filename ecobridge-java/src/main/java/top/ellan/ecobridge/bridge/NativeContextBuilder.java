package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.manager.EconomyManager;
import top.ellan.ecobridge.util.HolidayManager;

import java.lang.foreign.MemorySegment;
import java.time.OffsetDateTime;

/**
 * Native 内存上下文构建器 (v0.9.1 - Macro Enhanced)
 * <p>
 * 职责: 封装 VarHandle 操作，确保 Java 填充的数据与 Rust 侧 TradeContext 内存布局完全对齐。
 * 更新日志：
 * 1. 适配 v0.9.1 布局，新增市场热度与生态饱和度注入。
 * 2. 优化时区计算性能。
 */
public class NativeContextBuilder {

    /**
     * 填充标准的交易上下文 (Global Context)
     * 包含: 时间戳, 通胀率, 时区, 节假日掩码, 以及[新增]宏观调控因子。
     *
     * @param ctxSeg 必须是 NativeBridge.Layouts.TRADE_CONTEXT 大小的段（或其切片）
     * @param now    当前时间戳
     */
    public static void fillGlobalContext(MemorySegment ctxSeg, long now) {
        // 1. 基础时间数据 (Offset 24)
        NativeBridge.VH_CTX_TIMESTAMP.set(ctxSeg, 0L, now);
        
        // 2. 宏观经济核心指标 (Offset 16)
        EconomyManager eco = EconomyManager.getInstance();
        double inflation = eco.getInflationRate();
        NativeBridge.VH_CTX_INF_RATE.set(ctxSeg, 0L, inflation);
        
        // 3. [New] 宏观调控因子 (Offset 48 & 56)
        // 这些因子决定了定价引擎的自适应价格弹性
        double heat = eco.getMarketHeat(); // 获取全服实时流速 (V)
        double saturation = eco.getEcoSaturation(); // 获取全服资产饱和度
        
        NativeBridge.VH_CTX_MARKET_HEAT.set(ctxSeg, 0L, heat);
        NativeBridge.VH_CTX_ECO_SAT.set(ctxSeg, 0L, saturation);
        
        // 4. 时区偏移计算 (Offset 40)
        // 用于 Rust 端根据当地时间判断季节性与周末波动
        int timezoneOffset = OffsetDateTime.now().getOffset().getTotalSeconds();
        NativeBridge.VH_CTX_TIMEZONE_OFFSET.set(ctxSeg, 0L, timezoneOffset);
        
        // 5. 特殊状态掩码 (Offset 44)
        // Bit 1: 是否节假日
        int mask = (HolidayManager.isTodayHoliday() ? 1 : 0) << 1;
        NativeBridge.VH_CTX_NEWBIE_MASK.set(ctxSeg, 0L, mask);
        
        // 6. 默认值初始化 (Offset 32 & 8)
        // 批量快照演算不针对特定玩家，PLAY_TIME 设为 0；
        // 基准快照不含即时交易增量，CURR_AMT 设为 0.0。
        NativeBridge.VH_CTX_PLAY_TIME.set(ctxSeg, 0L, 0L);
        NativeBridge.VH_CTX_CURR_AMT.set(ctxSeg, 0L, 0.0);
    }
    
    /**
     * 更新特定商品的独立属性 (Offset 0)
     * * @param ctxSeg    上下文内存段 (TradeContext)
     * @param basePrice 商品在配置文件中定义的基准价 (p0)
     */
    public static void updateItemContext(MemorySegment ctxSeg, double basePrice) {
        NativeBridge.VH_CTX_BASE_PRICE.set(ctxSeg, 0L, basePrice);
    }
}