#!/usr/bin/env python3
"""
EcoBridge 抗自动化/刷物品定价表
================================
在原始配方价格基础上，根据物品是否可被红石机械自动化量产、
是否曾被复制bug影响等因素，重新计算实际市场价格。

原则：
  1. 红石农场的产物 → 价格除以10，lambda翻倍（供应极大）
  2. 复制bug高危物品 → 价格除以5，lambda翻倍
  3. 手动才能获取的物品 → 保持原价或加价
  4. Boss/探索限定 → 加价30%（不可量产，纯靠肝）
"""

import urllib.request, json, sys, os

GIST = 'https://api.github.com/gists/92d08120f0d6d25175f6c7a30e3ccac7'
OUTPUT = os.path.join(os.path.dirname(__file__), 'ecobridge-java/src/main/resources/items_hardened.yml')

# ====================================================================
# 分类规则
# ====================================================================

# 红石自动化农场可量产的物品 → 价格/10, lambda×2
AUTO_FARMED = {
    # 铁傀儡农场
    'iron_ingot', 'iron_block', 'iron_nugget', 'poppy',
    # 金农场（僵尸猪灵）
    'gold_ingot', 'gold_nugget', 'gold_block', 'rotten_flesh', 'golden_sword',
    # 刷怪塔通用
    'bone', 'arrow', 'string', 'spider_eye', 'gunpowder', 'slime_ball',
    'slime_block', 'ender_pearl', 'blaze_rod', 'blaze_powder',
    'magma_cream', 'ghast_tear', 'phantom_membrane',
    # 女巫农场
    'redstone', 'glowstone_dust', 'glass_bottle', 'sugar', 'stick',
    # 袭击农场
    'emerald', 'emerald_block', 'totem_of_undying', 'crossbow', 'saddle',
    'ominous_bottle',
    # 农作物（全自动）
    'wheat', 'carrot', 'potato', 'beetroot', 'sugar_cane', 'bamboo',
    'cactus', 'kelp', 'sea_pickle', 'melon_slice', 'melon', 'pumpkin',
    'sweet_berries', 'glow_berries', 'apple', 'brown_mushroom', 'red_mushroom',
    # 树场/TNT复制机
    'oak_log', 'spruce_log', 'birch_log', 'jungle_log', 'acacia_log',
    'dark_oak_log', 'mangrove_log', 'cherry_log', 'crimson_stem', 'warped_stem',
    'oak_planks', 'spruce_planks', 'birch_planks', 'jungle_planks',
    'acacia_planks', 'dark_oak_planks', 'mangrove_planks', 'cherry_planks',
    'crimson_planks', 'warped_planks', 'stick',
    # 圆石生成器
    'cobblestone', 'stone', 'smooth_stone',
    # 岩浆农场
    'lava_bucket',
    # 鸡蛋农场
    'egg', 'feather', 'chicken',
    # 羊毛农场
    'white_wool', 'mutton', 'cooked_mutton',
    # 牛肉/皮革农场
    'beef', 'leather', 'cooked_beef',
    # 猪灵交易
    'crying_obsidian', 'fire_charge', 'gravel', 'leather',
    'nether_brick', 'obsidian', 'soul_sand', 'nether_quartz',
    'glowstone', 'magma_block',
}

# 曾被复制bug影响的物品 → 价格/5, lambda×2
DUPED = {
    'tnt', 'sand', 'red_sand', 'gravel', 'concrete_powder',
    'white_concrete_powder', 'orange_concrete_powder', 'magenta_concrete_powder',
    'light_blue_concrete_powder', 'yellow_concrete_powder', 'lime_concrete_powder',
    'pink_concrete_powder', 'gray_concrete_powder', 'light_gray_concrete_powder',
    'cyan_concrete_powder', 'purple_concrete_powder', 'blue_concrete_powder',
    'brown_concrete_powder', 'green_concrete_powder', 'red_concrete_powder',
    'black_concrete_powder',
    'rail', 'powered_rail', 'detector_rail', 'activator_rail',
    'carpet', 'white_carpet', 'moss_carpet',
    'string', 'tripwire_hook',
}

