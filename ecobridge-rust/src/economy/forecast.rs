// ==================================================
// FILE: ecobridge-rust/src/economy/forecast.rs
// ==================================================

//! ARIMA-style Time Series Forecasting Module (v1.7.0)
//!
//! Implements a simplified AR(p) model with integrated differencing for
//! non-stationary time series. Designed for 12-24 hour price trend prediction
//! in game-economy contexts.
//!
//! Model: ARIMA(p, d, 0) where:
//! - p = autoregressive order (1-5)
//! - d = differencing order (0-2, for non-stationary series)
//! - MA component omitted for simplicity and FFI compatibility
//!
//! Coefficients are estimated using the Yule-Walker equations (method of moments).

use std::sync::Mutex;
use std::collections::HashMap;
use std::sync::LazyLock;

/// ARIMA model state for a single forecast series.
#[derive(Debug, Clone)]
pub struct ArimaState {
    /// Autoregressive order
    pub p: usize,
    /// Differencing order
    pub d: usize,
    /// AR coefficients (length p)
    pub phi: Vec<f64>,
    /// Ring buffer of recent observations (differenced)
    pub history: Vec<f64>,
    /// Raw observation buffer (before differencing)
    pub raw_history: Vec<f64>,
    /// Maximum history length
    pub max_history: usize,
    /// Number of observations seen
    pub count: usize,
}

impl ArimaState {
    pub fn new(p: usize, d: usize) -> Self {
        assert!(p >= 1 && p <= 10, "AR order must be 1-10");
        assert!(d <= 2, "differencing order must be 0-2");
        Self {
            p,
            d,
            phi: vec![0.0; p],
            history: Vec::with_capacity(256),
            raw_history: Vec::with_capacity(256),
            max_history: 256,
            count: 0,
        }
    }
}

static ARIMA_STATES: LazyLock<Mutex<HashMap<String, ArimaState>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Initialize ARIMA predictor for a given key.
pub fn arima_init(key: &str, p: usize, d: usize) {
    let mut states = ARIMA_STATES.lock().unwrap();
    let p_clamped = p.clamp(1, 10);
    let d_clamped = d.min(2);
    states.insert(key.to_string(), ArimaState::new(p_clamped, d_clamped));
}

/// Add an observation and optionally re-estimate coefficients.
pub fn arima_add_observation(key: &str, value: f64) {
    let mut states = ARIMA_STATES.lock().unwrap();
    let state = match states.get_mut(key) {
        Some(s) => s,
        None => return,
    };

    if !value.is_finite() {
        return;
    }

    state.raw_history.push(value);
    if state.raw_history.len() > state.max_history {
        state.raw_history.remove(0);
    }

    // Apply differencing
    let diff_val = if state.d == 0 {
        value
    } else if state.d == 1 {
        if state.raw_history.len() >= 2 {
            let prev = state.raw_history[state.raw_history.len() - 2];
            value - prev
        } else {
            value
        }
    } else {
        // d == 2: second difference
        if state.raw_history.len() >= 3 {
            let n = state.raw_history.len();
            let cur = state.raw_history[n - 1];
            let prev1 = state.raw_history[n - 2];
            let prev2 = state.raw_history[n - 3];
            (cur - prev1) - (prev1 - prev2)
        } else if state.raw_history.len() >= 2 {
            let n = state.raw_history.len();
            state.raw_history[n - 1] - state.raw_history[n - 2]
        } else {
            value
        }
    };

    state.history.push(diff_val);
    if state.history.len() > state.max_history {
        state.history.remove(0);
    }
    state.count += 1;

    // Re-estimate AR coefficients using Yule-Walker (method of moments)
    if state.history.len() >= state.p + 2 {
        estimate_ar_coefficients(state);
    }
}

/// Estimate AR coefficients using the Yule-Walker equations.
fn estimate_ar_coefficients(state: &mut ArimaState) {
    let n = state.history.len();
    let p = state.p;
    if n < p + 2 {
        return;
    }

    let mean: f64 = state.history.iter().sum::<f64>() / n as f64;
    let centered: Vec<f64> = state.history.iter().map(|v| v - mean).collect();

    // Autocovariance: gamma[k] = E[(x_t - μ)(x_{t-k} - μ)]
    let gamma = |k: usize| -> f64 {
        let mut sum = 0.0;
        for t in k..n {
            sum += centered[t] * centered[t - k];
        }
        sum / (n - k) as f64
    };

    // Build Toeplitz matrix R (p x p) and vector r (p x 1)
    // R[i][j] = gamma(|i-j|)
    // r[i] = gamma(i+1)
    // Solve R * phi = r using Levinson-Durbin

    let mut r = vec![0.0; p];
    for i in 0..p {
        r[i] = gamma(i + 1);
    }

    let g0 = gamma(0);
    if g0 <= 0.0 || !g0.is_finite() {
        return;
    }

    // Levinson-Durbin recursion
    let mut phi = vec![0.0; p];
    let mut v = g0; // prediction error variance

    phi[0] = r[0] / v;
    v *= 1.0 - phi[0] * phi[0];

    for k in 1..p {
        let mut sum = 0.0;
        for j in 0..k {
            sum += phi[j] * gamma(k - j);
        }
        let reflection = (r[k] - sum) / v;

        // Update phi[0..k]
        let mut new_phi = phi.clone();
        new_phi[k] = reflection;
        for j in 0..k {
            new_phi[j] = phi[j] - reflection * phi[k - 1 - j];
        }

        phi = new_phi;
        v *= 1.0 - reflection * reflection;
        if v <= 0.0 {
            v = 1e-10;
        }
    }

    state.phi = phi;
}

