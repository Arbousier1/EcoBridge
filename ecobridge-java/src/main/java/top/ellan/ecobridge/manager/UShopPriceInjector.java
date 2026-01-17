package top.ellan.ecobridge.manager;

import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.ObjectShop;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.GiveResult;
import cn.superiormc.ultimateshop.objects.items.TakeResult;
import cn.superiormc.ultimateshop.objects.items.prices.ObjectPrices;
import cn.superiormc.ultimateshop.objects.items.prices.PriceMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import su.nightexpress.coinsengine.api.currency.Currency;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UltimateShop 价格计算接管器 (Fixed Compilation)
 */
public class UShopPriceInjector {

    private static final Map<ObjectItem, ObjectPrices[]> originalPrices = new ConcurrentHashMap<>();

    public static void execute(EcoBridge plugin) {
        if (!plugin.getConfig().getBoolean("integrations.ultimateshop.price-takeover", true)) {
            return;
        }

        ConfigManager cm = ConfigManager.configManager;
        if (cm == null) return;

        int count = 0;
        try {
            Field buyPriceField = ObjectItem.class.getDeclaredField("buyPrice");
            Field sellPriceField = ObjectItem.class.getDeclaredField("sellPrice");
            buyPriceField.setAccessible(true);
            sellPriceField.setAccessible(true);

            for (ObjectShop shop : cm.getShops()) {
                if (shop.getConfig() == null) continue;
                for (String itemId : shop.getConfig().getConfigurationSection("items").getKeys(false)) {
                    ObjectItem item = shop.getProduct(itemId);
                    if (item == null) continue;

                    synchronized (item) {
                        if (!originalPrices.containsKey(item)) {
                            ObjectPrices oldBuy = (ObjectPrices) buyPriceField.get(item);
                            ObjectPrices oldSell = (ObjectPrices) sellPriceField.get(item);
                            if (oldBuy != null && oldSell != null) {
                                originalPrices.put(item, new ObjectPrices[]{oldBuy, oldSell});
                            }
                        }

                        buyPriceField.set(item, new EcoBridgeDynamicPrice(plugin, item, itemId, true));
                        sellPriceField.set(item, new EcoBridgeDynamicPrice(plugin, item, itemId, false));
                    }
                    count++;
                }
            }
            LogUtil.info("UltimateShop 动态定价内核已注入 (" + count + " 个商品)。");
        } catch (Exception e) {
            LogUtil.error("价格注入失败", e);
        }
    }

    public static void revert() {
        if (originalPrices.isEmpty()) return;
        try {
            Field buyPriceField = ObjectItem.class.getDeclaredField("buyPrice");
            Field sellPriceField = ObjectItem.class.getDeclaredField("sellPrice");
            buyPriceField.setAccessible(true);
            sellPriceField.setAccessible(true);

            for (Map.Entry<ObjectItem, ObjectPrices[]> entry : originalPrices.entrySet()) {
                ObjectItem item = entry.getKey();
                ObjectPrices[] originals = entry.getValue();
                if (item != null) {
                    synchronized (item) {
                        buyPriceField.set(item, originals[0]);
                        sellPriceField.set(item, originals[1]);
                    }
                }
            }
            originalPrices.clear();
        } catch (Exception e) {
            LogUtil.error("还原价格逻辑时发生错误", e);
        }
    }

    public static class EcoBridgeDynamicPrice extends ObjectPrices {

        @SuppressWarnings("unused")
        private final EcoBridge plugin;
        private final String productId;
        @SuppressWarnings("unused")
        private final boolean isBuy;
        private final String currencyId;

        // --- 静态反射缓存 ---
        private static Constructor<TakeResult> takeResultCtor;
        private static Constructor<GiveResult> giveResultCtor;
        private static Field takeResultMapField;
        private static Field giveResultMapField;
        private static boolean reflectionReady = false;

        static {
            try {
                // 初始化 TakeResult
                try {
                    takeResultCtor = TakeResult.class.getDeclaredConstructor();
                } catch (NoSuchMethodException e) {
                    takeResultCtor = TakeResult.class.getDeclaredConstructor(boolean.class);
                }
                takeResultCtor.setAccessible(true);
                takeResultMapField = findField(TakeResult.class, "resultMap", "results", "map", "data");
                if (takeResultMapField != null) takeResultMapField.setAccessible(true);

                // 初始化 GiveResult
                try {
                    giveResultCtor = GiveResult.class.getDeclaredConstructor();
                } catch (NoSuchMethodException e) {
                    giveResultCtor = GiveResult.class.getDeclaredConstructor(boolean.class);
                }
                giveResultCtor.setAccessible(true);
                giveResultMapField = findField(GiveResult.class, "resultMap", "results", "map", "data");
                if (giveResultMapField != null) giveResultMapField.setAccessible(true);

                reflectionReady = true;
            } catch (Exception e) {
                LogUtil.error("EcoBridge 无法初始化 UltimateShop 反射钩子", e);
            }
        }

