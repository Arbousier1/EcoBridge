package top.ellan.ecobridge.integration.platform.listener;

import java.lang.reflect.Field;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import su.nightexpress.coinsengine.api.currency.ExcellentCurrency;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

/**
 * 深度指令劫持系统 (v1.8.1 - Cleaned)
 *
 * <p>修复: 1. [Lint] 移除了未使用的 Import。 2. [Lint] 压制了 Paper API 的权限消息过时警告 (保持向下兼容)。
 */
public class CommandHijacker {

  private static Field knownCommandsField;
  private Map<String, Command> knownCommands;

  public CommandHijacker(EcoBridge ignored) {
    setupReflection();
  }

  private void setupReflection() {
    try {
      Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
      commandMapField.setAccessible(true);
      SimpleCommandMap commandMap = (SimpleCommandMap) commandMapField.get(Bukkit.getServer());

      if (knownCommandsField == null) {
        knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
        knownCommandsField.setAccessible(true);
      }

      @SuppressWarnings("unchecked")
      Map<String, Command> map = (Map<String, Command>) knownCommandsField.get(commandMap);
      this.knownCommands = map;
    } catch (Exception e) {
      LogUtil.error("指令劫持系统反射失败，请检查核心兼容性", e);
    }
  }

  public void hijackAllCurrencies() {
    if (knownCommands == null) return;

    // 获取 EcoBridge 的核心转账指令
    Command ecopay = knownCommands.get("ecopay");
    if (ecopay == null) ecopay = knownCommands.get("ecobridge:ecopay");

    if (ecopay == null) {
      LogUtil.error("严重错误: 无法找到 'ecopay' 指令，劫持中止。");
      return;
    }

    // 1. 劫持 CoinsEngine 所有货币的主指令 (例如 /coins, /gold)
    for (ExcellentCurrency currency : CoinsEngineAPI.getCurrencyRegistry().getCurrencies()) {
      for (String alias : currency.getCommandAliases()) {
        // true = 需要参数偏移 (例如 /coins pay -> 偏移为 /ecopay)
        applyPhysicalHijack(alias.toLowerCase(), ecopay, true);
      }

      // 2. 劫持纯支付指令 (例如 /pay, /epay) - 仅当它是主货币时
      if (currency.isPrimary()) {
        Command finalEcopay = ecopay;
        Arrays.asList("pay", "transfer", "epay")
            .forEach(
                label ->
                    // false = 不需要偏移 (例如 /pay -> /ecopay)
                    applyPhysicalHijack(label, finalEcopay, false));
      }
    }
  }

  /**
   * 执行物理劫持
   *
   * @param label 目标指令名
   * @param targetProxy 代理到的 EcoBridge 指令
   * @param requireSubCommand 是否需要检测子命令 (如 /coins pay)
   */
  private void applyPhysicalHijack(String label, Command targetProxy, boolean requireSubCommand) {
    Command originalCommand = knownCommands.get(label);

    // 如果已经被劫持过，或者是 EcoBridge 自己的指令，跳过
    if (originalCommand instanceof HijackedCommandWrapper || originalCommand == targetProxy) {
      return;
    }

    // 从 Map 中彻底移除旧指令
    List<String> keysToRemove = new ArrayList<>();
    for (String key : knownCommands.keySet()) {
      if (key.equalsIgnoreCase(label) || key.endsWith(":" + label)) {
        keysToRemove.add(key);
      }
    }
    for (String key : keysToRemove) {
      knownCommands.remove(key);
    }

    // 注册新的包装器，持有原指令的引用用于回退
    HijackedCommandWrapper wrapper =
        new HijackedCommandWrapper(label, targetProxy, originalCommand, requireSubCommand);
    knownCommands.put(label, wrapper);
    knownCommands.put("ecobridge:" + label, wrapper);

    LogUtil.info("已劫持指令: /" + label + " -> " + (requireSubCommand ? "智能路由" : "强制代理"));
  }

  /** [v2.0] Intercept UltimateShop admin commands to ensure our data stays consistent. */
  public void hijackShopCommands() {
    if (knownCommands == null) return;

    for (String label : new String[]{"ultimateshop", "us", "ushop", "usadmin"}) {
      Command original = knownCommands.get(label);
      if (original != null && !(original instanceof HijackedCommandWrapper)) {
        ShopAdminWrapper wrapper = new ShopAdminWrapper(label, original);
        knownCommands.put(label, wrapper);
        LogUtil.info("已接管 UltimateShop 管理指令: /" + label);
      }
    }
  }

