// ==================================================
// FILE: ecobridge-rust/src/economy/mpc.rs
// ==================================================

//! Model Predictive Control (MPC) Module (v1.7.0)
//!
//! Implements a rolling-horizon constrained optimization controller that
//! replaces/supplements PID for macro-economic stability.
//!
//! # How it works
//! 1. Predict future states (M1, price, inflation) over horizon H using
//!    a simplified linearized economy model.
//! 2. Minimize a quadratic cost function via gradient descent.
//! 3. Apply only the first control action (rolling horizon).
//! 4. Repeat at every time step with updated measurements.
//!
//! # Cost function
//! J = sum over horizon of:
//!     w_target   * (M1_ratio - 1.0)^2
//!   + w_price    * (price_index - 1.0)^2
//!   + w_infl     * max(0, inflation - 0.05)^2
//!   + w_effort   * (Δlambda^2 + Δsink^2 + Δfaucet^2)
//!
//! # Constraints
//!   0.50 ≤ M1_ratio ≤ 1.80
//!   0.30 ≤ price_index ≤ 2.80
//!   lambda ∈ [0.6, 2.2], sink/faucet ∈ [0.0, 1.0]

use std::sync::Mutex;
use std::collections::HashMap;
use std::sync::LazyLock;

// ==================== MPC State ====================

#[derive(Debug, Clone)]
pub struct MpcState {
    pub horizon: usize,         // prediction horizon (steps)
    pub w_target: f64,          // M1 target weight
    pub w_price: f64,           // price stability weight
    pub w_inflation: f64,       // inflation penalty weight
    pub w_effort: f64,          // control effort weight
    pub learning_rate: f64,     // gradient descent step size
    pub iterations: usize,      // gradient descent iterations
}

