// =============== ecobridge-rust/src/economy/control.rs ===============
use crate::models::PidState;

// PID 控制器常量
pub const DEFAULT_INTEGRATION_LIMIT: f64 = 30.0;
pub const MAX_SAFE_DT: f64 = 1.0;
pub const MIN_TIME_STEP: f64 = 1e-6;
pub const OUTPUT_MIN_CLAMP: f64 = 0.5;
pub const OUTPUT_MAX_CLAMP: f64 = 5.0;
pub const OUTPUT_BASELINE: f64 = 1.0;
pub const INTEGRAL_DECAY: f64 = 0.99999;
pub const BACK_CALC_GAIN: f64 = 0.2;
pub const DERIVATIVE_FILTER_ALPHA: f64 = 0.3;

#[inline]
fn sigmoid(x: f64) -> f64 {
    1.0 / (1.0 + (-x).exp())
}

/// 纯 Rust 实现的 PID 步进计算
/// 由 lib.rs 负责解包指针并调用此函数
pub fn compute_pid_adjustment_internal(
    pid: &mut PidState,
    target_vel: f64,
    current_vel: f64,
    dt: f64,
    inflation: f64,
) -> f64 {
    // 1. [修复] 输入参数严格校验
    // 防止 NaN, Inf 污染 PID 状态，或负 dt 导致积分反向
    if !target_vel.is_finite() || !current_vel.is_finite() 
       || !dt.is_finite() || dt < 0.0 
       || !inflation.is_finite() {
        return OUTPUT_BASELINE;
    }

    let error = target_vel - current_vel;
    let dt_safe = dt.clamp(0.0, MAX_SAFE_DT);
    
    // 动态参数调度 (Gain Scheduling)
    let schedule_gamma = 1.0 + sigmoid((inflation - 0.05) * 20.0);
    let active_kp = pid.kp * schedule_gamma;
    let active_ki = pid.ki * schedule_gamma;
    
    // 积分抗饱和 (Anti-windup) 与泄漏 (Leakage)
    let combined_leakage = (1.0 - pid.lambda.clamp(0.0, 1.0)) * INTEGRAL_DECAY;
    
    if pid.is_saturated != 0 {
        // 饱和时使用反向计算 (Back-calculation)
        let back_calc = error * BACK_CALC_GAIN;
        pid.integral = pid.integral.mul_add(combined_leakage, back_calc * dt_safe);
    } else {
        pid.integral = pid.integral.mul_add(combined_leakage, error * dt_safe);
    }
    
    // 积分限幅
    let limit = if pid.integration_limit > 0.0 { pid.integration_limit } else { DEFAULT_INTEGRATION_LIMIT };
    pid.integral = pid.integral.clamp(-limit, limit);
    
    // 微分滤波
    let delta_pv = current_vel - pid.prev_pv;
    let raw_derivative = if dt_safe > MIN_TIME_STEP { delta_pv / dt_safe } else { 0.0 };
    pid.filtered_d = DERIVATIVE_FILTER_ALPHA.mul_add(
        raw_derivative,
        (1.0 - DERIVATIVE_FILTER_ALPHA) * pid.filtered_d
    );
    pid.prev_pv = current_vel;
    
    // 计算输出
    let p_term = active_kp * error;
    let i_term = active_ki * pid.integral;
    let d_term = pid.kd * pid.filtered_d;
    
    let raw_output = OUTPUT_BASELINE + p_term + i_term - d_term;
    let final_output = raw_output.clamp(OUTPUT_MIN_CLAMP, OUTPUT_MAX_CLAMP);
    
    // 更新饱和状态标志
    if (raw_output - final_output).abs() > 1e-6 {
        pid.is_saturated = 1;
    } else {
        pid.is_saturated = 0;
    }
    
    // 最终输出防御
    if final_output.is_finite() { final_output } else { OUTPUT_BASELINE }
}

pub fn validate_pid_params(pid: &PidState) -> bool {
    pid.kp.is_finite() && pid.kp >= 0.0
        && pid.ki.is_finite() && pid.ki >= 0.0
        && pid.kd.is_finite() && pid.kd >= 0.0
        && pid.lambda.is_finite() && (0.0..=1.0).contains(&pid.lambda)
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_anti_windup_clamping() {
        let mut pid = PidState::default();
        pid.ki = 10.0;
        // 模拟持续误差导致饱和
        for _ in 0..100 {
            compute_pid_adjustment_internal(&mut pid, 100.0, 50.0, 0.1, 0.0);
        }
        assert_eq!(pid.is_saturated, 1);
        
        let saturated_integral = pid.integral;
        // 再推一次
        compute_pid_adjustment_internal(&mut pid, 100.0, 50.0, 0.1, 0.0);
        // 积分项不应无限增长
        assert!(pid.integral <= saturated_integral * 1.05);
    }

    #[test]
    fn test_invalid_inputs_resilience() {
        let mut pid = PidState::default();
        // 正常初始化
        pid.kp = 1.0; pid.ki = 0.5;

        // 1. 测试 NaN 输入
        let out_nan = compute_pid_adjustment_internal(&mut pid, f64::NAN, 50.0, 0.1, 0.0);
        assert_eq!(out_nan, OUTPUT_BASELINE);
        // 确保积分项未被污染
        assert_eq!(pid.integral, 0.0);

        // 2. 测试 Infinity 输入
        let out_inf = compute_pid_adjustment_internal(&mut pid, 100.0, f64::INFINITY, 0.1, 0.0);
        assert_eq!(out_inf, OUTPUT_BASELINE);

        // 3. 测试负 DT
        let out_neg_dt = compute_pid_adjustment_internal(&mut pid, 100.0, 50.0, -0.5, 0.0);
        assert_eq!(out_neg_dt, OUTPUT_BASELINE);
    }
}