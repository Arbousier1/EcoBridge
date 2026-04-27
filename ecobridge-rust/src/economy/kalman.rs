// ==================================================
// FILE: ecobridge-rust/src/economy/kalman.rs
// ==================================================

//! Kalman Filter for Price and M1 Supply State Estimation (v1.7.0)
//!
//! Implements a 3-state constant-acceleration Kalman filter for filtering
//! noisy economic observations and predicting future states.
//!
//! State vector: [position, velocity, acceleration]
//! Position = price level or M1 supply
//! Velocity = rate of change
//! Acceleration = change in velocity
//!
//! This replaces the raw linear M1 prediction with a statistically optimal
//! state estimate that accounts for process and measurement noise.

use std::sync::Mutex;
use std::collections::HashMap;
use std::sync::LazyLock;

/// 3-state Kalman filter (position, velocity, acceleration).
/// Uses constant-acceleration kinematics model.
#[derive(Debug, Clone)]
pub struct KalmanState {
    /// State vector: [pos, vel, acc]
    pub x: [f64; 3],
    /// Covariance matrix: 3x3, stored row-major
    pub p: [f64; 9],
    /// Process noise covariance diagonal: [pos_noise, vel_noise, acc_noise]
    pub q_diag: [f64; 3],
    /// Measurement noise (scalar — we observe position directly)
    pub r: f64,
    /// Flag: has the filter received its first measurement?
    pub initialized: bool,
}

impl KalmanState {
    pub fn new(process_noise_pos: f64, process_noise_vel: f64, process_noise_acc: f64, measurement_noise: f64) -> Self {
        Self {
            x: [0.0; 3],
            p: [
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            ],
            q_diag: [process_noise_pos, process_noise_vel, process_noise_acc],
            r: measurement_noise,
            initialized: false,
        }
    }
}

static KALMAN_STATES: LazyLock<Mutex<HashMap<String, KalmanState>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Initialize or reset a Kalman filter state for a given key.
pub fn kalman_init(key: &str) {
    let mut states = KALMAN_STATES.lock().unwrap();
    states.insert(key.to_string(), KalmanState::new(0.001, 0.01, 0.005, 0.05));
}

/// Initialize with custom noise parameters.
pub fn kalman_init_tuned(key: &str, q_pos: f64, q_vel: f64, q_acc: f64, r: f64) {
    let mut states = KALMAN_STATES.lock().unwrap();
    states.insert(key.to_string(), KalmanState::new(q_pos, q_vel, q_acc, r));
}

/// Predict step: propagate state forward by dt seconds.
/// Returns the predicted position.
pub fn kalman_predict(key: &str, dt: f64) -> f64 {
    let mut states = KALMAN_STATES.lock().unwrap();
    let state = states.entry(key.to_string()).or_insert_with(|| {
        KalmanState::new(0.001, 0.01, 0.005, 0.05)
    });

    if dt <= 0.0 || !dt.is_finite() {
        return state.x[0];
    }

    // State transition (constant acceleration model):
    // pos = pos + vel*dt + 0.5*acc*dt²
    // vel = vel + acc*dt
    // acc = acc
    let dt2 = 0.5 * dt * dt;

    // F matrix (3x3):
    // [1, dt, dt²/2]
    // [0, 1,  dt    ]
    // [0, 0,  1     ]
    let f = [
        1.0, dt, dt2,
        0.0, 1.0, dt,
        0.0, 0.0, 1.0,
    ];

    // x_pred = F * x
    let x_pred = mat_vec_mul_3x3(&f, &state.x);

    // P_pred = F * P * F^T + Q
    let fp = mat_mul_3x3(&f, &state.p);
    let p_pred_no_q = mat_mul_3x3_transpose_b(&fp, &f);

    // Add process noise Q (diagonal)
    let mut p_pred = p_pred_no_q;
    p_pred[0] += state.q_diag[0] * dt;
    p_pred[4] += state.q_diag[1] * dt;
    p_pred[8] += state.q_diag[2] * dt;

    state.x = x_pred;
    state.p = p_pred;

    state.x[0]
}

/// Update step: incorporate a new measurement of the position.
/// Returns the filtered (posterior) position estimate.
pub fn kalman_update(key: &str, measurement: f64) -> f64 {
    let mut states = KALMAN_STATES.lock().unwrap();
    let state = states.entry(key.to_string()).or_insert_with(|| {
        KalmanState::new(0.001, 0.01, 0.005, 0.05)
    });

    if !measurement.is_finite() {
        return state.x[0];
    }

    if !state.initialized {
        state.x[0] = measurement;
        state.x[1] = 0.0;
        state.x[2] = 0.0;
        state.initialized = true;
        return state.x[0];
    }

    // H = [1, 0, 0] (we measure position directly)
    // y = measurement - H*x = measurement - x[0]
    let y = measurement - state.x[0];

    // S = H*P*H^T + R = P[0] + R (since H picks first element)
    let s = state.p[0] + state.r;
    if s <= 0.0 {
        return state.x[0];
    }

    // K = P * H^T / S = [P[0], P[1], P[2]] / S
    let k0 = state.p[0] / s;
    let k1 = state.p[1] / s;
    let k2 = state.p[2] / s;

    // x = x + K*y
    state.x[0] += k0 * y;
    state.x[1] += k1 * y;
    state.x[2] += k2 * y;

    // P = (I - K*H) * P
    // I - K*H = [[1-k0, 0, 0], [-k1, 1, 0], [-k2, 0, 1]]
    let ikh = [
        1.0 - k0, 0.0, 0.0,
        -k1, 1.0, 0.0,
        -k2, 0.0, 1.0,
    ];
    state.p = mat_mul_3x3(&ikh, &state.p);

    state.x[0]
}

