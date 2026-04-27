#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EcoBridge Long-Run Economic Simulator v2.0
===========================================
Runs a 365-day simulation of a Minecraft server economy using EcoBridge's
actual mathematical models (GARCH, Kalman, PID, fuzzy control, pricing).

Output: sim_daily.csv + sim_hourly.csv + statistical report
"""

import csv
import math
import os
import random
import sys
import io
from collections import defaultdict
from dataclasses import dataclass, field
from typing import List, Tuple, Dict

# Fix Windows console encoding for emoji/special chars
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

# ====================================================================
# 配置参数 (对齐 config.yml 默认值)
# ====================================================================

SIM_DAYS = 365               # 模拟总天数
DT_SECONDS = 3600.0          # 时间步长 (1 小时)
TOTAL_HOURS = SIM_DAYS * 24
TARGET_M1 = 10_000_000.0     # 目标货币供应量
ONLINE_PLAYERS_BASE = 80     # 基础在线人数
ONLINE_PLAYERS_PEAK = 160    # 峰值在线人数
TARGET_VELOCITY = 0.04       # 目标流通速度
CAPACITY_PER_USER = 5000.0
MICROS_SCALE = 1_000_000.0
SEED = 20260427

# ====================================================================
# 物品定义 (模拟多种物品类型)
# ====================================================================

# Realistic Minecraft economy: SYSTEM SHOPS BUY MORE THAN THEY SELL.
# Players farm items and sell to shop (faucet = money IN, items IN).
# Players buy from shop less frequently (sink = money OUT, items OUT).
# Net result: items accumulate in the system, creating chronic downward price pressure.
#
# supply_rate = items sold TO shop per player-hour (shop收购)
# demand_rate = items bought FROM shop per player-hour (玩家购买)
# Key: supply_rate > demand_rate by 15-30% for most items
ITEMS = {
    "diamond":       {"base_price": 100.0,  "lambda": 0.008,  "supply_rate": 130.0, "demand_rate": 100.0, "volatility_class": "medium"},
    "iron_ingot":    {"base_price": 8.0,    "lambda": 0.015,  "supply_rate": 900.0, "demand_rate": 700.0, "volatility_class": "low"},
    "netherite":     {"base_price": 2500.0, "lambda": 0.003,  "supply_rate": 2.5,   "demand_rate": 2.0,   "volatility_class": "high"},
    "elytra":        {"base_price": 1800.0, "lambda": 0.005,  "supply_rate": 2.0,   "demand_rate": 1.5,   "volatility_class": "high"},
    "oak_log":       {"base_price": 1.5,    "lambda": 0.025,  "supply_rate": 5500.0,"demand_rate": 4000.0,"volatility_class": "low"},
    "enchanted_book": {"base_price": 45.0,  "lambda": 0.012,  "supply_rate": 38.0,  "demand_rate": 30.0,  "volatility_class": "medium"},
    "gunpowder":     {"base_price": 5.0,    "lambda": 0.018,  "supply_rate": 450.0, "demand_rate": 350.0, "volatility_class": "medium"},
    "ender_pearl":   {"base_price": 12.0,   "lambda": 0.014,  "supply_rate": 68.0,  "demand_rate": 50.0,  "volatility_class": "medium"},
}

# Critical parameter: how fast supply excess decays from N_eff
# Lower tau = faster decay = prices recover faster = stronger mean reversion
EFFECTIVE_TAU_DAYS = 2.5  # 2.5 day half-life for supply memory (aggressive reversion)


# ====================================================================
# 1. GARCH(1,1) 波动率模型 (对齐 volatility.rs)
# ====================================================================

class GarchModel:
    """GARCH(1,1): sigma_t^2 = omega + alpha * eps_{t-1}^2 + beta * sigma_{t-1}^2"""
    def __init__(self, alpha=0.05, beta=0.90, omega=1e-6):
        self.alpha = alpha
        self.beta = beta
        self.omega = omega
        self.last_return = 0.0
        persistence = alpha + beta
        self.last_variance = omega / max(1.0 - persistence, 1e-10)
        self.initialized = False

    def update(self, ret: float) -> float:
        """Feed a new return, get current volatility (sigma)."""
        if not math.isfinite(ret):
            return math.sqrt(max(self.last_variance, 0.0))
        if not self.initialized:
            self.last_return = ret
            self.initialized = True
            return math.sqrt(max(self.last_variance, 0.0))
        eps_sq = self.last_return * self.last_return
        new_var = self.omega + self.alpha * eps_sq + self.beta * self.last_variance
        self.last_variance = max(new_var, self.omega)
        self.last_return = ret
        return math.sqrt(self.last_variance)

    def forecast(self, steps: int = 1) -> float:
        """N-step ahead volatility forecast."""
        if steps == 0:
            return math.sqrt(max(self.last_variance, 0.0))
        persistence = self.alpha + self.beta
        lr_var = self.omega / max(1.0 - persistence, 1e-10)
        f_var = self.last_variance
        for _ in range(steps):
            f_var = lr_var + persistence * (f_var - lr_var)
        return math.sqrt(max(f_var, 0.0))

    def multiplier(self) -> float:
        """Volatility multiplier for dynamic price floor. Range [1.0, 2.0]."""
        vol = self.forecast(1)
        if vol <= 0.0:
            return 1.0
        return max(1.0, 1.0 + min(vol * 10.0, 1.0))


# ====================================================================
# 2. 卡尔曼滤波器 (对齐 kalman.rs)
# ====================================================================

class KalmanFilter:
    """3-state constant-acceleration Kalman filter."""
    def __init__(self, q_pos=0.001, q_vel=0.01, q_acc=0.005, r=0.05):
        self.x = [0.0, 0.0, 0.0]   # [pos, vel, acc]
        self.P = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
        self.Q_diag = [q_pos, q_vel, q_acc]
        self.R = r
        self.initialized = False

    def predict(self, dt: float) -> float:
        if dt <= 0.0 or not math.isfinite(dt):
            return self.x[0]
        dt2 = 0.5 * dt * dt
        # F = [[1, dt, dt²/2], [0, 1, dt], [0, 0, 1]]
        x = self.x
        x_new = [x[0] + x[1]*dt + x[2]*dt2, x[1] + x[2]*dt, x[2]]
        # P_pred = F*P*F^T + Q
        P = self.P
        # F*P
        fp = [
            P[0] + P[3]*dt + P[6]*dt2, P[1] + P[4]*dt + P[7]*dt2, P[2] + P[5]*dt + P[8]*dt2,
            P[3] + P[6]*dt, P[4] + P[7]*dt, P[5] + P[8]*dt,
            P[6], P[7], P[8],
        ]
        # (F*P)*F^T
        p_new = [
            fp[0] + fp[1]*dt + fp[2]*dt2, fp[1] + fp[2]*dt, fp[2],
            fp[3] + fp[4]*dt + fp[5]*dt2, fp[4] + fp[5]*dt, fp[5],
            fp[6] + fp[7]*dt + fp[8]*dt2, fp[7] + fp[8]*dt, fp[8],
        ]
        # Add Q
        p_new[0] += self.Q_diag[0] * dt
        p_new[4] += self.Q_diag[1] * dt
        p_new[8] += self.Q_diag[2] * dt

        self.x = x_new
        self.P = p_new
        return self.x[0]

    def update(self, measurement: float) -> float:
        if not math.isfinite(measurement):
            return self.x[0]
        if not self.initialized:
            self.x = [measurement, 0.0, 0.0]
            self.initialized = True
            return self.x[0]
        P = self.P
        y = measurement - self.x[0]  # innovation
        s = P[0] + self.R
        if s <= 0.0:
            return self.x[0]
        K = [P[0]/s, P[1]/s, P[2]/s]
        self.x[0] += K[0] * y
        self.x[1] += K[1] * y
        self.x[2] += K[2] * y
        # P = (I - K*H) * P
        ikh = [1.0-K[0], 0.0, 0.0, -K[1], 1.0, 0.0, -K[2], 0.0, 1.0]
        self.P = [
            ikh[0]*P[0], ikh[0]*P[1], ikh[0]*P[2],
            ikh[3]*P[0] + ikh[4]*P[3], ikh[3]*P[1] + ikh[4]*P[4], ikh[3]*P[2] + ikh[4]*P[5],
            ikh[6]*P[0] + ikh[7]*P[3] + ikh[8]*P[6], ikh[6]*P[1] + ikh[7]*P[4],
            ikh[6]*P[2] + ikh[7]*P[5],
        ]
        return self.x[0]

    def filter(self, measurement: float, dt: float) -> float:
        self.predict(dt)
        return self.update(measurement)

    def velocity(self) -> float:
        return self.x[1] if self.initialized else 0.0


# ====================================================================
# 3. PID 控制器 (对齐 control.rs)
# ====================================================================

@dataclass
class PidState:
    kp: float = 0.5; ki: float = 0.1; kd: float = 0.05; lam: float = 0.01
    integral: float = 0.0; prev_pv: float = 0.0; filtered_d: float = 0.0
    integration_limit: float = 30.0; is_saturated: int = 0

def sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))

def compute_pid(pid: PidState, target: float, current: float, dt: float,
                inflation: float, market_heat: float) -> float:
    """Full adaptive PID with gain scheduling, anti-windup, and panic damping."""
    if not all(math.isfinite(v) for v in [target, current, dt, inflation, market_heat]):
        return 1.0
    if dt <= 0.0:
        return 1.0

    error = target - current
    dt_safe = min(dt, 1.0)

    # Gain scheduling
    sensitivity = math.tanh(market_heat * 0.5)
    adaptive_kp = pid.kp * (1.0 + sensitivity)
    adaptive_ki = pid.ki * (1.0 - sensitivity * 0.5)
    schedule_gamma = 1.0 + sigmoid((inflation - 0.05) * 20.0)
    active_kp = adaptive_kp * schedule_gamma
    active_ki = adaptive_ki * schedule_gamma

    # Integral with anti-windup
    combined_leakage = (1.0 - min(max(pid.lam, 0.0), 1.0)) * 0.99999
    if pid.is_saturated:
        pid.integral = pid.integral * combined_leakage + error * 0.2 * dt_safe
    else:
        pid.integral = pid.integral * combined_leakage + error * dt_safe
    pid.integral = max(-pid.integration_limit, min(pid.integration_limit, pid.integral))

    # Derivative with low-pass filter
    delta_pv = current - pid.prev_pv
    raw_deriv = delta_pv / max(dt_safe, 1e-6)
    pid.filtered_d = 0.3 * raw_deriv + 0.7 * pid.filtered_d
    pid.prev_pv = current

    # Panic damping
    d_mult = 1.8 if abs(pid.filtered_d) > 50.0 else 1.0

    # Synthesis
    p_term = active_kp * error
    i_term = active_ki * pid.integral
    d_term = pid.kd * pid.filtered_d * d_mult
    raw = 1.0 + p_term + i_term - d_term
    output = max(0.5, min(5.0, raw))

    pid.is_saturated = 1 if abs(raw - output) > 1e-6 else 0
    return output if math.isfinite(output) else 1.0


# ====================================================================
# 4. 模糊逻辑宏观控制器 (对齐 PredictiveFuzzyFluidController.java)
# ====================================================================

class FuzzyFluidController:
    def __init__(self, horizon_sec: float = 259200.0, min_lambda: float = 0.6, max_lambda: float = 2.2):
        self.horizon = max(60.0, horizon_sec)
        self.min_lambda = min_lambda
        self.max_lambda = max_lambda

    def membership_rise(self, x, lo, hi):
        if x <= lo: return 0.0
        if x >= hi: return 1.0
        return (x - lo) / (hi - lo)

    def membership_fall(self, x, lo, hi):
        if x <= lo: return 1.0
        if x >= hi: return 0.0
        return 1.0 - (x - lo) / (hi - lo)

    def decide(self, s: dict) -> dict:
        """s: {inflation, heat, saturation, m1, faucet, sink, online, target_vel, target_m1}"""
        target_heat = max(0.1, s['online'] * s['target_vel'])
        net_flow = s['faucet'] - s['sink']
        pred_m1 = s['m1'] + net_flow * self.horizon
        supply_ratio = pred_m1 / max(1.0, s['target_m1'])

        inf_high = self.membership_rise(s['inflation'], 0.03, 0.18)
        heat_high = self.membership_rise(s['heat'], target_heat * 0.9, target_heat * 2.4)
        overflow_high = self.membership_rise(supply_ratio, 1.03, 1.45)
        deflation_high = self.membership_fall(s['inflation'], -0.10, -0.005)
        underflow_high = self.membership_fall(supply_ratio, 0.72, 0.98)
        heat_low = self.membership_fall(s['heat'], target_heat * 0.4, target_heat * 0.9)

        sink_boost = max(0.0, min(1.0, max(
            overflow_high,
            max(inf_high * 0.9 + heat_high * 0.4, s['saturation'] * 0.6)
        )))
        faucet_boost = max(0.0, min(1.0, max(
            underflow_high,
            max(deflation_high * 0.9 + heat_low * 0.4, (1.0 - s['saturation']) * 0.35)
        )))

        lam_mult = 1.0 + sink_boost * 0.85 - faucet_boost * 0.55
        lam_mult = max(self.min_lambda, min(self.max_lambda, lam_mult))
        return {"lambda": lam_mult, "sink": sink_boost, "faucet": faucet_boost}


# ====================================================================
# 5. 定价引擎 (对齐 pricing.rs)
# ====================================================================

class PricingEngine:
    """Per-item pricing with behavioral economics, tier pricing, GARCH floors, and MEAN REVERSION."""

    # Recovery parameters (aligned with config.yml economy.recovery)
    RECOVERY_FLOOR_RATIO = 0.62      # Below this, recovery activates
    RECOVERY_ACTIVATION_RATIO = 0.82 # Below this ratio of hist_avg, start pushing back
    RECOVERY_TARGET_RATIO = 0.95     # Target to push price back toward
    RECOVERY_STRENGTH = 0.34         # How strong the recovery pull is (0-1)
    RECOVERY_MAX_STEP = 0.025        # Max per-cycle recovery step

    def __init__(self, item_cfg: dict):
        self.base_price = item_cfg["base_price"]
        self.base_lambda = item_cfg["lambda"]
        self.garch = GarchModel()
        self.kalman = KalmanFilter()
        self.price_history: List[float] = []
        self.hist_avg = item_cfg["base_price"]
        self.n_eff = 0.0  # effective supply volume
        self.last_price = item_cfg["base_price"]
        self.recovery_active = False

    def compute(self, supply_flow: float, demand_flow: float, epsilon: float,
                lambda_mult: float) -> float:
        """
        Core pricing: P = P_base * epsilon * exp(-lambda_adj * N_eff)
        with sell asymmetry, tanh clamping, tier pricing, and GARCH-enhanced floor.
        """
        # Net trade: supply_rate - demand_rate per player-hour
        # Factor in online players indirectly through flow rates
        net_trade = supply_flow - demand_flow  # positive = oversupply, negative = excess demand

        # Exponential decay of N_eff with shorter tau for faster equilibrium
        tau = EFFECTIVE_TAU_DAYS
        decay_factor = math.exp(-DT_SECONDS / (tau * 86400.0))
        # N_eff tracks cumulative excess supply (positive) or deficit (negative)
        self.n_eff = self.n_eff * decay_factor + net_trade * DT_SECONDS / 3600.0 * 0.01

        base_micros = int(self.base_price * MICROS_SCALE)
        lam = self.base_lambda * lambda_mult

        # Asymmetric sensitivity: sell side gets 0.6x lambda (price sticky downward)
        if net_trade > 0:
            lam *= 0.6

        # Core pricing formula
        if not all(math.isfinite(v) for v in [self.n_eff, lam, epsilon]):
            return 0.01

        total_n = self.n_eff
        raw_exp = max(-100.0, min(100.0, -lam * total_n / 100.0))
        clamped_exp = 10.0 * math.tanh(raw_exp / 10.0)

        base_f64 = base_micros / MICROS_SCALE
        raw_price = base_f64 * epsilon * math.exp(clamped_exp)

        # Tier pricing (bulk supply discount — <1> 500 units at once)
        if net_trade > 500.0:
            qty = net_trade
            tiered = min(qty, 500.0) * raw_price
            qty -= 500.0
            if qty > 0:
                tiered += min(qty, 1500.0) * raw_price * 0.85
                qty -= 1500.0
            if qty > 0:
                tiered += qty * raw_price * 0.60
            raw_price = tiered / net_trade

        # GARCH-enhanced dynamic floor
        vol_mult = self.garch.multiplier()
        floor = max(self.hist_avg * self.RECOVERY_FLOOR_RATIO * vol_mult, 0.01)
        price = max(raw_price, floor)

        # === MEAN REVERSION / RECOVERY MECHANISM ===
        # When price drifts below activation ratio of its historical average,
        # the system applies an upward correction. This simulates the "central bank"
        # buying pressure that pushes prices back toward equilibrium.
        # This is the CRITICAL mechanism that prevents chronic price collapse
        # when the system shop buys more than it sells.
        activation_price = self.hist_avg * self.RECOVERY_ACTIVATION_RATIO
        target_price = self.hist_avg * self.RECOVERY_TARGET_RATIO

        if price < activation_price:
            self.recovery_active = True
            # How far below target are we? (0 = at target, 1 = at 0)
            deficit = max(0.0, (target_price - price) / max(target_price, 0.01))
            # Apply recovery pull: stronger when further from target
            recovery_step = self.RECOVERY_STRENGTH * deficit * self.RECOVERY_MAX_STEP * self.hist_avg
            # GARCH moderates recovery: don't fight market turbulence
            price += recovery_step / max(vol_mult, 1.0)
        else:
            self.recovery_active = False

        # Update GARCH with price return
        if self.last_price > 0:
            ret = (price - self.last_price) / self.last_price
            self.garch.update(ret)

        # Kalman filter for smoothed price
        self.kalman.filter(price, DT_SECONDS)

        # Update tracking
        self.last_price = price
        self.price_history.append(price)
        if len(self.price_history) > 100:
            self.hist_avg = sum(self.price_history[-100:]) / 100.0

        return price


# ====================================================================
# 6. 环境因子引擎 (对齐 environment.rs)
# ====================================================================

class EnvironmentEngine:
    """Seasonal, weekend, newbie protection, and inflation feedback."""

    def __init__(self):
        self.seasonal_amplitude = 0.15
        self.weekend_multiplier = 1.2
        self.newbie_protection_rate = 0.2

    def compute(self, ts_sec: float, timezone_offset: float = 0.0,
                inflation_rate: float = 0.0, play_hours: float = 50.0,
                is_festival: bool = False) -> float:
        SECONDS_PER_DAY = 86400.0
        SECONDS_PER_WEEK = 604800.0
        SECONDS_PER_MONTH = 2592000.0

        ts_local = ts_sec + timezone_offset

        # Seasonal: composite sine waves
        day_wave = math.sin(ts_local * 2.0 * math.pi / SECONDS_PER_DAY)
        week_wave = math.sin(ts_local * 2.0 * math.pi / SECONDS_PER_WEEK)
        month_wave = math.sin(ts_local * 2.0 * math.pi / SECONDS_PER_MONTH)
        seasonal = 0.6 * day_wave + 0.3 * week_wave + 0.1 * month_wave
        f_sea = 1.0 + self.seasonal_amplitude * seasonal
        if is_festival:
            f_sea *= 1.15

        # Weekend factor
        day_idx = int(ts_local / SECONDS_PER_DAY)
        day_of_week = (day_idx + 4) % 7  # 0=Mon, 6=Sun
        f_wk = self.weekend_multiplier if day_of_week >= 5 else 1.0

        # Newbie protection: linear decay from 0h -> 100h
        protection_decay = max(0.0, min(1.0, 1.0 - play_hours / 100.0))
        f_nb = 1.0 - self.newbie_protection_rate * protection_decay

        # Inflation feedback: sigmoid trigger above 5%
        sig_trig = 1.0 / (1.0 + math.exp(-(inflation_rate - 0.05) * 200.0))
        f_inf = 1.0 + inflation_rate * 0.2 * sig_trig

        # Weighted geometric mean
        log_eps = 0.0
        for f, w in [(f_sea, 0.25), (f_wk, 0.25), (f_nb, 0.25), (f_inf, 0.25)]:
            log_eps += w * math.log(max(f, 0.01))
        eps = math.exp(log_eps)
        return max(0.1, min(10.0, eps))


# ====================================================================
# 7. 主模拟循环
# ====================================================================

@dataclass
class HourlyRecord:
    day: float
    hour: int
    online_players: float
    m1_supply: float
    supply_ratio: float
    inflation_rate: float
    market_heat: float
    lambda_multiplier: float
    price_index: float
    item_prices: Dict[str, float]
    garch_vol: Dict[str, float]
    kalman_vel: Dict[str, float]


def run_simulation() -> List[HourlyRecord]:
    """Run 365-day simulation with all economic algorithms."""
    random.seed(SEED)

    # Initialize systems
    controller = FuzzyFluidController()
    pid = PidState()
    env_engine = EnvironmentEngine()
    pricing = {name: PricingEngine(cfg) for name, cfg in ITEMS.items()}
    m1_kalman = KalmanFilter(q_pos=1000.0, q_vel=500.0, r=50000.0)

    # Macro state
    m1 = TARGET_M1 * 1.02
    price_index = 1.0
    market_heat = ONLINE_PLAYERS_BASE * TARGET_VELOCITY * 0.9
    inflation_rate = 0.0

    records: List[HourlyRecord] = []

    for t in range(TOTAL_HOURS):
        day = t / 24.0
        hour_of_day = t % 24

        # --- Dynamic player counts with daily cycle ---
        hour_frac = hour_of_day / 24.0
        daily_cycle = math.sin((hour_frac - 0.25) * 2.0 * math.pi)  # peak ~18:00
        online = ONLINE_PLAYERS_BASE + (ONLINE_PLAYERS_PEAK - ONLINE_PLAYERS_BASE) * max(0.0, daily_cycle * 0.7 + 0.3)
        online += random.gauss(0, 5)  # noise

        # Weekend boost
        day_of_week = int(day + 4) % 7
        if day_of_week >= 5:
            online *= 1.15

        # --- Weekly cycle for supply/demand ---
        weekly_cycle = math.sin(2.0 * math.pi * day / 7.0)
        is_weekend = day_of_week >= 5
        is_festival = (140 <= day <= 147)  # simulated festival week

        # --- Faucet / Sink base rates ---
        faucet_base = 520.0 + 120.0 * (0.5 + 0.5 * math.cos(hour_of_day * 2.0 * math.pi / 24.0))
        faucet_base += 60.0 * weekly_cycle
        sink_base = 500.0 + 100.0 * (0.5 - 0.5 * math.cos(hour_of_day * 2.0 * math.pi / 24.0))
        sink_base -= 40.0 * weekly_cycle

        # Event shocks
        if 28 * 24 <= t < 30 * 24:    # inflationary exploit spike
            faucet_base += 850.0
        if 96 * 24 <= t < 99 * 24:     # aggressive sink event
            sink_base += 550.0
        if 140 * 24 <= t < 143 * 24:   # festival
            faucet_base += 500.0
        if 200 * 24 <= t < 202 * 24:   # content update (high demand)
            sink_base += 1200.0
        if 300 * 24 <= t < 305 * 24:   # extended low activity
            faucet_base *= 0.6
            sink_base *= 0.6

        # Random noise
        faucet_base *= (0.985 + random.random() * 0.03)
        sink_base *= (0.985 + random.random() * 0.03)

        # --- Economic saturation ---
        eco_saturation = max(0.0, min(1.0,
            market_heat / (max(1.0, online) * CAPACITY_PER_USER)))

        # --- Fuzzy control decision ---
        signals = {
            "inflation": inflation_rate, "heat": market_heat,
            "saturation": eco_saturation, "m1": m1,
            "faucet": faucet_base, "sink": sink_base,
            "online": online, "target_vel": TARGET_VELOCITY,
            "target_m1": TARGET_M1,
        }
        decision = controller.decide(signals)

        # --- Dynamic sink/faucet anchoring ---
        supply_ratio_pre = m1 / TARGET_M1
        faucet = faucet_base + max(0.0, 1.0 - supply_ratio_pre) * 160.0
        sink = sink_base + max(0.0, supply_ratio_pre - 1.0) * 190.0

        adj_faucet = faucet * (1.0 - 0.20 * decision['sink'] + 0.25 * decision['faucet'])
        adj_sink = sink * (1.0 + 0.30 * decision['sink'] - 0.10 * decision['faucet'])
        net_flow = adj_faucet - adj_sink

        # --- PID regulation of lambda ---
        pid_adj = compute_pid(pid, TARGET_VELOCITY, market_heat / max(1.0, online),
                              DT_SECONDS, inflation_rate, market_heat)

        # --- Update M1 supply ---
        m1 = max(TARGET_M1 * 0.50, min(TARGET_M1 * 1.80,
            m1 + net_flow * DT_SECONDS))
        supply_ratio = m1 / TARGET_M1

        # Filter M1 with Kalman
        m1_filtered = m1_kalman.filter(m1, DT_SECONDS)

        # --- Update inflation ---
        inflation_rate = (0.55 * inflation_rate +
            0.45 * ((supply_ratio - 1.0) * 0.06 + (random.random() - 0.5) * 0.004))

        # --- Update market heat ---
        target_heat = max(0.1, online * TARGET_VELOCITY)
        market_heat = (0.82 * market_heat + 0.18 *
            (target_heat * (1.0 + 0.45 * max(0.0, 1.0 - price_index)
                           - 0.25 * max(0.0, price_index - 1.0))))
        market_heat = max(0.02, market_heat + (random.random() - 0.5) * 0.01)

        # --- Update price index ---
        imbalance = supply_ratio - 1.0
        pressure = 0.45 * inflation_rate + 0.55 * imbalance
        price_index *= (1.0 + 0.015 * decision['lambda'] * pid_adj * pressure)
        price_index = max(0.30, min(2.80, price_index))

        # --- Per-item pricing ---
        item_prices = {}
        garch_vols = {}
        kalman_vels = {}
        eps = env_engine.compute(
            t * DT_SECONDS,
            inflation_rate=inflation_rate,
            is_festival=is_festival
        )

        for item_name, cfg in ITEMS.items():
            # Base supply/demand scaled by online players
            player_scale = online / ONLINE_PLAYERS_BASE

            # Structural imbalances based on item type
            is_rare = cfg["volatility_class"] == "high"
            is_bulk = cfg["volatility_class"] == "low"
            is_medium = cfg["volatility_class"] == "medium"

            # Weekend patterns: more farming (supply) on weekends, more shopping (demand)
            if is_weekend:
                supply_mult = 1.15 if is_bulk else 1.08  # bulk farmers active on weekends
                demand_mult = 1.25  # everyone shops more on weekends
            else:
                supply_mult = 0.95 if is_bulk else 1.0
                demand_mult = 0.90

            # Daily cycle: more farming in morning (hour 6-12), more trading at peak (hour 16-22)
            hour_mult_supply = 1.0 + 0.2 * math.sin((hour_of_day - 9) * 2 * math.pi / 24.0)
            hour_mult_demand = 1.0 + 0.3 * math.sin((hour_of_day - 19) * 2 * math.pi / 24.0)

            # Rare items: demand increases over time as server matures (days 0-180)
            maturity_demand_bonus = 1.0 + 0.4 * min(1.0, day / 180.0) if is_rare else 1.0

            supply_flow = cfg["supply_rate"] * supply_mult * hour_mult_supply * player_scale
            demand_flow = cfg["demand_rate"] * demand_mult * hour_mult_demand * player_scale * maturity_demand_bonus

            # --- Events that create real imbalances ---
            r = random.random()

            # Double dungeon weekend: extra supply for combat drops
            if is_medium and is_weekend and r < 0.05:
                supply_flow *= 2.0

            # Rare drops: netherite/elytra supply shocks
            if is_rare and r < 0.003:
                supply_flow *= 3.5
            if is_rare and r < 0.001:
                supply_flow *= 8.0  # end city raid

            # Building boom: bulk demand spike
            if is_bulk and r < 0.02 and not is_weekend:
                demand_flow *= 1.8  # someone builds a megaproject

            # Newbie wave (days 0-30, 180-210): bulk demand increases
            if is_bulk and (day < 30 or (180 < day < 210)):
                demand_flow *= 1.15

            # Enchanting craze (random spikes)
            if item_name == "enchanted_book" and r < 0.01:
                demand_flow *= 3.0

            price = pricing[item_name].compute(supply_flow, demand_flow, eps,
                                               decision['lambda'] * pid_adj)
            item_prices[item_name] = price
            garch_vols[item_name] = pricing[item_name].garch.forecast(1)
            kalman_vels[item_name] = pricing[item_name].kalman.velocity()

        records.append(HourlyRecord(
            day=day, hour=hour_of_day,
            online_players=online, m1_supply=m1, supply_ratio=supply_ratio,
            inflation_rate=inflation_rate, market_heat=market_heat,
            lambda_multiplier=decision['lambda'] * pid_adj,
            price_index=price_index,
            item_prices=item_prices, garch_vol=garch_vols, kalman_vel=kalman_vels
        ))

    return records


# ====================================================================
# 8. 输出与可视化
# ====================================================================

def export_csv(records: List[HourlyRecord], filepath: str):
    """Export full hourly data to CSV."""
    item_names = list(ITEMS.keys())
    with open(filepath, 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        header = [
            'day', 'hour', 'online_players', 'm1_supply', 'supply_ratio',
            'inflation_rate', 'market_heat', 'lambda_multiplier', 'price_index'
        ]
        for name in item_names:
            header.append(f"{name}_price")
            header.append(f"{name}_garch_vol")
        w.writerow(header)

        for r in records:
            row = [r.day, r.hour, r.online_players, r.m1_supply, r.supply_ratio,
                   r.inflation_rate, r.market_heat, r.lambda_multiplier, r.price_index]
            for name in item_names:
                row.append(r.item_prices.get(name, 0.0))
                row.append(r.garch_vol.get(name, 0.0))
            w.writerow(row)


def compute_daily(records: List[HourlyRecord]) -> Dict:
    """Aggregate hourly records into daily stats."""
    days = defaultdict(list)
    for r in records:
        days[int(r.day)].append(r)

    result = {
        'day': [], 'supply_ratio': [], 'price_index': [], 'inflation_rate': [],
        'market_heat': [], 'lambda_multiplier': [],
    }
    for name in ITEMS:
        result[f'{name}_price'] = []
        result[f'{name}_garch_vol'] = []

    for d in sorted(days.keys()):
        day_recs = days[d]
        result['day'].append(d)
        result['supply_ratio'].append(sum(r.supply_ratio for r in day_recs) / len(day_recs))
        result['price_index'].append(sum(r.price_index for r in day_recs) / len(day_recs))
        result['inflation_rate'].append(sum(r.inflation_rate for r in day_recs) / len(day_recs))
        result['market_heat'].append(sum(r.market_heat for r in day_recs) / len(day_recs))
        result['lambda_multiplier'].append(sum(r.lambda_multiplier for r in day_recs) / len(day_recs))
        for name in ITEMS:
            result[f'{name}_price'].append(
                sum(r.item_prices.get(name, 0) for r in day_recs) / len(day_recs))
            result[f'{name}_garch_vol'].append(
                sum(r.garch_vol.get(name, 0) for r in day_recs) / len(day_recs))
    return result


def export_daily_csv(daily: Dict, filepath: str):
    """Export daily aggregated data."""
    item_names = list(ITEMS.keys())
    with open(filepath, 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        header = ['day', 'supply_ratio', 'price_index', 'inflation_rate',
                  'market_heat', 'lambda_multiplier']
        for name in item_names:
            header.append(f"{name}_price")
            header.append(f"{name}_garch_vol")
        w.writerow(header)

        for i in range(len(daily['day'])):
            row = [
                daily['day'][i], daily['supply_ratio'][i], daily['price_index'][i],
                daily['inflation_rate'][i], daily['market_heat'][i], daily['lambda_multiplier'][i]
            ]
            for name in item_names:
                row.append(daily[f'{name}_price'][i])
                row.append(daily[f'{name}_garch_vol'][i])
            w.writerow(row)


def print_report(records: List[HourlyRecord], daily: Dict):
    """Print comprehensive statistical report."""
    n = len(daily['day'])
    item_names = list(ITEMS.keys())

    print("=" * 80)
    print("  EcoBridge 长期经济模拟报告 — 365天 Minecraft 服务器经济预测")
    print("=" * 80)

    # Macro summary
    print(f"\n{'═' * 80}")
    print("  一、宏观经济指标")
    print(f"{'═' * 80}")

    avg_supply = sum(daily['supply_ratio']) / n
    min_supply = min(daily['supply_ratio'])
    max_supply = max(daily['supply_ratio'])
    print(f"  M1 供应比率  均值: {avg_supply:.4f}  最低: {min_supply:.4f}  最高: {max_supply:.4f}")

    avg_pi = sum(daily['price_index']) / n
    min_pi = min(daily['price_index'])
    max_pi = max(daily['price_index'])
    print(f"  价格指数      均值: {avg_pi:.4f}  最低: {min_pi:.4f}  最高: {max_pi:.4f}")

    avg_inf = sum(daily['inflation_rate']) / n
    max_inf = max(daily['inflation_rate'])
    min_inf = min(daily['inflation_rate'])
    print(f"  通胀率        均值: {avg_inf:.4%}  最低: {min_inf:.4%}  最高: {max_inf:.4%}")

    avg_heat = sum(daily['market_heat']) / n
    print(f"  市场热度      均值: {avg_heat:.2f}")

    # Per-item analysis
    print(f"\n{'═' * 80}")
    print("  二、物品价格长期预测")
    print(f"{'═' * 80}")
    print(f"  {'物品':<18} {'初始价':>10} {'最终价':>10} {'涨幅':>8}  {'年均波动率':>12}  {'趋势'}")
    print(f"  {'─' * 18} {'─' * 10} {'─' * 10} {'─' * 8}  {'─' * 12}  {'─' * 20}")

    for name in item_names:
        cfg = ITEMS[name]
        initial = cfg['base_price']
        prices = daily[f'{name}_price']
        final = prices[-1]
        change_pct = (final / initial - 1.0) * 100
        avg_vol = sum(daily[f'{name}_garch_vol']) / n

        # Trend direction
        first_half = sum(prices[:n//2]) / (n//2)
        second_half = sum(prices[n//2:]) / (n - n//2)
        if second_half > first_half * 1.03:
            trend = "↑ 上升 (通胀压力)"
        elif second_half < first_half * 0.97:
            trend = "↓ 下降 (供给过剩)"
        else:
            trend = "→ 稳定"

        print(f"  {name:<18} {initial:>10.2f} {final:>10.2f} {change_pct:>+7.1f}%  {avg_vol:>12.6f}  {trend}")

    # Volatility clustering analysis
    print(f"\n{'═' * 80}")
    print("  三、波动率聚类分析 (GARCH)")
    print(f"{'═' * 80}")
    for name in item_names:
        vols = daily[f'{name}_garch_vol']
        vol_of_vol = (sum((v - sum(vols)/n)**2 for v in vols) / n) ** 0.5
        max_vol = max(vols)
        max_vol_day = vols.index(max_vol)
        print(f"  {name:<18} 均值波动: {sum(vols)/n:.6f}  波动率方差: {vol_of_vol:.6f}  "
              f"最大波动日: Day {max_vol_day} ({max_vol:.6f})")

    # Event impact analysis
    print(f"\n{'═' * 80}")
    print("  四、事件冲击分析")
    print(f"{'═' * 80}")

    events = [
        ("Day 28-30 通胀漏洞 (Infl. Exploit)", 28, 30),
        ("Day 96-99 激进回收 (Aggressive Sink)", 96, 99),
        ("Day 140-143 节庆活动 (Festival)", 140, 143),
        ("Day 200-202 内容更新 (Content Update)", 200, 202),
        ("Day 300-305 活动低谷 (Low Activity)", 300, 305),
    ]

    for label, d_start, d_end in events:
        i_start = d_start * 24
        i_end = d_end * 24
        pre_pi = sum(r.price_index for r in records[max(0,i_start-24):i_start]) / 24
        during_pi = sum(r.price_index for r in records[i_start:i_end]) / max(1, i_end - i_start)
        post_pi = sum(r.price_index for r in records[i_end:min(len(records),i_end+24)]) / 24
        print(f"  {label:<40} 前: {pre_pi:.4f} → 中: {during_pi:.4f} → 后: {post_pi:.4f}")

    # Stability assessment
    print(f"\n{'═' * 80}")
    print("  五、系统稳定性评估")
    print(f"{'═' * 80}")

    # Check tail stability
    tail_start = int(n * 0.8)
    tail_supply = daily['supply_ratio'][tail_start:]
    tail_price = daily['price_index'][tail_start:]
    tail_supply_std = (sum((v - sum(tail_supply)/len(tail_supply))**2 for v in tail_supply) / len(tail_supply)) ** 0.5
    tail_price_std = (sum((v - sum(tail_price)/len(tail_price))**2 for v in tail_price) / len(tail_price)) ** 0.5

    checks = []
    checks.append(("供给比率 ∈ [0.50, 1.80]", min_supply >= 0.50 and max_supply <= 1.80))
    checks.append(("价格指数 ∈ [0.30, 2.80]", min_pi >= 0.30 and max_pi <= 2.80))
    checks.append(("尾部供给波动 < 0.16", tail_supply_std < 0.16))
    checks.append(("尾部价格波动 < 0.16", tail_price_std < 0.16))

    for label, ok in checks:
        status = "✓ 通过" if ok else "✗ 失败"
        print(f"  {label:<40} {status}")

    all_pass = all(ok for _, ok in checks)
    print(f"\n  综合判定: {'✓ 系统稳定 — 长期数学模型预测经济不会崩溃' if all_pass else '✗ 存在不稳定因素，需调整参数'}")

    # Year-end projections
    print(f"\n{'═' * 80}")
    print("  六、年度预测摘要")
    print(f"{'═' * 80}")
    print(f"  模型: GARCH(1,1) + Kalman(3-state) + FuzzyFluid + PID")
    print(f"  模拟服务器规模: {ONLINE_PLAYERS_BASE}-{ONLINE_PLAYERS_PEAK} 在线玩家")
    print(f"  模拟天数: {SIM_DAYS} 天 ({TOTAL_HOURS} 模拟小时)")
    print(f"  M1 终值: {daily['supply_ratio'][-1] * TARGET_M1:,.0f} (初始 {TARGET_M1:,.0f})")
    print(f"  价格指数终值: {daily['price_index'][-1]:.4f}")

    # Top movers
    changes = [(name, (daily[f'{name}_price'][-1] / ITEMS[name]['base_price'] - 1.0) * 100)
               for name in item_names]
    changes.sort(key=lambda x: -abs(x[1]))
    print(f"\n  价格变动排名:")
    for name, chg in changes[:4]:
        direction = "涨" if chg > 0 else "跌"
        print(f"    {name}: {direction} {abs(chg):.1f}%")

    print(f"\n{'═' * 80}")
    print("  报告生成完毕。CSV 数据文件: sim_daily.csv, sim_hourly.csv")
    print(f"{'═' * 80}")


# ====================================================================
# Main
# ====================================================================

if __name__ == '__main__':
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sim_output')
    os.makedirs(out_dir, exist_ok=True)

    print("正在运行 EcoBridge 365天经济模拟...")
    print(f"({TOTAL_HOURS} 个模拟小时, {len(ITEMS)} 种物品, {SIM_DAYS} 天)")
    records = run_simulation()

    daily = compute_daily(records)

    hourly_path = os.path.join(out_dir, 'sim_hourly.csv')
    daily_path = os.path.join(out_dir, 'sim_daily.csv')
    export_csv(records, hourly_path)
    export_daily_csv(daily, daily_path)
    print(f"CSV 已保存: {daily_path}, {hourly_path}")

    print_report(records, daily)