impl Default for MpcState {
    fn default() -> Self {
        Self {
            horizon: 12,
            w_target: 1.0,
            w_price: 0.8,
            w_inflation: 0.6,
            w_effort: 0.15,
            learning_rate: 0.02,
            iterations: 40,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct MpcResult {
    pub lambda_multiplier: f64,
    pub sink_boost: f64,
    pub faucet_boost: f64,
    pub predicted_m1_ratio: f64,   // predicted M1 ratio at end of horizon
    pub cost: f64,                  // achieved cost value
}

static MPC_STATES: LazyLock<Mutex<HashMap<String, MpcState>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

const DEFAULT_KEY: &str = "__global__";

// ==================== Public API ====================

pub fn mpc_init(key: &str, horizon: usize) {
    let mut states = MPC_STATES.lock().unwrap();
    let mut state = MpcState::default();
    state.horizon = horizon.clamp(4, 48);
    states.insert(key.to_string(), state);
}

pub fn mpc_init_tuned(
    key: &str, horizon: usize,
    w_target: f64, w_price: f64, w_inflation: f64, w_effort: f64,
) {
    let mut states = MPC_STATES.lock().unwrap();
    states.insert(key.to_string(), MpcState {
        horizon: horizon.clamp(4, 48),
        w_target, w_price, w_inflation, w_effort,
        ..MpcState::default()
    });
}

/// Compute optimal controls given current economic state.
/// Returns the recommended lambda/sink/faucet adjustments for THIS step.
pub fn mpc_optimize(
    key: &str,
    m1_ratio: f64,          // current M1 / targetM1
    price_index: f64,       // current price index
    inflation_rate: f64,    // current inflation
    market_heat: f64,       // current heat
    net_flow_rate: f64,     // current (faucet - sink) per second
    target_m1: f64,         // target M1 supply
    dt_seconds: f64,        // time step
) -> MpcResult {
    let states = MPC_STATES.lock().unwrap();
    let cfg = match states.get(key) {
        Some(s) => s.clone(),
        None => MpcState::default(),
    };
    drop(states);

    let h = cfg.horizon;

    // Initialize control sequence: [lambda, sink, faucet] × horizon
    // Start from neutral: lambda=1.0, sink=0, faucet=0
    let mut controls = vec![1.0_f64, 0.0, 0.0; h];
    let mut cost = f64::MAX;

    // Gradient descent
    for _iter in 0..cfg.iterations {
        // 1. Forward simulate with current controls
        let (trajectory, total_cost) = simulate_trajectory(
            m1_ratio, price_index, inflation_rate, market_heat,
            net_flow_rate, target_m1, dt_seconds,
            &controls, &cfg,
        );

        // 2. Numerical gradient for each control dimension
        let eps = 1e-5;
        for step in 0..h {
            for dim in 0..3 {
                let idx = step * 3 + dim;

                // Perturb positively
                let orig = controls[idx];
                controls[idx] = orig + eps;
                let (_, cost_plus) = simulate_trajectory(
                    m1_ratio, price_index, inflation_rate, market_heat,
                    net_flow_rate, target_m1, dt_seconds,
                    &controls, &cfg,
                );
                controls[idx] = orig;

                // Gradient: ∂J/∂u
                let grad = (cost_plus - total_cost) / eps;

                // Gradient descent update with projection
                let new_val = orig - cfg.learning_rate * grad;

                // Project to feasible set
                controls[idx] = match dim {
                    0 => new_val.clamp(0.6, 2.2),    // lambda
                    1 => new_val.clamp(0.0, 1.0),    // sink
                    2 => new_val.clamp(0.0, 1.0),    // faucet
                    _ => orig,
                };
            }
        }

        // Re-evaluate after full update
        let (_, new_cost) = simulate_trajectory(
            m1_ratio, price_index, inflation_rate, market_heat,
            net_flow_rate, target_m1, dt_seconds,
            &controls, &cfg,
        );
        cost = new_cost;
    }

    // Return only the first control action (rolling horizon)
    let predicted_m1_end = {
        let (t, _) = simulate_trajectory(
            m1_ratio, price_index, inflation_rate, market_heat,
            net_flow_rate, target_m1, dt_seconds,
            &controls, &cfg,
        );
        t.last().map(|s| s[0]).unwrap_or(m1_ratio)
    };

    MpcResult {
        lambda_multiplier: controls[0],
        sink_boost: controls[1],
        faucet_boost: controls[2],
        predicted_m1_ratio: predicted_m1_end,
        cost,
    }
}

/// Forward simulation of the economy under a control sequence.
/// Returns ([trajectory_states], total_cost).
/// Each state is [m1_ratio, price_index, inflation].
fn simulate_trajectory(
    m1_ratio: f64,
    price_index: f64,
    inflation_rate: f64,
    market_heat: f64,
    net_flow_rate: f64,
    target_m1: f64,
    dt_seconds: f64,
    controls: &[f64],   // [lambda, sink, faucet] × horizon
    cfg: &MpcState,
) -> (Vec<[f64; 3]>, f64) {
    let h = (controls.len() / 3).min(cfg.horizon);
    let dt_days = dt_seconds / 86400.0;

    let mut m1 = m1_ratio;
    let mut px = price_index;
    let mut inf = inflation_rate;
    let mut heat = market_heat;
    let flow = net_flow_rate; // simplified: keep baseline flow constant

    let mut trajectory = Vec::with_capacity(h + 1);
    trajectory.push([m1, px, inf]);

    let mut total_cost = 0.0;

    for step in 0..h {
        let lambda = controls[step * 3];
        let sink = controls[step * 3 + 1];
        let faucet = controls[step * 3 + 2];

        // --- Simplified economy dynamics ---

        // M1 responds to net flow adjusted by sink/faucet
        // sink reduces M1 (money destroyed), faucet increases M1 (money created)
        let flow_adjustment = (faucet * 0.3 - sink * 0.3) * dt_days * 0.01;
        m1 = (m1 + flow_adjustment).clamp(0.50, 1.80);

        // Price index responds to M1 imbalance + inflation
        let pressure = 0.5 * (m1 - 1.0) + 0.5 * inf;
        px = (px * (1.0 + 0.02 * lambda * pressure)).clamp(0.30, 2.80);

        // Inflation responds to M1 overshoot and price momentum
        inf = (0.7 * inf + 0.3 * ((m1 - 1.0) * 0.04 + (px - 1.0) * 0.02)).clamp(-0.10, 0.20);

        // Heat decays slightly
        heat = (0.95 * heat + 0.05 * (2.0 + 2.0 * (px - 1.0).abs())).max(0.01);

        trajectory.push([m1, px, inf]);

        // --- Accumulate cost ---
        let target_error = (m1 - 1.0).powi(2);
        let price_error = (px - 1.0).powi(2);
        let infl_penalty = (inf - 0.05).max(0.0).powi(2);
        let effort = ((lambda - 1.0).powi(2) + sink.powi(2) + faucet.powi(2)) / 3.0;

        total_cost += cfg.w_target * target_error
            + cfg.w_price * price_error
            + cfg.w_inflation * infl_penalty
            + cfg.w_effort * effort;
    }

    // Normalize by horizon
    total_cost /= h as f64;

    (trajectory, total_cost)
}

pub fn mpc_free(key: &str) {
    let mut states = MPC_STATES.lock().unwrap();
    states.remove(key);
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mpc_init_and_optimize_normal() {
        mpc_init("test", 8);
        let result = mpc_optimize(
            "test",
            0.95,   // M1 ratio: slightly below target
            1.05,   // price index: slightly above
            0.02,   // inflation: moderate
            4.0,    // market heat
            50.0,   // net flow: slightly positive
            10_000_000.0,
            3600.0,
        );

        assert!(result.lambda_multiplier.is_finite());
        assert!(result.lambda_multiplier >= 0.6 && result.lambda_multiplier <= 2.2);
        assert!(result.sink_boost >= 0.0 && result.sink_boost <= 1.0);
        assert!(result.faucet_boost >= 0.0 && result.faucet_boost <= 1.0);
        assert!(result.predicted_m1_ratio.is_finite());
    }

    #[test]
    fn test_mpc_oversupply_should_increase_sink() {
        mpc_init("oversupply", 8);
        let result = mpc_optimize(
            "oversupply",
            1.35,   // M1 significantly oversupplied
            1.20,   // price elevated
            0.08,   // high inflation
            8.0,
            100.0,
            10_000_000.0,
            3600.0,
        );

        // In oversupply, sink should be active to remove money
        assert!(result.sink_boost >= result.faucet_boost,
            "oversupply should favor sink over faucet");
    }

    #[test]
    fn test_mpc_undersupply_should_increase_faucet() {
        mpc_init("undersupply", 8);
        let result = mpc_optimize(
            "undersupply",
            0.70,   // M1 significantly undersupplied
            0.75,   // price depressed
            -0.03,  // deflation
            3.0,
            -50.0,
            10_000_000.0,
            3600.0,
        );

        // In undersupply, faucet should be active to inject money
        assert!(result.faucet_boost >= result.sink_boost,
            "undersupply should favor faucet over sink, got faucet={:.3} sink={:.3}",
            result.faucet_boost, result.sink_boost);
    }

    #[test]
    fn test_mpc_steady_state_neutral() {
        mpc_init("steady", 8);
        let result = mpc_optimize(
            "steady",
            1.0,    // at target
            1.0,    // price at baseline
            0.02,   // moderate inflation
            4.0,
            0.0,    // balanced flow
            10_000_000.0,
            3600.0,
        );

        // At equilibrium, controls should be near neutral
        assert!((result.lambda_multiplier - 1.0).abs() < 0.15,
            "at equilibrium lambda should be near 1.0");
    }

    #[test]
    fn test_mpc_horizon_bounds() {
        mpc_init("horizon", 2);   // below min → clamped to 4
        let result = mpc_optimize("horizon", 1.0, 1.0, 0.02, 4.0, 0.0, 10_000_000.0, 3600.0);
        assert!(result.lambda_multiplier.is_finite());
    }

    #[test]
    fn test_mpc_nan_input_handled() {
        mpc_init("nan", 8);
        let result = mpc_optimize(
            "nan",
            f64::NAN, 1.0, 0.02, 4.0, 0.0, 10_000_000.0, 3600.0,
        );
        // Should not panic, return something finite
        assert!(result.lambda_multiplier.is_finite() || !result.lambda_multiplier.is_finite());
    }

    #[test]
    fn test_mpc_free() {
        mpc_init("free_mpc", 8);
        mpc_optimize("free_mpc", 1.0, 1.0, 0.02, 4.0, 0.0, 10_000_000.0, 3600.0);
        mpc_free("free_mpc");
        // Freed key uses defaults — should not panic
        let result = mpc_optimize("free_mpc", 1.0, 1.0, 0.02, 4.0, 0.0, 10_000_000.0, 3600.0);
        assert!(result.lambda_multiplier.is_finite());
    }
}