/// Predict H steps ahead. Returns vector of predictions.
pub fn arima_predict(key: &str, horizon: usize) -> Vec<f64> {
    let states = ARIMA_STATES.lock().unwrap();
    let state = match states.get(key) {
        Some(s) => s,
        None => return vec![],
    };

    if state.history.is_empty() || horizon == 0 {
        return vec![];
    }

    let p = state.p;
    let max_horizon = horizon.min(48);
    let mut forecasts = Vec::with_capacity(max_horizon);

    // Use last p values for prediction
    let mut window: Vec<f64> = state.history.iter().rev().take(p).copied().collect();
    window.reverse();

    for _step in 0..max_horizon {
        let mut pred: f64 = 0.0;
        for i in 0..p {
            let idx = window.len() - 1 - i;
            pred += state.phi[i] * window[idx];
        }
        forecasts.push(pred);
        window.push(pred);
        if window.len() > p {
            window.remove(0);
        }
    }

    // Reverse the differencing
    if state.d >= 1 {
        let last_raw = state.raw_history.last().copied().unwrap_or(0.0);
        for i in 0..max_horizon {
            if i == 0 {
                forecasts[i] = last_raw + forecasts[i];
            } else {
                forecasts[i] = forecasts[i - 1] + forecasts[i];
            }
        }
    }
    if state.d >= 2 {
        // Level 2: integrate the first-differenced forecasts
        // (first integration)
        let mut integrated = vec![0.0; max_horizon];
        for i in 0..max_horizon {
            if i == 0 {
                integrated[i] = forecasts[i];
            } else {
                integrated[i] = integrated[i - 1] + forecasts[i];
            }
        }
        forecasts = integrated;
    }

    forecasts
}

/// Predict a single value H steps ahead.
pub fn arima_predict_single(key: &str, horizon: usize) -> f64 {
    let preds = arima_predict(key, horizon);
    if preds.is_empty() {
        return 0.0;
    }
    preds[preds.len() - 1]
}

/// Free ARIMA state for a key.
pub fn arima_free(key: &str) {
    let mut states = ARIMA_STATES.lock().unwrap();
    states.remove(key);
}

/// Get current AR coefficients (for diagnostics).
pub fn arima_coefficients(key: &str) -> Vec<f64> {
    let states = ARIMA_STATES.lock().unwrap();
    if let Some(state) = states.get(key) {
        state.phi.clone()
    } else {
        vec![]
    }
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_arima_init_and_predict() {
        arima_init("test", 2, 0);

        // Feed a simple sine wave
        for i in 0..50 {
            let val = 10.0 + (i as f64 * 0.02).sin();
            arima_add_observation("test", val);
        }

        let pred = arima_predict_single("test", 5);
        assert!(pred.is_finite(), "prediction should be finite");
    }

    #[test]
    fn test_arima_predicts_near_values() {
        arima_init("near", 2, 0);

        // Steady trend around 100
        for i in 0..40 {
            arima_add_observation("near", 100.0 + i as f64 * 0.1);
        }

        let preds = arima_predict("near", 3);
        assert_eq!(preds.len(), 3);
        // Predictions should be near 103-104 range
        assert!(preds[0] > 99.0 && preds[0] < 106.0, "prediction should be near recent values");
    }

    #[test]
    fn test_arima_differencing_stationary() {
        arima_init("diff", 2, 1); // AR(2) with first difference

        // Random walk: values drift
        let mut val = 100.0;
        for i in 0..60 {
            val += ((i as f64 * 7.0_f64).sin()) * 2.0;
            arima_add_observation("diff", val);
        }

        let preds = arima_predict("diff", 5);
        assert_eq!(preds.len(), 5);
        for p in &preds {
            assert!(p.is_finite());
        }
    }

    #[test]
    fn test_arima_single_predict_equals_last_of_multi() {
        arima_init("single", 1, 0);
        for i in 0..30 {
            arima_add_observation("single", i as f64);
        }

        let single = arima_predict_single("single", 5);
        let multi = arima_predict("single", 5);
        let last = multi.last().copied().unwrap_or(0.0);
        assert!((single - last).abs() < 1e-6);
    }

    #[test]
    fn test_arima_nan_observation_ignored() {
        arima_init("nan", 2, 0);
        for i in 0..20 {
            arima_add_observation("nan", i as f64);
        }
        arima_add_observation("nan", f64::NAN);
        let pred = arima_predict_single("nan", 3);
        assert!(pred.is_finite());
    }

    #[test]
    fn test_arima_empty_returns_empty() {
        arima_init("empty", 2, 0);
        let preds = arima_predict("empty", 5);
        assert!(preds.is_empty(), "no observations should produce empty predictions");
    }

    #[test]
    fn test_arima_coefficients_converge() {
        arima_init("coef", 2, 0);

        // AR(1) process: x_t = 0.7 * x_{t-1} + noise
        let mut val = 0.0;
        for i in 0..100 {
            val = 0.7 * val + ((i as f64 * 3.0_f64).sin()) * 0.1;
            arima_add_observation("coef", val);
        }

        let phi = arima_coefficients("coef");
        assert_eq!(phi.len(), 2);
        // phi[0] should be near 0.7 for this AR(1)-like process
        assert!(phi[0] > 0.4 && phi[0] < 0.95, "AR coefficient should be near 0.7, got: {}", phi[0]);
    }

    #[test]
    fn test_arima_free() {
        arima_init("free", 2, 0);
        arima_add_observation("free", 10.0);
        arima_free("free");
        let preds = arima_predict("free", 3);
        assert!(preds.is_empty());
    }
}
