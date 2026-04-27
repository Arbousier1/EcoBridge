// ==================================================
// FILE: ecobridge-rust/src/economy/volatility.rs
// ==================================================

//! GARCH(1,1) Volatility Modeling Module (v1.7.0)
//!
//! Implements a GARCH(1,1) model for market volatility estimation.
//! GARCH captures volatility clustering — large price changes tend to
//! be followed by large price changes, a well-known stylized fact in
//! financial and game-economy time series.
//!
//! # Model
//! sigma_t^2 = omega + alpha * epsilon_{t-1}^2 + beta * sigma_{t-1}^2
//!
//! Constraints: alpha + beta < 1.0 (stationarity), omega > 0

use std::sync::Mutex;
use lazy_static::lazy_static;
use std::collections::HashMap;

/// GARCH(1,1) state for a single market or asset.
#[derive(Debug, Clone)]
pub struct GarchState {
    pub alpha: f64,
    pub beta: f64,
    pub omega: f64,
    pub last_return: f64,
    pub last_variance: f64,
    pub initialized: bool,
}

impl GarchState {
    pub fn new(alpha: f64, beta: f64, omega: f64) -> Self {
        Self {
            alpha,
            beta,
            omega,
            last_return: 0.0,
            last_variance: omega / (1.0 - alpha - beta).max(1e-10),
            initialized: false,
        }
    }
}

lazy_static! {
    static ref GARCH_STATES: Mutex<HashMap<String, GarchState>> = Mutex::new(HashMap::new());
}

const DEFAULT_GARCH_KEY: &str = "__global__";

/// Initialize or reset a GARCH state for a given key.
pub fn garch_init(key: &str, alpha: f64, beta: f64, omega: f64) {
    if !alpha.is_finite() || !beta.is_finite() || !omega.is_finite() {
        return;
    }
    if alpha < 0.0 || beta < 0.0 || omega <= 0.0 {
        return;
    }
    if alpha + beta >= 1.0 {
        return; // non-stationary
    }
    let mut states = GARCH_STATES.lock().unwrap();
    states.insert(key.to_string(), GarchState::new(alpha, beta, omega));
}

/// Update the GARCH model with a new return observation.
/// Returns the current (updated) volatility (sigma, not sigma^2).
pub fn garch_update(key: &str, return_val: f64) -> f64 {
    let mut states = GARCH_STATES.lock().unwrap();
    let state = states.entry(key.to_string()).or_insert_with(|| {
        GarchState::new(0.05, 0.90, 1e-6)
    });

    if !return_val.is_finite() {
        return state.last_variance.sqrt();
    }

    if !state.initialized {
        state.last_return = return_val;
        state.initialized = true;
        return state.last_variance.sqrt();
    }

    let epsilon_sq = state.last_return * state.last_return;
    let new_variance = state.omega + state.alpha * epsilon_sq + state.beta * state.last_variance;

    state.last_variance = new_variance.max(state.omega);
    state.last_return = return_val;

    state.last_variance.sqrt()
}

/// N-step ahead GARCH forecast. Returns the predicted volatility at horizon H.
pub fn garch_forecast(key: &str, steps: u32) -> f64 {
    let states = GARCH_STATES.lock().unwrap();
    let state = match states.get(key) {
        Some(s) => s,
        None => return 0.0,
    };

    if steps == 0 {
        return state.last_variance.sqrt();
    }

    let persistence = state.alpha + state.beta;
    let long_run_var = state.omega / (1.0 - persistence).max(1e-10);
    let mut forecast_var = state.last_variance;

    for _ in 0..steps {
        forecast_var = long_run_var + persistence * (forecast_var - long_run_var);
    }

    forecast_var.sqrt().max(0.0)
}

/// Free the GARCH state for a given key.
pub fn garch_free(key: &str) {
    let mut states = GARCH_STATES.lock().unwrap();
    states.remove(key);
}

/// Convenience: get the current volatility as a multiplier that can scale price floors.
/// Returns a value >= 1.0 — in calm markets near 1.0, in volatile markets higher.
pub fn garch_volatility_multiplier(key: &str) -> f64 {
    let vol = garch_forecast(key, 1);
    if vol <= 0.0 {
        return 1.0;
    }
    // scale: typical daily vol ~0.01-0.05 in calm, >0.10 in turbulent
    // multiplier = 1 + min(vol * 10, 1.0) — capping at 2x
    (1.0 + (vol * 10.0).min(1.0)).max(1.0)
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_garch_init_and_update() {
        garch_init("test", 0.05, 0.90, 1e-6);
        let vol = garch_update("test", 0.01);
        assert!(vol > 0.0 && vol.is_finite(), "initial volatility should be positive");

        let vol2 = garch_update("test", 0.01);
        assert!(vol2.is_finite(), "second update should work");
    }

    #[test]
    fn test_garch_bad_params_rejected() {
        // non-stationary params should be ignored
        garch_init("bad", 0.5, 0.6, 1e-6); // alpha+beta = 1.1 > 1.0
        let vol = garch_forecast("bad", 0);
        assert!((vol - 0.0).abs() < 1e-10, "non-stationary init should be rejected");
    }

    #[test]
    fn test_garch_volatility_clustering() {
        garch_init("cluster", 0.10, 0.85, 1e-7);

        // calm period
        let mut vol_before = garch_update("cluster", 0.001);
        for _ in 0..20 {
            vol_before = garch_update("cluster", 0.001);
        }

        // turbulent period
        garch_update("cluster", 0.10); // large return
        let vol_after_shock = garch_update("cluster", 0.05);

        assert!(vol_after_shock > vol_before,
            "volatility should increase after a large return (clustering)");
    }

    #[test]
    fn test_garch_forecast_converges_to_long_run() {
        garch_init("forecast", 0.05, 0.90, 1e-6);

        // seed with a few updates
        for _ in 0..5 {
            garch_update("forecast", 0.01);
        }

        let short_term = garch_forecast("forecast", 1);
        let long_term = garch_forecast("forecast", 1000);
        assert!(short_term.is_finite() && long_term.is_finite());

        // long-term forecast should approach unconditional variance
        let persistence = 0.05 + 0.90;
        let expected_lr = (1e-6 / (1.0 - persistence)).sqrt();
        assert!((long_term - expected_lr).abs() < 0.01);
    }

    #[test]
    fn test_garch_multiplier_range() {
        garch_init("mult", 0.05, 0.90, 1e-7);

        // calm
        for _ in 0..10 {
            garch_update("mult", 0.001);
        }
        let m_calm = garch_volatility_multiplier("mult");
        assert!(m_calm >= 1.0, "multiplier should be at least 1.0");

        // shock
        garch_update("mult", 0.20);
        let m_shock = garch_volatility_multiplier("mult");
        assert!(m_shock >= m_calm, "multiplier should increase after shock");
        assert!(m_shock <= 2.0, "multiplier should be capped at 2.0");
    }

    #[test]
    fn test_garch_nan_input_handled() {
        garch_init("nan", 0.05, 0.90, 1e-6);
        garch_update("nan", 0.01);
        let vol = garch_update("nan", f64::NAN);
        assert!(vol.is_finite(), "NaN input should not produce NaN volatility");
    }

    #[test]
    fn test_garch_free() {
        garch_init("free_me", 0.05, 0.90, 1e-6);
        garch_update("free_me", 0.01);
        garch_free("free_me");
        let vol = garch_forecast("free_me", 0);
        assert!((vol - 0.0).abs() < 1e-10, "freed state should return 0");
    }
}
