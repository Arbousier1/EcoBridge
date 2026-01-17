package top.ellan.ecobridge.manager;

import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.ObjectShop;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.ObjectLimit; 
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.InternalPlaceholder;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UltimateShop 限额对象注入器 (Final Production Version)
 * <p>
 * 功能:
 * 1. 通过反射将 EcoBridge 的动态限额逻辑注入到 UltimateShop 商品中。
 * 2. 支持热重载安全还原，防止内存泄漏。
 * 3. 线程安全设计。
 */
public class UShopLimitInjector {

    // 缓存原始限额对象，用于卸载时恢复 (Key: Item -> [BuyLimit, SellLimit])
    private static final Map<ObjectItem, ObjectLimit[]> originalLimits = new ConcurrentHashMap<>();
    
    // 静态反射字段缓存 (提升性能)
    private static Field buyLimitField;
    private static Field sellLimitField;
    private static boolean reflectionReady = false;

    // 静态初始化块：只查找一次字段
    static {
        try {
            buyLimitField = ObjectItem.class.getDeclaredField("buyLimit");
            sellLimitField = ObjectItem.class.getDeclaredField("sellLimit");
            buyLimitField.setAccessible(true);
            sellLimitField.setAccessible(true);
            reflectionReady = true;
        } catch (Exception e) {
            LogUtil.error("EcoBridge 严重错误: 无法初始化 UltimateShop 限额反射字段，限额接管将失效。", e);
        }
    }

    /**
     * 执行注入逻辑
     * 建议在 onEnable 延迟 1 tick 后调用
     */
    public static void execute(EcoBridge plugin) {
        // 1. 检查配置开关
        if (!plugin.getConfig().getBoolean("integrations.ultimateshop.enabled", true)) {
            return;
        }
        
        // 2. 检查反射环境
        if (!reflectionReady) {
            LogUtil.warn("反射环境未就绪，跳过限额注入。");
            return;
        }

        ConfigManager cm = ConfigManager.configManager;
        if (cm == null) return;

        int count = 0;
        try {
            // 3. 遍历所有商店和商品
            for (ObjectShop shop : cm.getShops()) {
                if (shop.getConfig() == null) continue;
                
                for (String itemId : shop.getConfig().getConfigurationSection("items").getKeys(false)) {
                    ObjectItem item = shop.getProduct(itemId);
                    if (item == null) continue;

                    // 4. 线程安全锁 (防止 UltimateShop 此时正在读写该商品)
                    synchronized (item) {
                        // 备份原始对象 (仅备份一次)
                        if (!originalLimits.containsKey(item)) {
                            ObjectLimit oldBuy = (ObjectLimit) buyLimitField.get(item);
                            ObjectLimit oldSell = (ObjectLimit) sellLimitField.get(item);
                            // 即使是 null 也要存入，以便还原时置空
                            originalLimits.put(item, new ObjectLimit[]{oldBuy, oldSell});
                        }

                        // 获取当前值，准备注入
                        ObjectLimit currentBuy = (ObjectLimit) buyLimitField.get(item);
                        ObjectLimit currentSell = (ObjectLimit) sellLimitField.get(item);
                        
                        // 防止重复注入 (如果已经是我们的类，就不再套娃)
                        if (!(currentBuy instanceof EcoBridgeDynamicLimit)) {
                             buyLimitField.set(item, new EcoBridgeDynamicLimit(plugin, currentBuy));
                        }
                        if (!(currentSell instanceof EcoBridgeDynamicLimit)) {
                             sellLimitField.set(item, new EcoBridgeDynamicLimit(plugin, currentSell));
                        }
                    }
                    count++;
                }
            }
            LogUtil.info("UltimateShop 动态限额内核已注入 (" + count + " 个商品)。");

        } catch (Exception e) {
            LogUtil.error("限额注入过程中发生异常", e);
        }
    }

    /**
     * 还原操作：务必在 onDisable 中调用
     */
    public static void revert() {
        if (originalLimits.isEmpty() || !reflectionReady) return;

        try {
            int count = 0;
            for (Map.Entry<ObjectItem, ObjectLimit[]> entry : originalLimits.entrySet()) {
                ObjectItem item = entry.getKey();
                ObjectLimit[] originals = entry.getValue();

                if (item != null) {
                    synchronized (item) {
                        buyLimitField.set(item, originals[0]);
                        sellLimitField.set(item, originals[1]);
                    }
                    count++;
                }
            }
            originalLimits.clear();
            LogUtil.info("已还原 " + count + " 个商品的原始限额逻辑。");

        } catch (Exception e) {
            LogUtil.error("还原限额逻辑时发生错误", e);
        }
    }

    /**
     * EcoBridge 动态限额代理类
     */
    public static class EcoBridgeDynamicLimit extends ObjectLimit {
        
        private final EcoBridge plugin;
        private final ObjectLimit original; // 保存原始对象引用，用于回退

        public EcoBridgeDynamicLimit(EcoBridge plugin, ObjectLimit original) {
            // [构造函数适配]
            // 大多数 UltimateShop 版本都有无参构造。
            // 如果报错 "Implicit super constructor is undefined"，请改为:
            // super(new YamlConfiguration(), null, null); 
            super();
            
            this.plugin = plugin;
            this.original = original;
        }

        @Override
        public int getPlayerLimits(Player player) {
            // 1. 动态接管检查
            if (plugin.getConfig().getBoolean("integrations.ultimateshop.limit-takeover", true)) {
                // 返回物理硬上限 (例如 2000)
                return InternalPlaceholder.PHYSICAL_HARD_CAP;
            }

            // 2. 回退到原始逻辑
            if (original != null) {
                return original.getPlayerLimits(player);
            }
            return -1; // 原生逻辑中 -1 代表无限制
        }

        @Override
        public int getServerLimits(Player player) {
            // 目前不接管全服限额，直接透传给原始对象
            if (original != null) {
                return original.getServerLimits(player);
            }
            return -1;
        }
    }
}