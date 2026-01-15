package top.ellan.ecobridge.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import su.nightexpress.coinsengine.api.currency.Currency;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.manager.TransferManager;
import top.ellan.ecobridge.util.LogUtil;

import java.util.*;

/**
 * 深度指令接管器 (V0.7.6 API适配版)
 * 职责：拦截并接管所有经济相关的转账指令，转发至 EcoBridge 审计系统。
 * 修复：
 * 1. 修复 CoinsEngine API 调用错误 (getMainCurrency -> isPrimary)。
 * 2. 移除未使用的 plugin 字段。
 */
public class CommandInterceptor implements Listener {

    // [修复]: 移除未使用的 plugin 字段，消除 Warning
    // private final EcoBridge plugin; 
    
    // 缓存：指令别名 -> 对应的货币实例 (若为 null 则代表通用指令)
    private final Map<String, Currency> interceptMap = new HashMap<>();

    public CommandInterceptor(EcoBridge plugin) {
        // this.plugin = plugin; // 不再赋值
        reloadCache();
    }

    public void reloadCache() {
        interceptMap.clear();
        
        if (CoinsEngineAPI.getCurrencyRegistry() == null) {
            LogUtil.warn("CoinsEngine 注册表尚未就绪，指令拦截器暂缓加载。");
            return;
        }

        // 1. 加载 CoinsEngine 定义的所有货币指令别名
        CoinsEngineAPI.getCurrencyRegistry().getCurrencies().forEach(currency -> {
            Arrays.stream(currency.getCommandAliases())
                  .map(String::toLowerCase)
                  .forEach(alias -> interceptMap.put(alias, currency));
        });

        // 2. 加载通用指令 (如 /pay, /transfer) -> 映射为 null
        List<String> commonCmds = Arrays.asList("pay", "transfer", "epay", "balance", "money");
        commonCmds.forEach(cmd -> interceptMap.put(cmd, null));
        
        LogUtil.info("指令拦截器已就绪，监控指令数: " + interceptMap.size());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String rawMessage = event.getMessage().substring(1); 
        String[] parts = rawMessage.split("\\s+");
        
        if (parts.length < 1) return;

        // 剥离命名空间
        String fullLabel = parts[0].toLowerCase();
        String label = fullLabel.contains(":") ? fullLabel.split(":")[1] : fullLabel;

        if (!interceptMap.containsKey(label)) {
            return;
        }

        Currency currency = interceptMap.get(label);
        boolean isTransfer = false;
        int targetIdx = -1;
        int amountIdx = -1;

        // --- 场景判定 ---
        
        // 场景 A: 通用指令
        if (currency == null) {
            if (label.equals("pay") || label.equals("transfer") || label.equals("epay")) {
                isTransfer = true;
                targetIdx = 1;
                amountIdx = 2;
                // [关键修复]: 使用 Stream 查找 isPrimary() 为 true 的货币
                currency = CoinsEngineAPI.getCurrencyRegistry().getCurrencies().stream()
                        .filter(Currency::isPrimary)
                        .findFirst()
                        .orElse(null);
            }
        } 
        // 场景 B: 特定货币指令
        else {
            if (parts.length > 1) {
                String subCmd = parts[1].toLowerCase();
                if (subCmd.equals("pay") || subCmd.equals("send") || subCmd.equals("give")) {
                    isTransfer = true;
                    targetIdx = 2;
                    amountIdx = 3;
                }
            }
        }

        if (!isTransfer) return;

        event.setCancelled(true);
        Player sender = event.getPlayer();

        if (parts.length <= amountIdx) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
                "<yellow>EcoBridge 用法: /" + label + (interceptMap.get(label) == null ? "" : " pay") + " <玩家> <金额>"
            ));
            return;
        }

        if (currency == null) {
             sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: 系统未配置主货币 (isPrimary=true)。"));
             return;
        }

        handleTransfer(sender, currency, parts[targetIdx], parts[amountIdx]);
    }

    private void handleTransfer(Player sender, Currency currency, String targetName, String amountStr) {
        if (sender.getName().equalsIgnoreCase(targetName)) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: 无法对自己转账。"));
            return;
        }

        Player receiver = Bukkit.getPlayer(targetName);
        if (receiver == null) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: 目标玩家不在线。"));
            return;
        }

        try {
            double amount = Double.parseDouble(amountStr);
            
            if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
                 sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: 无效的金额。"));
                 return;
            }

            double minAmount = currency.getMinTransferAmount();
            if (minAmount > 0 && amount < minAmount) {
                sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: 转账金额低于最小限制 (" + minAmount + ")"));
                return;
            }

            TransferManager.getInstance().initiateTransfer(sender, receiver, amount);

        } catch (NumberFormatException e) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: 金额必须为纯数字。"));
        }
    }
}