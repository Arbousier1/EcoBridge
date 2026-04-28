#!/usr/bin/env python3
"""
EcoBridge items.yml generator — imports pricing from Minecraft recipe analysis.

Sources:
  worth.yml by queengooborg — prices derived from actual Minecraft recipes
  https://gist.github.com/queengooborg/92d08120f0d6d25175f6c7a30e3ccac7

Each item gets:
  - base-price: from worth.yml data (recipe-based value)
  - lambda: price sensitivity, auto-assigned by acquisition difficulty tier
  - sell-ratio: custom sell multiplier (defaults to global)

Difficulty tiers (auto-assigned from price range):
  Trivial    (<2)    — cobblestone, dirt, grass      → lambda 0.030
  Basic      (<20)   — planks, sticks, basic tools   → lambda 0.020
  Intermed.  (<200)  — iron gear, redstone items     → lambda 0.012
  Advanced   (<2000) — diamond gear, enchantments    → lambda 0.006
  Rare       (<9000) — netherite, tridents, templates → lambda 0.003
  Endgame    (>=9000)— beacons, nether stars          → lambda 0.0015
"""

import urllib.request
import json
import sys
import os

GIST_API = 'https://api.github.com/gists/92d08120f0d6d25175f6c7a30e3ccac7'
OUTPUT = os.path.join(os.path.dirname(__file__), 'ecobridge-java', 'src', 'main', 'resources', 'items_template.yml')

TIERS = [
    ('trivial',      2.0,    0.030, '徒手可得'),
    ('basic',       20.0,    0.020, '基础合成'),
    ('intermediate', 200.0,   0.012, '铁器时代'),
    ('advanced',    2000.0,   0.006, '钻石装备'),
    ('rare',        9000.0,   0.003, '稀有掉落'),
    ('endgame',     float('inf'), 0.0015, '终极物品'),
]

# Items so common they should have explicit sell limits
HIGH_VOLUME = {
    'cobblestone', 'dirt', 'grass_block', 'sand', 'gravel',
    'oak_log', 'spruce_log', 'birch_log', 'stone', 'deepslate',
    'netherrack', 'kelp', 'bamboo', 'sugar_cane',
}

# Items that should NEVER be auto-sold by the system (exploit risk)
BLACKLIST = {
    'dragon_egg', 'command_block', 'barrier', 'structure_block',
    'debug_stick', 'knowledge_book',
}

def fetch_items():
    resp = json.load(urllib.request.urlopen(GIST_API))
    raw_url = resp['files']['essentials-worth.yml']['raw_url']
    content = urllib.request.urlopen(raw_url).read().decode('utf-8')
    items = {}
    for line in content.split('\n'):
        line = line.strip()
        if ':' in line and not line.startswith('#'):
            parts = line.split(':', 1)
            if len(parts) == 2:
                try:
                    key = parts[0].strip()
                    val = float(parts[1].strip())
                    if key not in BLACKLIST:
                        items[key] = val
                except ValueError:
                    pass
    return items

def get_tier(price):
    for name, max_price, lam, desc in TIERS:
        if price <= max_price:
            return name, lam, desc
    return 'basic', 0.020, '基础合成'

def generate():
    items = fetch_items()
    print(f'Fetched {len(items)} items from worth.yml')

    by_tier = {t[0]: [] for t in TIERS}
    for name, price in sorted(items.items()):
        tier, lam, desc = get_tier(price)
        by_tier[tier].append((name, price, lam))

    lines = [
        '# EcoBridge items.yml template',
        f'# Generated from {len(items)} items via mc-toolkit recipe analysis',
        '# Source: https://gist.github.com/queengooborg/92d08120f0d6d25175f6c7a30e3ccac7',
        '#',
        '# Each item has:',
        '#   base-price — derived from Minecraft crafting recipes',
        '#   lambda — price sensitivity (lower = more stable)',
        '#   sell-ratio — optional per-item sell multiplier',
        '#   daily-limit — optional per-player daily sell cap (high-volume items only)',
        '#',
        '# Difficulty tiers:',
    ]
    for name, max_p, lam, desc in TIERS:
        count = len(by_tier[name])
        lines.append(f'#   {desc:<10} ({count:>4} items) λ={lam:.4f}')

    lines.append('')
    lines.append('item-settings:')

    for name, max_p, lam, desc in TIERS:
        lines.append(f'  # === {desc} (λ={lam:.4f}) ===')
        for item_name, price, item_lam in sorted(by_tier[name]):
            # Convert to UltimateShop format: shopId.productId
            readable = item_name.replace('_', ' ').title()
            indent = '    '
            lines.append(f'{indent}# {readable}')
            lines.append(f'{indent}default.{item_name}:')
            lines.append(f'{indent}  base-price: {price:.2f}')
            lines.append(f'{indent}  lambda: {item_lam:.4f}')
            if item_name in HIGH_VOLUME:
                lines.append(f'{indent}  daily-limit: 512')
            lines.append('')

    with open(OUTPUT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    print(f'Written to {OUTPUT}')

    # Summary
    print()
    for name, max_p, lam, desc in TIERS:
        count = len(by_tier[name])
        pct = count / len(items) * 100
        print(f'  {desc:<10} {count:>4} items ({pct:>5.1f}%)  λ={lam:.4f}  price < {max_p}')

if __name__ == '__main__':
    generate()
