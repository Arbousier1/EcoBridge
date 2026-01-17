use crate::models::PidState;

// ==================== 基础物理常量 ====================

pub const DEFAULT_INTEGRATION_LIMIT: f64 = 30.0;
pub const MAX_SAFE_DT: f64 = 1.0;
pub const MIN_TIME_STEP: f64 = 1e-6;
pub const OUTPUT_MIN_CLAMP: f64 = 0.5;
pub const OUTPUT_MAX_CLAMP: f64 = 5.0;
pub const OUTPUT_BASELINE: f64 = 1.0;

// ==================== 稳定性与滤波常量 ====================

pub const INTEGRAL_DECAY: f64 = 0.99999;
pub const BACK_CALC_GAIN: f64 = 0.2;
pub const DERIVATIVE_FILTER_ALPHA: f64 = 0.3;

// ==================== 行为经济学：宏观调控常量 ====================

pub const PANIC_THRESHOLD: f64 = 50.0;     // 触发恐慌抑制的加速度阈值
pub const PANIC_DAMPING: f64 = 1.8;       // 恐慌状态下的微分项放大倍数
pub const HEAT_SENSITIVITY: f64 = 0.5;    // 财富流速感应灵敏度

#[inline]
fn sigmoid(x: f64) -> f64 {
    1.0 / (1.0 + (-x).exp())
}

// ==================== 1. 自适应价格弹性核心 (Macro-Control) ====================

/// 根据市场热度（财富流速）动态计算自适应增益
/// 解决了传统机械调节在长周期货币沉淀时的滞后问题
pub fn compute_adaptive_gain(cfg: &PidState, heat: f64) -> (f64, f64) {
    // 使用 tanh 压缩热度影响，防止极端流速导致系统失控
    // 当流速(heat)越高，系统弹性越大，响应越灵敏
    let sensitivity = (heat * HEAT_SENSITIVITY).tanh(); 
    
    // Kp 随流速正向增强：提高市场对新交易的即时反应速度
    let adaptive_kp = cfg.kp * (1.0 + sensitivity);
    
    // Ki 随流速反向衰减：防止高频流转环境下产生的严重积分饱和
    let adaptive_ki = cfg.ki * (1.0 - sensitivity * 0.5); 
    
    (adaptive_kp, adaptive_ki)
}

// ==================== 2. 全自适应 PID 调节核心 ====================

/// 演进后的宏观调控步进计算
/// 结合了流速感应、通胀调度与恐慌阻尼
pub fn compute_pid_adjustment_internal(
    pid: &mut PidState,
    target_vel: f64,
    current_vel: f64,
    dt: f64,
    inflation: f64,
    market_heat: f64, // 财富流速反馈因子
) -> f64 {
    // 1. 输入参数严格校验
    if !target_vel.is_finite() || !current_vel.is_finite() 
       || !dt.is_finite() || dt < 0.0 
       || !inflation.is_finite() || !market_heat.is_finite() {
        return OUTPUT_BASELINE;
    }

    let error = target_vel - current_vel;
    let dt_safe = dt.clamp(0.0, MAX_SAFE_DT);

    // 2. 计算基于流速的自适应基础增益
    let (base_kp, base_ki) = compute_adaptive_gain(pid, market_heat);

    // 3. 叠加宏观周期调度 (Gain Scheduling)
    // 通胀率越高，gamma 越大，强制系统进入收缩模式
    let schedule_gamma = 1.0 + sigmoid((inflation - 0.05) * 20.0);
    let active_kp = base_kp * schedule_gamma;
    let active_ki = base_ki * schedule_gamma;
    
    // 4. 积分项处理 (Anti-windup & Leakage)
    let combined_leakage = (1.0 - pid.lambda.clamp(0.0, 1.0)) * INTEGRAL_DECAY;
    
    if pid.is_saturated != 0 {
        // 饱和状态引入 Back-calculation，加速退出锁定区
        let back_calc = error * BACK_CALC_GAIN;
        pid.integral = pid.integral.mul_add(combined_leakage, back_calc * dt_safe);
    } else {
        pid.integral = pid.integral.mul_add(combined_leakage, error * dt_safe);
    }
    
    let limit = if pid.integration_limit > 0.0 { pid.integration_limit } else { DEFAULT_INTEGRATION_LIMIT };
    pid.integral = pid.integral.clamp(-limit, limit);
    
    // 5. 微分项处理 (滤波与加速度捕捉)
    let delta_pv = current_vel - pid.prev_pv;
    let raw_derivative = if dt_safe > MIN_TIME_STEP { delta_pv / dt_safe } else { 0.0 };
    
    // 低通滤波滤除瞬时噪声
    pid.filtered_d = DERIVATIVE_FILTER_ALPHA.mul_add(
        raw_derivative,
        (1.0 - DERIVATIVE_FILTER_ALPHA) * pid.filtered_d
    );
    pid.prev_pv = current_vel;

    // 6. 恐慌抑制逻辑 (Panic Suppression)
    let d_multiplier = if pid.filtered_d.abs() > PANIC_THRESHOLD {
        PANIC_DAMPING
    } else {
        1.0
    };
    
    // 7. 合成最终调节量
    let p_term = active_kp * error;
    let i_term = active_ki * pid.integral;
    let d_term = pid.kd * pid.filtered_d * d_multiplier; 
    
    let raw_output = OUTPUT_BASELINE + p_term + i_term - d_term;
    let final_output = raw_output.clamp(OUTPUT_MIN_CLAMP, OUTPUT_MAX_CLAMP);
    
    // 更新饱和状态标志
    pid.is_saturated = if (raw_output - final_output).abs() > 1e-6 { 1 } else { 0 };
    
    if final_output.is_finite() { final_output } else { OUTPUT_BASELINE }
}

/// 验证 PID 配置参数的合法性
pub fn validate_pid_params(pid: &PidState) -> bool {
    pid.kp.is_finite() && pid.kp >= 0.0
        && pid.ki.is_finite() && pid.ki >= 0.0
        && pid.kd.is_finite() && pid.kd >= 0.0
        && pid.lambda.is_finite() && (0.0..=1.0).contains(&pid.lambda)
}

// ==================== 自动化回归测试 ====================

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_macro_adaptive_gains() {
        let pid = PidState::default();
        let (kp_low, _) = compute_adaptive_gain(&pid, 0.1);
        let (kp_high, _) = compute_adaptive_gain(&pid, 10.0);
        assert!(kp_high > kp_low, "高流速下市场弹性应自动增强");
    }

    #[test]
    fn test_anti_windup_clamping() {
        let mut pid = PidState::default();
        pid.ki = 10.0;
        for _ in 0..100 {
            compute_pid_adjustment_internal(&mut pid, 100.0, 50.0, 0.1, 0.0, 1.0);
        }
        assert_eq!(pid.is_saturated, 1, "系统应正确识别饱和状态");
    }

    #[test]
    fn test_panic_damping_logic() {
        let mut pid = PidState::default();
        pid.kd = 1.0;
        // 模拟价格雪崩加速度
        compute_pid_adjustment_internal(&mut pid, 10.0, 0.0, 0.1, 0.0, 1.0);
        let out = compute_pid_adjustment_internal(&mut pid, 10.0, 80.0, 0.1, 0.0, 1.0);
        assert!(out < OUTPUT_BASELINE, "恐慌状态下 D项应产生强力反向压制");
    }
}