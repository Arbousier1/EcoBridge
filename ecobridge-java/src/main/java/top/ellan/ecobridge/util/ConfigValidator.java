package top.ellan.ecobridge.util;

import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;

/**
 * 经济内核参数校验器 (ConfigValidator v1.0.1)
 * 职责：执行物理与宏观经济参数的边界审计，确保系统在安全数值区间运行。
 * 更新日志：
 * 1. 移除未使用导入 java.util.function.Consumer。
 * 2. 优化纠错日志显示。
 */
public class ConfigValidator {

    /**
     * 执行全量参数校验
     * @return 如果存在致命配置错误返回 false，目前主要执行自动纠正并返回 true
     */
    public static boolean validate(EcoBridge plugin) {
        FileConfiguration config = plugin.getConfig();
        boolean healthy = true;

        LogUtil.info("正在执行经济内核物理参数审计...");

        // --- 1. 宏观调控参数 (Macro) ---
        healthy &= checkRange(config, "economy.macro.target-velocity", 0.001, 10.0, 0.05);
        healthy &= checkRange(config, "economy.macro.capacity-per-user", 1.0, 1000000.0, 5000.0);
        healthy &= checkRange(config, "economy.macro.heat-sensitivity", 0.01, 2.0, 0.5);
        healthy &= checkRange(config, "economy.macro.panic-threshold", 1.0, 1000.0, 50.0);

        // --- 2. PID 控制器参数 (核心物理参数) ---
        healthy &= checkRange(config, "economy.pid.kp", 0.0, 5.0, 0.5);
        healthy &= checkRange(config, "economy.pid.ki", 0.0, 1.0, 0.1);
        healthy &= checkRange(config, "economy.pid.kd", 0.0, 2.0, 0.05);
        healthy &= checkRange(config, "economy.pid.lambda", 0.0, 1.0, 0.01);

        // --- 3. 基础市场参数 ---
        healthy &= checkRange(config, "economy.decay-rate", 0.0, 1.0, 0.05);
        healthy &= checkRange(config, "economy.default-lambda", 0.00001, 0.1, 0.002);
        healthy &= checkRange(config, "economy.tau", 0.1, 365.0, 7.0);
        healthy &= checkRange(config, "economy.sell-ratio", 0.0, 1.0, 0.5);

        // --- 4. 审计参数 ---
        healthy &= checkRange(config, "economy.audit-settings.base-tax-rate", 0.0, 1.0, 0.05);
        healthy &= checkRange(config, "economy.audit-settings.luxury-tax-rate", 0.0, 1.0, 0.1);

        // --- 5. 系统设置 ---
        healthy &= checkRange(config, "system.log-sample-rate", 0, 100, 100);

        if (!healthy) {
            LogUtil.warn("部分参数不合法，内核已自动重置为安全默认值。请检查并校准 config.yml。");
            plugin.saveConfig(); // 物理保存修正后的参数
        } else {
            LogUtil.info("物理参数审计通过，内核状态：健康。");
        }

        return true;
    }

    /**
     * 浮点型范围检查并纠正
     */
    private static boolean checkRange(FileConfiguration config, String path, double min, double max, double def) {
        if (!config.contains(path)) {
            config.set(path, def);
            return false;
        }

        double val = config.getDouble(path);
        if (val < min || val > max) {
            LogUtil.error("配置校验失败: [" + path + "] 的值 " + val + " 超出安全范围 [" + min + " - " + max + "]");
            config.set(path, def);
            return false;
        }
        return true;
    }

    /**
     * 整型范围检查并纠正
     */
    private static boolean checkRange(FileConfiguration config, String path, int min, int max, int def) {
        int val = config.getInt(path);
        if (val < min || val > max) {
            LogUtil.error("配置校验失败: [" + path + "] 的值 " + val + " 超出安全范围 [" + min + " - " + max + "]");
            config.set(path, def);
            return false;
        }
        return true;
    }
}