        private static Field findField(Class<?> clazz, String... potentialNames) {
            for (String name : potentialNames) {
                try {
                    return clazz.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {}
            }
            return null;
        }

        public EcoBridgeDynamicPrice(EcoBridge plugin, ObjectItem item, String productId, boolean isBuy) {
            super(new YamlConfiguration(), "EcoBridge", item, isBuy ? PriceMode.BUY : PriceMode.SELL);
            this.plugin = plugin;
            this.productId = productId;
            this.isBuy = isBuy;
            this.currencyId = plugin.getConfig().getString("economy.currency-id", "coins");
        }

        @Override
        public TakeResult take(Inventory inventory, Player player, int times, int amount, boolean simulation) {
            if (PricingManager.getInstance() == null) {
                return createTakeResult(false, new HashMap<>());
            }

            double unitPrice = PricingManager.getInstance().calculateBuyPrice(productId);
            double totalPrice = unitPrice * amount;

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("money", totalPrice);

            Currency currency = CoinsEngineAPI.getCurrency(currencyId);
            if (currency == null) {
                LogUtil.warnOnce("EcoBridge_Currency_404", "CoinsEngine 中未找到货币 ID: " + currencyId);
                return createTakeResult(false, resultMap);
            }

            double balance = CoinsEngineAPI.getBalance(player, currency);
            if (balance < totalPrice) {
                return createTakeResult(false, resultMap);
            }

            if (!simulation) {
                CoinsEngineAPI.removeBalance(player, currency, totalPrice);
                if (EconomicStateManager.getInstance() != null) {
                    EconomicStateManager.getInstance().recordPurchase(player, productId, amount);
                }
            }

            return createTakeResult(true, resultMap);
        }

        @Override
        public GiveResult give(Player player, int times, int amount) {
            if (isSimulationContext()) {
                double unitPrice = (PricingManager.getInstance() != null) ? 
                    PricingManager.getInstance().calculateSellPrice(productId) : 0.0;
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("money", unitPrice * amount);
                return createGiveResult(true, resultMap);
            }

            if (PricingManager.getInstance() == null) {
                return createGiveResult(false, new HashMap<>());
            }

            double unitPrice = PricingManager.getInstance().calculateSellPrice(productId);
            double totalPrice = unitPrice * amount;

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("money", totalPrice);

            Currency currency = CoinsEngineAPI.getCurrency(currencyId);
            if (currency == null) {
                LogUtil.warnOnce("EcoBridge_Currency_404", "CoinsEngine 中未找到货币 ID: " + currencyId);
                return createGiveResult(false, resultMap);
            }

            CoinsEngineAPI.addBalance(player, currency, totalPrice);
            if (EconomicStateManager.getInstance() != null) {
                EconomicStateManager.getInstance().recordSale(player, productId, amount);
            }

            return createGiveResult(true, resultMap);
        }

        // =========================================================================
        //  Robust Factory Methods (Reflection Only)
        // =========================================================================

        private TakeResult createTakeResult(boolean success, Map<String, Object> map) {
            if (!reflectionReady || takeResultCtor == null) {
                return null; // [Fix] 无法反射时返回 null (因为无法调用构造函数)
            }

            try {
                TakeResult result;
                if (takeResultCtor.getParameterCount() == 0) {
                    result = takeResultCtor.newInstance();
                } else {
                    result = takeResultCtor.newInstance(success);
                }

                if (takeResultMapField != null) {
                    takeResultMapField.set(result, map);
                }
                return result;
            } catch (Exception e) {
                LogUtil.errorOnce("EcoBridge_Reflect_Error", "创建 TakeResult 失败: " + e.getMessage());
                return null;
            }
        }

        private GiveResult createGiveResult(boolean success, Map<String, Object> map) {
            if (!reflectionReady || giveResultCtor == null) {
                return null;
            }

            try {
                GiveResult result;
                if (giveResultCtor.getParameterCount() == 0) {
                    result = giveResultCtor.newInstance();
                } else {
                    result = giveResultCtor.newInstance(success);
                }

                if (giveResultMapField != null) {
                    giveResultMapField.set(result, map);
                }
                return result;
            } catch (Exception e) {
                LogUtil.errorOnce("EcoBridge_Reflect_Error", "创建 GiveResult 失败: " + e.getMessage());
                return null;
            }
        }

        private boolean isSimulationContext() {
            try {
                return StackWalker.getInstance().walk(stream -> 
                    stream.anyMatch(frame -> {
                        String cls = frame.getClassName();
                        String mtd = frame.getMethodName();
                        return cls.contains("ModifyDisplayItem") || mtd.equals("modifyItem");
                    })
                );
            } catch (Throwable t) {
                for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                    if (element.getClassName().contains("ModifyDisplayItem")) return true;
                }
                return false;
            }
        }
    }
}