# Boss/探索限定 → 价格×1.3, lambda×0.7（更稀有，更稳定）
BOSS_LOOT = {
    'nether_star', 'dragon_egg', 'dragon_head', 'elytra',
    'wither_skeleton_skull', 'heart_of_the_sea', 'sponge', 'wet_sponge',
    'trident', 'echo_shard', 'disc_fragment_5', 'goat_horn',
    'pigstep', 'otherside', 'relic', 'creator',
    'netherite_upgrade', 'snout_armor_trim', 'rib_armor_trim',
    'eye_armor_trim', 'vex_armor_trim', 'tide_armor_trim',
    'wayfinder_armor_trim', 'raiser_armor_trim', 'shaper_armor_trim',
    'host_armor_trim', 'ward_armor_trim', 'silence_armor_trim',
    'spire_armor_trim', 'flow_armor_trim', 'bolt_armor_trim',
    'coast_armor_trim', 'dune_armor_trim', 'sentry_armor_trim',
    'wild_armor_trim', 'snort_pottery_sherd', 'howl_pottery_sherd',
    'angler_pottery_sherd', 'shelter_pottery_sherd', 'archer_pottery_sherd',
    'prize_pottery_sherd', 'skull_pottery_sherd', 'blade_pottery_sherd',
    'brewer_pottery_sherd', 'burn_pottery_sherd', 'danger_pottery_sherd',
    'explorer_pottery_sherd', 'friend_pottery_sherd', 'heart_pottery_sherd',
    'heartbreak_pottery_sherd', 'mourner_pottery_sherd', 'plenty_pottery_sherd',
    'arms_up_pottery_sherd', 'guster_pottery_sherd', 'scrape_pottery_sherd',
    'flow_pottery_sherd', 'miner_pottery_sherd',
}

# 只能手动获取 → 保持或略加价
MANUAL_ONLY = {
    'ancient_debris', 'netherite_scrap', 'netherite_ingot', 'netherite_block',
    'diamond', 'diamond_ore', 'deepslate_diamond_ore',
    'diamond_block', 'diamond_sword', 'diamond_pickaxe', 'diamond_axe',
    'diamond_shovel', 'diamond_hoe', 'diamond_helmet', 'diamond_chestplate',
    'diamond_leggings', 'diamond_boots',
    'amethyst_shard', 'amethyst_block', 'budding_amethyst',
    'shulker_shell', 'shulker_box',  # 末地城探索
    'sculk_sensor', 'sculk_shrieker', 'sculk_catalyst', 'sculk',
    'calibrated_sculk_sensor',
    'experience_bottle',  # 村民交易/宝箱
    'name_tag', 'lead',
    'music_disc_11', 'music_disc_13', 'music_disc_blocks', 'music_disc_cat',
    'music_disc_chirp', 'music_disc_far', 'music_disc_mall', 'music_disc_mellohi',
    'music_disc_stal', 'music_disc_strad', 'music_disc_wait', 'music_disc_ward',
    'skeleton_skull', 'zombie_head', 'creeper_head',  # 高压苦力怕
    'bell', 'lodestone', 'respawn_anchor',
    'conduit', 'prismarine_shard', 'prismarine_crystals', 'dark_prismarine',
    'sea_lantern',
}

# ====================================================================
# 计算逻辑
# ====================================================================

def fetch_worth():
    resp = json.load(urllib.request.urlopen(GIST))
    raw = resp['files']['essentials-worth.yml']['raw_url']
    content = urllib.request.urlopen(raw).read().decode('utf-8')
    items = {}
    for line in content.split('\n'):
        line = line.strip()
        if ':' in line and not line.startswith('#'):
            k, v = line.split(':', 1)
            try:
                items[k.strip()] = float(v.strip())
            except ValueError:
                pass
    return items

def normalize(name):
    return name.strip().lower().replace(' ', '_')

def tier_info(price, lam):
    if price <= 2:     return 'trivial', '随处可见', lam
    if price <= 20:    return 'basic', '基础材料', lam
    if price <= 200:   return 'intermediate', '常用物品', lam
    if price <= 2000:  return 'advanced', '贵重物品', lam
    if price <= 9000:  return 'rare', '稀有物品', lam
    return 'endgame', '终极物品', lam

CATEGORIES = {
    'auto_farmed': (0.10, 2.0, '红石自动化'),
    'duped':       (0.20, 2.0, '曾受复制bug影响'),
    'boss_loot':   (1.30, 0.7, 'Boss/探索限定'),
    'manual_only': (1.00, 1.0, '手动获取'),
}