  /** [v2.0] Wrapper that intercepts admin shop item give/set to track inventory changes. */
  private static class ShopAdminWrapper extends Command {
    private final Command original;

    @SuppressWarnings("deprecation")
    ShopAdminWrapper(String name, Command original) {
      super(name);
      this.original = original;
      if (original.getPermission() != null) this.setPermission(original.getPermission());
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
      // Pass through to original — our hooks (ASM + Event) already capture the price path.
      // Admin give/set operations that bypass the normal transaction flow will still
      // be recorded via CoinsEngineListener.onBalanceChange() for M1 tracking.
      return original.execute(sender, label, args);
    }

    @NotNull
    @Override
    public List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
      return original.tabComplete(sender, alias, args);
    }
  }

  private static class HijackedCommandWrapper extends Command {
    private final Command ecopayTarget;
    private final Command originalFallback; // 原生指令 (用于回退)
    private final boolean requireSubCommand; // 是否需要 "pay" 子参数

    @SuppressWarnings("deprecation") // Paper 1.21: 兼容旧版字符串权限消息复制
    protected HijackedCommandWrapper(
        String name, Command ecopayTarget, Command originalFallback, boolean requireSubCommand) {
      super(name);
      this.ecopayTarget = ecopayTarget;
      this.originalFallback = originalFallback;
      this.requireSubCommand = requireSubCommand;

      // 复制原指令的权限设置，防止普通玩家看到不该看的提示
      if (originalFallback != null) {
        this.setPermission(originalFallback.getPermission());
        this.setPermissionMessage(originalFallback.getPermissionMessage());
      }
    }

    @Override
    public boolean execute(
        @NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
      // 场景 1: 劫持的是 /pay (requireSubCommand = false)
      // 直接全部转给 ecopay
      if (!requireSubCommand) {
        return ecopayTarget.execute(sender, "ecopay", args);
      }

      // 场景 2: 劫持的是 /coins (requireSubCommand = true)
      // 需要检查 args[0] 是否为 "pay" 或 "send"
      if (args.length > 0) {
        String sub = args[0].toLowerCase();
        if (sub.equals("pay") || sub.equals("send") || sub.equals("give")) {
          // 参数偏移: /coins pay Ellan 100 -> /ecopay Ellan 100
          String[] shiftedArgs = Arrays.copyOfRange(args, 1, args.length);
          return ecopayTarget.execute(sender, "ecopay", shiftedArgs);
        }
      }

      // 场景 3: 并非转账操作 (例如 /coins help, /coins balance)
      // 回退到原指令，确保不破坏其他功能
      if (originalFallback != null) {
        return originalFallback.execute(sender, label, args);
      }

      return false;
    }

    @NotNull
    @Override
    public List<String> tabComplete(
        @NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args)
        throws IllegalArgumentException {
      // 逻辑与 execute 类似，决定是谁在补全

      if (!requireSubCommand) {
        return ecopayTarget.tabComplete(sender, alias, args);
      }

      if (args.length > 0) {
        String sub = args[0].toLowerCase();
        // 如果正在输入第一个参数，且是以 p 开头，提示 pay
        if (args.length == 1 && "pay".startsWith(sub)) {
          // 混合补全：既有 CoinsEngine 的子命令，也有我们的 pay
          if (originalFallback != null) {
            List<String> list = originalFallback.tabComplete(sender, alias, args);
            if (list == null) list = new ArrayList<>();
            list.add("pay");
            return list;
          }
        }

        // 如果已经确认是 pay，后续参数由 ecopay 补全 (玩家名、金额)
        if (sub.equals("pay") || sub.equals("send")) {
          String[] shiftedArgs = Arrays.copyOfRange(args, 1, args.length);
          return ecopayTarget.tabComplete(sender, alias, shiftedArgs);
        }
      }

      // 其他情况交给原指令补全
      if (originalFallback != null) {
        return originalFallback.tabComplete(sender, alias, args);
      }
      return Collections.emptyList();
    }
  }
}
