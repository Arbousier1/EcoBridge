package top.ellan.ecobridge.hook;

import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.ObjectShop;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.GiveResult;
import cn.superiormc.ultimateshop.objects.items.ObjectLimit;
import cn.superiormc.ultimateshop.objects.items.TakeResult;
import cn.superiormc.ultimateshop.objects.items.prices.ObjectPrices;
import cn.superiormc.ultimateshop.objects.items.prices.PriceMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import su.nightexpress.coinsengine.api.currency.Currency;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.manager.EconomicStateManager;
import top.ellan.ecobridge.manager.PricingManager;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UltimateShop 统一接管管理器 (v3.0.1 - Clean Integration)
 * <p>
 * 职责：
 * 1. 集中管理价格 (Prices) 与限额 (Limits) 的反射注入。
 * 2. 移除未使用的冗余字段，消除 IDE 警告。
 * 3. 确保跨线程交易的一致性与资源回收的安全性。
 */
public class UShopHookManager {

    private static final Map<ObjectItem, ObjectPrices[]> originalPrices = new ConcurrentHashMap<>();
    private static final Map<ObjectItem, ObjectLimit[]> originalLimits = new ConcurrentHashMap<>();

    private static Field buyPriceField;
    private static Field sellPriceField;
    private static Field buyLimitField;
    private static Field sellLimitField;
    private static boolean reflectionReady = false;

    static {
        try {
            buyPriceField = ObjectItem.class.getDeclaredField("buyPrice");
            sellPriceField = ObjectItem.class.getDeclaredField("sellPrice");
            buyPriceField.setAccessible(true);
            sellPriceField.setAccessible(true);

            buyLimitField = ObjectItem.class.getDeclaredField("buyLimit");
            sellLimitField = ObjectItem.class.getDeclaredField("sellLimit");
            buyLimitField.setAccessible(true);
            sellLimitField.setAccessible(true);

            reflectionReady = true;
        } catch (Exception e) {
            LogUtil.error("无法初始化 UltimateShop 反射字段，接管引擎已禁用。", e);
        }
    }

    public static void execute(EcoBridge plugin) {
        if (!reflectionReady || !plugin.getConfig().getBoolean("integrations.ultimateshop.enabled", true)) {
            return;
        }

        ConfigManager cm = ConfigManager.configManager;
        if (cm == null) return;

        int priceInjected = 0;
        int limitInjected = 0;

        try {
            for (ObjectShop shop : cm.getShops()) {
                if (shop.getConfig() == null) continue;
                for (String itemId : shop.getConfig().getConfigurationSection("items").getKeys(false)) {
                    ObjectItem item = shop.getProduct(itemId);
                    if (item == null) continue;

                    synchronized (item) {
                        // 1. 接管价格逻辑
                        if (plugin.getConfig().getBoolean("integrations.ultimateshop.price-takeover", true)) {
                            if (!originalPrices.containsKey(item)) {
                                originalPrices.put(item, new ObjectPrices[]{
                                    (ObjectPrices) buyPriceField.get(item),
                                    (ObjectPrices) sellPriceField.get(item)
                                });
                            }
                            buyPriceField.set(item, new EcoBridgeDynamicPrice(item, itemId));
                            sellPriceField.set(item, new EcoBridgeDynamicPrice(item, itemId));
                            priceInjected++;
                        }

                        // 2. 接管限额逻辑
                        if (plugin.getConfig().getBoolean("integrations.ultimateshop.limit-takeover", true)) {
                            if (!originalLimits.containsKey(item)) {
                                originalLimits.put(item, new ObjectLimit[]{
                                    (ObjectLimit) buyLimitField.get(item),
                                    (ObjectLimit) sellLimitField.get(item)
                                });
                            }
                            buyLimitField.set(item, new EcoBridgeDynamicLimit(plugin, originalLimits.get(item)[0]));
                            sellLimitField.set(item, new EcoBridgeDynamicLimit(plugin, originalLimits.get(item)[1]));
                            limitInjected++;
                        }
                    }
                }
            }
            LogUtil.info("UltimateShop 接管完成: [价格代理: " + priceInjected + "] [限额代理: " + limitInjected + "]");
        } catch (Exception e) {
            LogUtil.error("接管执行过程中发生异常", e);
        }
    }

    public static void revert() {
        if (!reflectionReady) return;

        originalPrices.forEach((item, originals) -> {
            synchronized (item) {
                try {
                    buyPriceField.set(item, originals[0]);
                    sellPriceField.set(item, originals[1]);
                } catch (Exception ignored) {}
            }
        });

        originalLimits.forEach((item, originals) -> {
            synchronized (item) {
                try {
                    buyLimitField.set(item, originals[0]);
                    sellLimitField.set(item, originals[1]);
                } catch (Exception ignored) {}
            }
        });

        originalPrices.clear();
        originalLimits.clear();
        LogUtil.info("UltimateShop 所有接管代理已安全卸载，原始逻辑已还原。");
    }

    // ============================================================
    // INNER CLASS: 动态价格代理 (优化版)
    // ============================================================
    public static class EcoBridgeDynamicPrice extends ObjectPrices {
        private final String productId;
        private final String currencyId;