def main():
    worth = fetch_worth()
    print(f'Loaded {len(worth)} items from worth.yml')

    results = {}
    for name, base_price in worth.items():
        n = normalize(name)
        factor, lam_mul, tag = 1.0, 1.0, 'normal'

        if n in MANUAL_ONLY or any(n.startswith(p) for p in MANUAL_ONLY):
            factor, lam_mul, tag = 1.0, 1.0, 'manual_only'
        if n in BOSS_LOOT:
            factor, lam_mul, tag = 1.30, 0.7, 'boss_loot'
        if n in DUPED:
            factor, lam_mul, tag = 0.20, 2.0, 'duped'
        if n in AUTO_FARMED:
            factor, lam_mul, tag = 0.10, 2.0, 'auto_farmed'

        price = max(0.01, round(base_price * factor, 2))
        lam = round(0.03 * lam_mul, 4) if base_price < 2 else \
              round(0.020 * lam_mul, 4) if base_price < 20 else \
              round(0.012 * lam_mul, 4) if base_price < 200 else \
              round(0.006 * lam_mul, 4) if base_price < 2000 else \
              round(0.003 * lam_mul, 4) if base_price < 9000 else \
              round(0.0015 * lam_mul, 4)

        results[name] = (price, lam, base_price, tag)

    # Stats
    stats = {k: 0 for k in CATEGORIES}
    stats['normal'] = 0
    for _, _, _, tag in results.values():
        stats[tag] = stats.get(tag, 0) + 1

    print('\n=== 分类统计 ===')
    for tag, (factor, lm, desc) in CATEGORIES.items():
        print(f'  {desc:12s}: {stats[tag]:>4d} 物品  (价格×{factor:.2f}, λ×{lm:.1f})')
    normal_count = stats.get('normal', 0)
    print(f'  {"普通物品":12s}: {normal_count:>4d} 物品')

    # Generate YAML
    lines = [
        '# EcoBridge 抗自动化/刷物品定价表',
        '# Generated from mc-toolkit recipe data + server reality adjustments',
        '#',
        '# Adjustments applied:',
        '#   红石自动化农场产物 → 价格÷10, lambda×2',
        '#   复制bug高危物品 → 价格÷5, lambda×2',
        '#   Boss/探索限定 → 价格×1.3, lambda×0.7',
        '#',
        '# Column legend:',
        '#   base = original recipe price',
        '#   adjusted = after automation/dupe reality check',
        '#   λ = price sensitivity (higher = more volatile)',
        '#   tag = classification',
        '',
    ]

    lines.append('item-settings:')
    for tag, (factor, _, desc) in sorted(CATEGORIES.items()):
        lines.append(f'  # ====== {desc} ======')

    # Sort by tag priority then price
    tag_order = {v: i for i, v in enumerate(['boss_loot', 'manual_only', 'auto_farmed', 'duped'])}
    sorted_items = sorted(results.items(),
        key=lambda x: (tag_order.get(x[1][3], 99), -x[1][0]))

    for name, (price, lam, orig, tag) in sorted_items:
        readable = name.replace('_', ' ').title()
        desc = {
            'boss_loot': 'Boss掉落',
            'manual_only': '手动获取',
            'auto_farmed': '红石农场',
            'duped': '复制bug',
        }.get(tag, '普通')

        indent = '    '
        lines.append(f'{indent}# {readable} [{desc}] 原价={orig:.1f}')
        lines.append(f'{indent}default.{name}:')
        lines.append(f'{indent}  base-price: {price:.2f}')
        lines.append(f'{indent}  lambda: {lam:.4f}')
        lines.append('')

    with open(OUTPUT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    print(f'\nWritten to {OUTPUT}')

    # Show some comparisons
    print('\n=== 调整前后对比 (选20个) ===')
    comparisons = [
        ('iron_ingot', '铁锭-铁傀儡塔'),
        ('gold_ingot', '金锭-猪灵塔'),
        ('gunpowder', '火药-苦力怕塔'),
        ('emerald', '绿宝石-袭击塔'),
        ('oak_log', '橡木-树场'),
        ('cobblestone', '圆石-刷石机'),
        ('string', '线-蜘蛛塔'),
        ('ender_pearl', '末影珍珠-小黑塔'),
        ('tnt', 'TNT-复制机'),
        ('sand', '沙子-复制机'),
        ('nether_star', '下界之星-Boss'),
        ('dragon_egg', '龙蛋-Boss'),
        ('ancient_debris', '远古残骸-手动'),
        ('diamond', '钻石-手动'),
        ('netherite_ingot', '下界合金锭-手动'),
        ('elytra', '鞘翅-末地城'),
        ('totem_of_undying', '不死图腾-袭击塔'),
        ('trident', '三叉戟-溺尸'),
        ('beacon', '信标-Boss'),
        ('shulker_shell', '潜影壳-末地城'),
    ]
    for name, desc in comparisons:
        if name in results:
            price, lam, orig, tag = results[name]
            change = (price / max(orig, 0.01) - 1) * 100
            print(f'  {desc:24s} ${orig:>8.1f} → ${price:>8.2f}  ({change:+.0f}%)  λ={lam:.4f}')

if __name__ == '__main__':
    main()
