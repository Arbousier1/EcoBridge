// ==================================================
// FILE: ecobridge-rust/src/economy/macro_eco.rs
// ==================================================

//! Macro Economy Mathematics Module
//! 
//! 本模块负责宏观经济指标的纯数学计算。
//! 包含: 通胀率 (Inflation), 市场稳定性 (Stability), 热度衰减 (Decay).
//! 设计原则: 无副作用 (Pure Functions), 数值稳定 (Clamped).

/// 计算通货膨胀率 (Inflation Rate)
/// 
/// 公式: ε = (当前流通热度 / M1货币总量)
/// 约束: 结果被强制钳位在 [-0.15, 0.45] 之间，防止恶性通胀或通缩导致价格崩盘。
/// 
/// # Arguments
/// * `current_heat` - 当前市场的流通热度 (Circulation Heat)
/// * `m1_supply` - M1 广义货币供应量
#[inline(always)]
pub fn calculate_inflation_rate(current_heat: f64, m1_supply: f64) -> f64 {
    // 防御性编程: 防止除以零或负资产
    if m1_supply <= 1.0 { 
        return 0.0; 
    }
    
    let raw_rate = current_heat / m1_supply;
    
    // 硬约束: 通胀率最高 45%, 通缩率最高 15%
    raw_rate.clamp(-0.15, 0.45)
}

/// 计算市场稳定性因子 (Stability Factor)
/// 
/// 逻辑: 距离上一次剧烈波动(Volatile Event)时间越久，市场越稳定。
/// 这是一个线性恢复函数 (Linear Recovery)。
/// 
/// # Arguments
/// * `last_volatile_ts` - 上一次触发熔断/剧烈波动的时间戳 (ms)
/// * `current_ts` - 当前时间戳 (ms)
/// * `recovery_window_ms` - 恢复满稳定性所需的窗口期 (通常为 15分钟 = 900,000ms)
#[inline(always)]
pub fn calculate_stability(
    last_volatile_ts: i64, 
    current_ts: i64, 
    recovery_window_ms: f64
) -> f64 {
    // 如果从未发生过波动 (0), 则市场绝对稳定
    if last_volatile_ts <= 0 { 
        return 1.0; 
    }
    
    let diff = (current_ts - last_volatile_ts) as f64;
    
    // 时间异常保护 (如系统时间回拨)
    if diff < 0.0 {
        return 1.0;
    }
    
    // 归一化并钳位: [0.0 (极度恐慌) -> 1.0 (完全冷静)]
    (diff / recovery_window_ms).clamp(0.0, 1.0)
}

/// 计算热度自然衰减量 (Decay Amount)
/// 
/// 逻辑: 市场热度会随时间自然冷却，回归均衡。
/// 如果热度极低 (abs < 1.0)，则一次性扣除所有热度以归零。
/// 
/// # Arguments
/// * `current_heat` - 当前热度
/// * `daily_decay_rate` - 每日衰减比率 (如 0.05 代表每日衰减 5%)
/// * `cycles_per_day` - 每日执行衰减任务的次数 (如 48 次)
#[inline(always)]
pub fn calculate_decay(current_heat: f64, daily_decay_rate: f64, cycles_per_day: f64) -> f64 {
    // 阈值检查: 如果热度微乎其微，直接返回当前热度
    // Java端会执行 add(-reduction)，即 add(-current_heat)，从而使其归零
    if current_heat.abs() < 1.0 { 
        return current_heat; 
    }
    
    // 计算单周期衰减率
    let per_cycle_rate = daily_decay_rate / cycles_per_day;
    
    // 返回本周期应扣除的量
    current_heat * per_cycle_rate
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_inflation_clamp() {
        assert_eq!(calculate_inflation_rate(100.0, 1000.0), 0.10);
        assert_eq!(calculate_inflation_rate(5000.0, 1000.0), 0.45); // Clamped max
        assert_eq!(calculate_inflation_rate(-200.0, 1000.0), -0.15); // Clamped min
    }

    #[test]
    fn test_stability_recovery() {
        let window = 1000.0;
        assert_eq!(calculate_stability(0, 100, window), 1.0); // Never volatile
        assert_eq!(calculate_stability(1000, 1500, window), 0.5); // Halfway recovered
        assert_eq!(calculate_stability(1000, 2500, window), 1.0); // Fully recovered
    }

    #[test]
    fn test_decay_logic() {
        // Normal decay
        let reduction = calculate_decay(1000.0, 0.48, 48.0); // 1% per cycle
        assert_eq!(reduction, 10.0);

        // Reset logic
        let reset_val = calculate_decay(0.5, 0.1, 48.0);
        assert_eq!(reset_val, 0.5); // Should return full value for reset
    }
}