// =============== ecobridge-rust/src/economy/environment.rs ===============
use crate::models::{TradeContext, MarketConfig};

// ==================== 时间常量 ====================
const SECONDS_PER_DAY: f64 = 86400.0;
const SECONDS_PER_WEEK: f64 = 604800.0;
const SECONDS_PER_MONTH: f64 = 2592000.0;

// ==================== 辅助数学函数 ====================

#[inline]
fn sigmoid(x: f64) -> f64 {
    1.0 / (1.0 + (-x * 10.0).exp())
}

// ==================== 核心逻辑实现 ====================

/// 纯 Rust 实现的环境因子计算 (v0.8.2 Timezone Fixed)
/// 
/// 修复日志：
/// 1. 引入 `timezone_offset` 修正，确保周末和昼夜波形符合服务器本地时间。
/// 2. 统一使用 `ts_sec_local` 进行演算。
pub fn calculate_epsilon_internal(
    ctx: &TradeContext,
    cfg: &MarketConfig,
) -> f64 {
    // 1. [关键修复] 时区对齐
    // Java 侧传入的 current_timestamp 是 UTC 毫秒
    // timezone_offset 是本地时区相对于 UTC 的偏移秒数 (例如新加坡为 +28800)
    let ts_sec_utc = (ctx.current_timestamp as f64) / 1000.0;
    let offset_sec = ctx.timezone_offset as f64;
    
    // 转换为本地秒数：用于判断"当地是否是白天"以及"当地是否是周末"
    let ts_sec_local = ts_sec_utc + offset_sec;
    
    // 辅助闭包：安全自然对数
    let safe_ln = |factor: f64| factor.max(0.01).ln();

    // 2. 季节性因子 (Seasonal Factor) - 基于本地时间
    // 我们希望 day_wave 的峰值出现在当地的中午，而不是 UTC 的中午
    let day_wave = (ts_sec_local * 2.0 * std::f64::consts::PI / SECONDS_PER_DAY).sin();
    let week_wave = (ts_sec_local * 2.0 * std::f64::consts::PI / SECONDS_PER_WEEK).sin();
    let month_wave = (ts_sec_local * 2.0 * std::f64::consts::PI / SECONDS_PER_MONTH).sin();
    
    let seasonal_factor = 0.6 * day_wave + 0.3 * week_wave + 0.1 * month_wave;
    let mut f_sea = 1.0 + cfg.seasonal_amplitude * seasonal_factor;
    
    // Festival Mode 检查
    if (ctx.newbie_mask >> 1) & 1 == 1 {
        f_sea *= 1.15; 
    }

    // 3. 周末因子 (Weekend Factor) - 基于本地时间
    // Unix Epoch (1970-01-01 00:00:00 UTC) 是周四
    // 本地时间的 epoch 偏移计算：
    // day_index = floor(local_seconds / 86400)
    // (day_index + 4) % 7 -> 0=Sun, ..., 4=Thu, 5=Fri, 6=Sat
    let day_index = (ts_sec_local / SECONDS_PER_DAY).floor() as i64;
    // 使用 .rem_euclid 确保负数时间戳也能正确取模 (Rust % 运算符对负数行为不同)
    let day_of_week = (day_index + 4).rem_euclid(7);
    
    let f_wk = if day_of_week >= 5 { cfg.weekend_multiplier } else { 1.0 };

    // 4. 新手保护因子
    let f_nb = if (ctx.newbie_mask & 1) == 1 {
        1.0 - cfg.newbie_protection_rate
    } else {
        1.0
    };

    // 5. 通胀反馈因子
    let sigmoid_trigger = sigmoid(ctx.inflation_rate - 0.05);
    let f_inf = 1.0 + (ctx.inflation_rate * 0.2 * sigmoid_trigger);

    // 6. 对数加权合成
    let log_eps = 
          cfg.seasonal_weight   * safe_ln(f_sea)
        + cfg.weekend_weight    * safe_ln(f_wk)
        + cfg.newbie_weight     * safe_ln(f_nb)
        + cfg.inflation_weight  * safe_ln(f_inf);

    log_eps.exp().clamp(0.1, 10.0)
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{TradeContext, MarketConfig};

    #[test]
    fn test_weekend_logic_utc() {
        let mut cfg = MarketConfig::default();
        cfg.weekend_multiplier = 2.0;
        cfg.weekend_weight = 1.0;
        // 屏蔽其他干扰
        cfg.seasonal_weight = 0.0;
        cfg.newbie_weight = 0.0;
        cfg.inflation_weight = 0.0;

        // 1970-01-03 (周六) UTC
        let sat_ts = 2 * 86400 * 1000; 
        let ctx = TradeContext {
            current_timestamp: sat_ts,
            timezone_offset: 0, // UTC
            ..Default::default()
        };
        
        let eps = calculate_epsilon_internal(&ctx, &cfg);
        assert!((eps - 2.0).abs() < 1e-4);
    }

    #[test]
    fn test_weekend_logic_timezone_shift() {
        let mut cfg = MarketConfig::default();
        cfg.weekend_multiplier = 2.0;
        cfg.weekend_weight = 1.0;
        cfg.seasonal_weight = 0.0;
        cfg.newbie_weight = 0.0;
        cfg.inflation_weight = 0.0;

        // 极端场景：UTC 周四 23:00
        // UTC: 1970-01-01 (Thu) 23:00 = 82800s
        let thu_night_utc = 82_800 * 1000;
        
        // Case A: 在伦敦 (UTC+0)，还是周四 -> 平日 (1.0)
        let ctx_london = TradeContext {
            current_timestamp: thu_night_utc,
            timezone_offset: 0,
            ..Default::default()
        };
        let eps_london = calculate_epsilon_internal(&ctx_london, &cfg);
        assert!((eps_london - 1.0).abs() < 1e-4, "London should be Thursday (1.0)");

        // Case B: 在新加坡 (UTC+8)，已经是周五 07:00 -> 周末 (2.0)
        // 82800 + 8*3600 = 111600s (Fri)
        let ctx_sg = TradeContext {
            current_timestamp: thu_night_utc,
            timezone_offset: 8 * 3600, // +8 Hours
            ..Default::default()
        };
        let eps_sg = calculate_epsilon_internal(&ctx_sg, &cfg);
        assert!((eps_sg - 2.0).abs() < 1e-4, "Singapore should be Friday (2.0)");
    }
}