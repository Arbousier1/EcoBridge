package top.ellan.ecobridge.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.manager.TransferManager;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能转账指令 (TransferCommand v0.6.1)
 * 职责：指令解析、权限验证、Tab 补全，并驱动 TransferManager 进入风控审计流。
 */
public class TransferCommand implements TabExecutor {

    private static final String PERMISSION = "ecobridge.command.transfer";

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {

        // 1. 权限与发送者类型校验
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该指令仅限玩家在游戏内执行。"));
            return true;
        }

        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>权限不足！ <gray>您需要: <yellow>" + PERMISSION));
            return true;
        }

        // 2. 参数长度校验
        if (args.length < 2) {
            sendUsage(player, label);
            return true;
        }

        // 3. 目标玩家存在性与在线状态校验
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: <gray>目标玩家 <yellow>" + args[0] + " <gray>不在线。"));
            return true;
        }

        // 4. 自我转账逻辑拦截
        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>禁止操作: <gray>您无法向自己发起转账。"));
            return true;
        }

        // 5. 金额合法性校验 (在调用 Manager 前执行初步过滤)
        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount < 0.01) {
                player.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>金额异常: <gray>转账金额必须大于 <yellow>0.01"));
                return true;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>非法输入: <gray>请输入正确的数字金额。"));
            return true;
        }

        // 6. 视觉反馈：立即告知玩家进入审计阶段
        player.sendMessage(EcoBridge.getMiniMessage().deserialize(
        "<gray>[<blue>EcoBridge</blue>] <italic>正在启动智能审计，请稍候...</italic>"
    ));

        // 7. 委托逻辑层处理 (Rust 审计 + 异步落盘)
        // 注意：TransferManager 内部应使用虚拟线程处理，不阻塞主线程
        TransferManager.getInstance().initiateTransfer(player, target, amount);

        return true;
    }

    /**
     * 实现 Tab 补全逻辑
     */
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(name -> name.toLowerCase().startsWith(input))
            .collect(Collectors.toList());
        }
        if (args.length == 2) {
            return List.of("10", "100", "1000"); // 提供金额建议
        }
        return Collections.emptyList();
    }

    private void sendUsage(Player player, String label) {
        player.sendMessage(EcoBridge.getMiniMessage().deserialize(
        "<gradient:aqua:blue><b>EcoBridge 智能结算系统</b></gradient>\n" +
        "<gray>用法: <yellow>/" + label + " <玩家> <金额>"
    ));
    }
}