/// Combined predict + update: the most common operation.
/// Returns the filtered position after incorporating the measurement.
pub fn kalman_filter(key: &str, measurement: f64, dt: f64) -> f64 {
    kalman_predict(key, dt);
    kalman_update(key, measurement)
}

/// Get the current filtered velocity estimate.
pub fn kalman_velocity(key: &str) -> f64 {
    let states = KALMAN_STATES.lock().unwrap();
    if let Some(state) = states.get(key) {
        if state.initialized { state.x[1] } else { 0.0 }
    } else {
        0.0
    }
}

/// Get the full state: [position, velocity, acceleration].
pub fn kalman_state(key: &str) -> [f64; 3] {
    let states = KALMAN_STATES.lock().unwrap();
    if let Some(state) = states.get(key) {
        state.x
    } else {
        [0.0; 3]
    }
}

/// Free state for a given key.
pub fn kalman_free(key: &str) {
    let mut states = KALMAN_STATES.lock().unwrap();
    states.remove(key);
}

// --- 3x3 Matrix helpers ---

fn mat_vec_mul_3x3(m: &[f64; 9], v: &[f64; 3]) -> [f64; 3] {
    [
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    ]
}

fn mat_mul_3x3(a: &[f64; 9], b: &[f64; 9]) -> [f64; 9] {
    [
        a[0]*b[0] + a[1]*b[3] + a[2]*b[6], a[0]*b[1] + a[1]*b[4] + a[2]*b[7], a[0]*b[2] + a[1]*b[5] + a[2]*b[8],
        a[3]*b[0] + a[4]*b[3] + a[5]*b[6], a[3]*b[1] + a[4]*b[4] + a[5]*b[7], a[3]*b[2] + a[4]*b[5] + a[5]*b[8],
        a[6]*b[0] + a[7]*b[3] + a[8]*b[6], a[6]*b[1] + a[7]*b[4] + a[8]*b[7], a[6]*b[2] + a[7]*b[5] + a[8]*b[8],
    ]
}

fn mat_mul_3x3_transpose_b(a: &[f64; 9], b: &[f64; 9]) -> [f64; 9] {
    // a * b^T where both are 3x3
    [
        a[0]*b[0] + a[1]*b[1] + a[2]*b[2], a[0]*b[3] + a[1]*b[4] + a[2]*b[5], a[0]*b[6] + a[1]*b[7] + a[2]*b[8],
        a[3]*b[0] + a[4]*b[1] + a[5]*b[2], a[3]*b[3] + a[4]*b[4] + a[5]*b[5], a[3]*b[6] + a[4]*b[7] + a[5]*b[8],
        a[6]*b[0] + a[7]*b[1] + a[8]*b[2], a[6]*b[3] + a[7]*b[4] + a[8]*b[5], a[6]*b[6] + a[7]*b[7] + a[8]*b[8],
    ]
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_kalman_init_and_first_measurement() {
        kalman_init("test");
        let filtered = kalman_filter("test", 10.0, 1.0);
        assert!((filtered - 10.0).abs() < 0.001, "first measurement should set initial position");
    }

    #[test]
    fn test_kalman_smooths_noise() {
        kalman_init("smooth");
        kalman_filter("smooth", 100.0, 1.0);

        // Inject noisy measurements around 100
        let mut filtered = 100.0;
        for i in 0..20 {
            let noise = ((i as f64 * 7.0_f64).sin() - 0.5) * 10.0;
            filtered = kalman_filter("smooth", 100.0 + noise, 1.0);
        }
        // After 20 updates, filtered value should be close to 100
        assert!((filtered - 100.0).abs() < 3.0, "Kalman filter should smooth noise toward true value");
    }

    #[test]
    fn test_kalman_tracks_trend() {
        kalman_init("trend");
        kalman_filter("trend", 100.0, 1.0);

        // Rising trend: measurement increases by ~1 each step
        for i in 1..30 {
            kalman_filter("trend", 100.0 + i as f64, 1.0);
        }

        let vel = kalman_velocity("trend");
        assert!(vel > 0.5, "velocity should track upward trend, got: {}", vel);
    }

    #[test]
    fn test_kalman_negative_dt_handled() {
        kalman_init("neg_dt");
        kalman_filter("neg_dt", 50.0, 1.0);
        let pos = kalman_predict("neg_dt", -1.0);
        assert!((pos - 50.0).abs() < 0.01, "negative dt should return current position unchanged");
    }

    #[test]
    fn test_kalman_nan_measurement_ignored() {
        kalman_init("nan_meas");
        kalman_filter("nan_meas", 100.0, 1.0);
        let pos = kalman_update("nan_meas", f64::NAN);
        assert!((pos - 100.0).abs() < 0.01, "NaN measurement should not update state");
    }

    #[test]
    fn test_kalman_state_vector_access() {
        kalman_init("state_test");
        kalman_filter("state_test", 100.0, 1.0);
        kalman_filter("state_test", 105.0, 1.0);

        let [pos, vel, _acc] = kalman_state("state_test");
        assert!(pos > 100.0, "position should move toward 105");
        assert!(vel > 0.0, "velocity should be positive with rising values");
    }

    #[test]
    fn test_kalman_free() {
        kalman_init("to_free");
        kalman_filter("to_free", 10.0, 1.0);
        kalman_free("to_free");
        let pos = kalman_velocity("to_free"); // accessing freed key returns 0
        assert!((pos - 0.0).abs() < 1e-10);
    }
}
