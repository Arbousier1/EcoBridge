#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EcoBridge 长期经济模拟 — 可视化图表生成器
读取 sim_daily.csv 生成 6 张专业级经济图表
"""

import csv
import os
import sys
import io

if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np

# ====================================================================
# 读取数据
# ====================================================================

def load_daily_csv(filepath: str) -> dict:
    data = {}
    with open(filepath, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            for key, val in row.items():
                if key not in data:
                    data[key] = []
                data[key].append(float(val))
    return data

# ====================================================================
# 样式配置
# ====================================================================

plt.rcParams.update({
    'figure.facecolor': '#0f111a',
    'axes.facecolor': '#161822',
    'axes.edgecolor': '#2a2d3a',
    'axes.labelcolor': '#c0c5d4',
    'text.color': '#c0c5d4',
    'xtick.color': '#8a8fa0',
    'ytick.color': '#8a8fa0',
    'grid.color': '#1f2230',
    'grid.alpha': 0.7,
    'font.family': 'sans-serif',
    'font.sans-serif': ['Segoe UI', 'Arial', 'DejaVu Sans'],
    'figure.dpi': 120,
    'savefig.dpi': 150,
    'savefig.facecolor': '#0f111a',
    'savefig.bbox': 'tight',
})

COLORS = {
    'supply': '#22c55e',    # green
    'price': '#60a5fa',     # blue
    'inflation': '#f59e0b', # amber
    'm1': '#a78bfa',        # purple
    'heat': '#f87171',      # red
    'lambda': '#e879f9',    # pink
    'diamond': '#4ade80',
    'netherite': '#c084fc',
    'elytra': '#fbbf24',
    'iron': '#94a3b8',
    'oak': '#65a30d',
    'book': '#fb923c',
    'gunpowder': '#6b7280',
    'pearl': '#2dd4bf',
}

# ====================================================================
# 图表生成
# ====================================================================

def plot_macro_overview(ax, data):
    """图1: 宏观经济全景 — 供给比率、价格指数、通胀率"""
    days = data['day']
    ax2 = ax.twinx()
    ax3 = ax.twinx()
    ax3.spines['right'].set_position(('outward', 60))

    ax.fill_between(days, 1.0, data['supply_ratio'], alpha=0.15, color=COLORS['supply'])
    ax.plot(days, data['supply_ratio'], color=COLORS['supply'], linewidth=1.2, label='M1 Supply Ratio')
    ax.axhline(y=1.0, color='#ffffff', linewidth=0.6, linestyle='--', alpha=0.3)
    ax.set_ylabel('Supply Ratio (M1/Target)', color=COLORS['supply'])
    ax.set_ylim(0.40, 1.90)
    ax.tick_params(axis='y', colors=COLORS['supply'])

    ax2.plot(days, data['price_index'], color=COLORS['price'], linewidth=1.6, label='Price Index')
    ax2.set_ylabel('Price Index', color=COLORS['price'])
    ax2.set_ylim(0.25, 1.05)
    ax2.tick_params(axis='y', colors=COLORS['price'])

    ax3.plot(days, data['inflation_rate'], color=COLORS['inflation'], linewidth=0.8, alpha=0.8, label='Inflation')
    ax3.set_ylabel('Inflation Rate', color=COLORS['inflation'])
    ax3.set_ylim(-0.05, 0.06)
    ax3.tick_params(axis='y', colors=COLORS['inflation'])

    ax.set_xlabel('Day')
    ax.set_title('Macroeconomic Overview (365-Day Simulation)', fontsize=13, fontweight='bold', pad=12)
    ax.grid(True, alpha=0.3)

    # Legend
    lines = ax.get_lines() + ax2.get_lines() + ax3.get_lines()
    labels = [l.get_label() for l in lines]
    ax.legend(lines, labels, loc='upper right', framealpha=0.8, facecolor='#161822', edgecolor='#2a2d3a', fontsize=8)

    # Event annotations
    events = [
        (29, 'Infl. Spike', '#f59e0b'),
        (97, 'Aggr. Sink', '#f87171'),
        (141, 'Festival', '#4ade80'),
        (201, 'Content Update', '#60a5fa'),
        (302, 'Low Activity', '#94a3b8'),
    ]
    for dx, label, color in events:
        ax.axvline(x=dx, color=color, linewidth=0.6, linestyle=':', alpha=0.6)
        ax.annotate(label, xy=(dx, 1.75), fontsize=7, color=color,
                    rotation=90, va='top', alpha=0.8)


def plot_item_prices(ax, data):
    """图2: 核心物品价格轨迹"""
    items = [
        ('diamond', 'Diamond', COLORS['diamond']),
        ('netherite', 'Netherite', COLORS['netherite']),
        ('elytra', 'Elytra', COLORS['elytra']),
        ('iron_ingot', 'Iron Ingot', COLORS['iron']),
        ('enchanted_book', 'Ench. Book', COLORS['book']),
        ('gunpowder', 'Gunpowder', COLORS['gunpowder']),
        ('ender_pearl', 'Ender Pearl', COLORS['pearl']),
    ]

    days = data['day']
    for key, label, color in items:
        prices = data[f'{key}_price']
        initial = prices[0]
        normalized = [p / initial * 100 for p in prices]
        ax.plot(days, normalized, color=color, linewidth=1.2, alpha=0.85, label=label)

    ax.axhline(y=100, color='#ffffff', linewidth=0.6, linestyle='--', alpha=0.3)
    ax.set_title('Item Price Trajectories (% of Initial Price)', fontsize=13, fontweight='bold', pad=12)
    ax.set_xlabel('Day')
    ax.set_ylabel('Price (% of Base)')
    ax.set_ylim(75, 125)
    ax.grid(True, alpha=0.3)
    ax.legend(loc='lower left', framealpha=0.8, facecolor='#161822', edgecolor='#2a2d3a', fontsize=7, ncol=4,
              columnspacing=0.8)


def plot_volatility_heatmap(ax, data):
    """图3: GARCH波动率热力图 — 所有物品的波动率时间序列"""
    item_names = ['diamond', 'netherite', 'elytra', 'iron_ingot', 'enchanted_book', 'gunpowder', 'ender_pearl', 'oak_log']
    days = data['day']

    # Build matrix [items x days_sampled]
    sample_every = 5  # sample every 5 days for readability
    sampled_days = days[::sample_every]
    matrix = np.zeros((len(item_names), len(sampled_days)))

    for i, name in enumerate(item_names):
        vols = data[f'{name}_garch_vol']
        matrix[i, :] = [vols[j] for j in range(0, len(vols), sample_every)]

    im = ax.imshow(matrix, aspect='auto', cmap='inferno', origin='lower',
                   extent=[0, 365, 0, len(item_names)],
                   vmin=0, vmax=np.percentile(matrix, 95))

    ax.set_yticks(np.arange(len(item_names)) + 0.5)
    ax.set_yticklabels([n.replace('_', ' ').title() for n in item_names], fontsize=8)
    ax.set_xlabel('Day')
    ax.set_title('GARCH Volatility Heatmap (Warmer = More Volatile)', fontsize=13, fontweight='bold', pad=12)

    cbar = plt.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    cbar.set_label('Volatility (sigma)', color='#c0c5d4', fontsize=9)
    cbar.ax.tick_params(colors='#8a8fa0')


def plot_event_impact(ax, data):
    """图4: 事件冲击前后对比 — bar chart"""
    events = [
        ('Infl. Exploit\nDay 28-30', 27, 31),
        ('Aggressive Sink\nDay 96-99', 95, 100),
        ('Festival\nDay 140-143', 139, 144),
        ('Content Update\nDay 200-202', 199, 203),
        ('Low Activity\nDay 300-305', 299, 306),
    ]

    x_labels = []
    pre_vals = []
    during_vals = []
    post_vals = []

    for label, d_pre, d_post in events:
        pre_pi = np.mean(data['price_index'][max(0,d_pre-1):d_pre])
        during_pi = np.mean(data['price_index'][d_pre:d_post])
        post_pi = np.mean(data['price_index'][d_post:min(365,d_post+1)])

        x_labels.append(label)
        pre_vals.append(pre_pi)
        during_vals.append(during_pi)
        post_vals.append(post_pi)

    x = np.arange(len(events))
    width = 0.25

    ax.bar(x - width, pre_vals, width, color='#60a5fa', alpha=0.85, label='Before Event', edgecolor='none')
    ax.bar(x, during_vals, width, color='#f59e0b', alpha=0.85, label='During Event', edgecolor='none')
    ax.bar(x + width, post_vals, width, color='#22c55e', alpha=0.85, label='After Event', edgecolor='none')

    ax.set_xticks(x)
    ax.set_xticklabels(x_labels, fontsize=7)
    ax.set_ylabel('Price Index')
    ax.set_title('Event Impact Analysis — Price Index Before/During/After', fontsize=13, fontweight='bold', pad=12)
    ax.grid(True, alpha=0.3, axis='y')
    ax.legend(framealpha=0.8, facecolor='#161822', edgecolor='#2a2d3a', fontsize=8)


def plot_decomposition(ax, data):
    """图5: 价格构成分解 — 随机选3个物品展示价格驱动因子"""
    days = data['day']
    item = 'diamond'
    prices = np.array(data[f'{item}_price'])
    vols = np.array(data[f'{item}_garch_vol'])

    # Smooth for visibility
    window = 24
    prices_smooth = np.convolve(prices, np.ones(window)/window, mode='same')
    vols_smooth = np.convolve(vols, np.ones(window)/window, mode='same')

    ax.fill_between(days, prices_smooth - vols_smooth * 10, prices_smooth + vols_smooth * 10,
                    alpha=0.2, color=COLORS['diamond'], label=f'{item.title()} GARCH Band')

    ax.plot(days, prices_smooth, color=COLORS['diamond'], linewidth=1.5, label=f'{item.title()} Price (24h MA)')
    ax.plot(days, vols_smooth * 50 + 90, color=COLORS['inflation'], linewidth=1.0, alpha=0.7,
            label=f'{item.title()} Volatility (x50, offset)')

    ax.set_title(f'Price Decomposition — {item.title()} with GARCH Confidence Band', fontsize=13, fontweight='bold', pad=12)
    ax.set_xlabel('Day')
    ax.set_ylabel('Price')
    ax.grid(True, alpha=0.3)
    ax.legend(framealpha=0.8, facecolor='#161822', edgecolor='#2a2d3a', fontsize=8)


def plot_correlation_matrix(ax, data):
    """图6: 物品价格相关性矩阵"""
    item_names = ['diamond', 'netherite', 'elytra', 'iron_ingot', 'enchanted_book', 'gunpowder', 'ender_pearl', 'oak_log']
    n = len(item_names)

    # Build correlation matrix from daily price data
    prices_matrix = np.zeros((n, len(data['day'])))
    for i, name in enumerate(item_names):
        prices_matrix[i, :] = data[f'{name}_price']

    corr = np.corrcoef(prices_matrix)

    im = ax.imshow(corr, cmap='RdBu_r', vmin=-1, vmax=1, aspect='equal')
    ax.set_xticks(range(n))
    ax.set_yticks(range(n))
    labels = [n.replace('_', ' ').title() for n in item_names]
    ax.set_xticklabels(labels, fontsize=7, rotation=45, ha='right')
    ax.set_yticklabels(labels, fontsize=7)

    # Add correlation values
    for i in range(n):
        for j in range(n):
            ax.text(j, i, f'{corr[i][j]:.2f}', ha='center', va='center',
                    fontsize=6, color='white' if abs(corr[i][j]) > 0.5 else '#222')

    ax.set_title('Item Price Correlation Matrix', fontsize=13, fontweight='bold', pad=12)
    cbar = plt.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    cbar.set_label('Correlation', color='#c0c5d4', fontsize=9)
    cbar.ax.tick_params(colors='#8a8fa0')


def plot_daily_cycle(ax, data):
    """图7: 日内价格周期 (平均每个小时的价格)"""
    # We need hourly data for this — read from sim_hourly.csv
    hourly_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sim_output', 'sim_hourly.csv')
    hourly = {}
    with open(hourly_path, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            for key, val in row.items():
                if key not in hourly:
                    hourly[key] = []
                hourly[key].append(float(val))

    hours = list(range(24))
    for item_name in ['diamond', 'iron_ingot', 'oak_log']:
        hour_prices = [[] for _ in range(24)]
        for i in range(len(hourly['hour'])):
            h = int(hourly['hour'][i])
            hour_prices[h].append(hourly[f'{item_name}_price'][i])

        avg_by_hour = [np.mean(ps) / np.mean(hour_prices[0]) * 100 if ps else 100 for ps in hour_prices]
        color = COLORS.get(item_name, '#ffffff')
        ax.plot(hours, avg_by_hour, color=color, linewidth=1.5, marker='o', markersize=3,
                label=item_name.replace('_', ' ').title())

    ax.axhline(y=100, color='#ffffff', linewidth=0.5, linestyle='--', alpha=0.3)
    ax.set_xticks(range(0, 24, 2))
    ax.set_title('Intraday Price Cycle (Average by Hour of Day, % of Daily Mean)', fontsize=13, fontweight='bold', pad=12)
    ax.set_xlabel('Hour of Day (UTC)')
    ax.set_ylabel('Price (% of Daily Average)')
    ax.set_ylim(98, 102)
    ax.grid(True, alpha=0.3)
    ax.legend(framealpha=0.8, facecolor='#161822', edgecolor='#2a2d3a', fontsize=8)


# ====================================================================
# Main — Generate Composite Figure
# ====================================================================

def main():
    data_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sim_output')
    data = load_daily_csv(os.path.join(data_dir, 'sim_daily.csv'))

    print("Generating economic charts...")

    # Create 2 composite figures with subplots
    # Figure 1: Macro Overview + Item Prices + Volatility Heatmap
    fig1, axes1 = plt.subplots(3, 1, figsize=(16, 18))
    plot_macro_overview(axes1[0], data)
    plot_item_prices(axes1[1], data)
    plot_volatility_heatmap(axes1[2], data)
    fig1.tight_layout(pad=2.0)

    path1 = os.path.join(data_dir, 'ecobridge_macro_and_prices.png')
    fig1.savefig(path1)
    print(f"  Saved: {path1}")
    plt.close(fig1)

    # Figure 2: Event Impact + Decomposition + Correlation + Daily Cycle
    fig2 = plt.figure(figsize=(16, 14))
    gs = fig2.add_gridspec(2, 2, hspace=0.35, wspace=0.30)

    ax_event = fig2.add_subplot(gs[0, 0])
    plot_event_impact(ax_event, data)

    ax_decomp = fig2.add_subplot(gs[0, 1])
    plot_decomposition(ax_decomp, data)

    ax_corr = fig2.add_subplot(gs[1, 0])
    plot_correlation_matrix(ax_corr, data)

    ax_cycle = fig2.add_subplot(gs[1, 1])
    plot_daily_cycle(ax_cycle, data)

    fig2.tight_layout(pad=2.0)

    path2 = os.path.join(data_dir, 'ecobridge_analysis.png')
    fig2.savefig(path2)
    print(f"  Saved: {path2}")
    plt.close(fig2)

    print("\nDone! Open the files to view the charts:")
    print(f"  {path1}")
    print(f"  {path2}")

    # Also try to open them automatically
    try:
        os.startfile(path1)
        os.startfile(path2)
    except Exception:
        pass


if __name__ == '__main__':
    main()