        private static Constructor<TakeResult> takeResultCtor;
        private static Constructor<GiveResult> giveResultCtor;
        private static Field takeResultMapField;
        private static Field giveResultMapField;

        static {
            try {
                try { takeResultCtor = TakeResult.class.getDeclaredConstructor(); } 
                catch (NoSuchMethodException e) { takeResultCtor = TakeResult.class.getDeclaredConstructor(boolean.class); }
                takeResultCtor.setAccessible(true);

                try { giveResultCtor = GiveResult.class.getDeclaredConstructor(); } 
                catch (NoSuchMethodException e) { giveResultCtor = GiveResult.class.getDeclaredConstructor(boolean.class); }
                giveResultCtor.setAccessible(true);

                takeResultMapField = findField(TakeResult.class, "resultMap", "results", "map");
                giveResultMapField = findField(GiveResult.class, "resultMap", "results", "map");
                if (takeResultMapField != null) takeResultMapField.setAccessible(true);
                if (giveResultMapField != null) giveResultMapField.setAccessible(true);
            } catch (Exception e) { e.printStackTrace(); }
        }

        public EcoBridgeDynamicPrice(ObjectItem item, String productId) {
            // 这里调用父类构造，标记为自定义类型
            super(new YamlConfiguration(), "EcoBridge", item, PriceMode.BUY); 
            this.productId = productId;
            // 从主类配置直接读取
            this.currencyId = EcoBridge.getInstance().getConfig().getString("economy.currency-id", "coins");
        }

        @Override
        public TakeResult take(Inventory inv, Player player, int times, int amount, boolean sim) {
            PricingManager pm = PricingManager.getInstance();
            var lock = pm.getItemLock(productId).readLock();
            lock.lock();
            try {
                double unitPrice = pm.calculateBuyPrice(productId);
                double total = unitPrice * amount;
                Map<String, Object> map = new HashMap<>();
                map.put("money", total);

                Currency cur = CoinsEngineAPI.getCurrency(currencyId);
                if (cur == null || CoinsEngineAPI.getBalance(player, cur) < total) return createTakeResult(false, map);

                if (!sim) {
                    CoinsEngineAPI.removeBalance(player, cur, total);
                    EconomicStateManager.getInstance().recordPurchase(player, productId, amount);
                }
                return createTakeResult(true, map);
            } finally { lock.unlock(); }
        }

        @Override
        public GiveResult give(Player player, int times, int amount) {
            PricingManager pm = PricingManager.getInstance();
            var lock = pm.getItemLock(productId).readLock();
            lock.lock();
            try {
                double unitPrice = pm.calculateSellPrice(productId);
                double total = unitPrice * amount;
                Map<String, Object> map = new HashMap<>();
                map.put("money", total);

                if (!isSimulationContext()) {
                    Currency cur = CoinsEngineAPI.getCurrency(currencyId);
                    if (cur != null) {
                        CoinsEngineAPI.addBalance(player, cur, total);
                        EconomicStateManager.getInstance().recordSale(player, productId, amount);
                    }
                }
                return createGiveResult(true, map);
            } finally { lock.unlock(); }
        }

        private static Field findField(Class<?> c, String... ns) {
            for (String n : ns) { try { return c.getDeclaredField(n); } catch (Exception ignored) {} }
            return null;
        }

        private TakeResult createTakeResult(boolean s, Map<String, Object> m) {
            try {
                TakeResult r = (takeResultCtor.getParameterCount() == 0) ? takeResultCtor.newInstance() : takeResultCtor.newInstance(s);
                if (takeResultMapField != null) takeResultMapField.set(r, m);
                return r;
            } catch (Exception e) { return null; }
        }

        private GiveResult createGiveResult(boolean s, Map<String, Object> m) {
            try {
                GiveResult r = (giveResultCtor.getParameterCount() == 0) ? giveResultCtor.newInstance() : giveResultCtor.newInstance(s);
                if (giveResultMapField != null) giveResultMapField.set(r, m);
                return r;
            } catch (Exception e) { return null; }
        }

        private boolean isSimulationContext() {
            return StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getClassName().contains("ModifyDisplayItem")));
        }
    }

    // ============================================================
    // INNER CLASS: 动态限额代理
    // ============================================================
    public static class EcoBridgeDynamicLimit extends ObjectLimit {
        private final EcoBridge plugin;
        private final ObjectLimit original;

        public EcoBridgeDynamicLimit(EcoBridge plugin, ObjectLimit original) {
            this.plugin = plugin;
            this.original = original;
        }

        @Override
        public int getPlayerLimits(Player player) {
            // 宏观管控逻辑：如果服务器触发了“紧急管控模式”，则锁定极低限额
            if (plugin.getConfig().getBoolean("economy.macro.panic-mode", false)) return 20; 
            return original != null ? original.getPlayerLimits(player) : -1;
        }

        @Override
        public int getServerLimits(Player player) {
            return original != null ? original.getServerLimits(player) : -1;
        }
    }
}