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

        // --- 2. 新控制器参数 (Predictive + Fuzzy + Sink/Faucet) ---
        healthy &= checkRange(config, "economy.control.predictive.horizon-seconds", 60.0, 1209600.0, 259200.0);
        healthy &= checkRange(config, "economy.control.lambda.min-multiplier", 0.1, 3.0, 0.6);
        healthy &= checkRange(config, "economy.control.lambda.max-multiplier", 0.2, 5.0, 2.2);
        healthy &= checkRange(config, "economy.recovery.floor-ratio-to-history", 0.05, 1.0, 0.55);
        healthy &= checkRange(config, "economy.recovery.activation-ratio-to-history", 0.1, 2.0, 0.78);
        healthy &= checkRange(config, "economy.recovery.target-ratio-to-history", 0.1, 2.0, 0.92);
        healthy &= checkRange(config, "economy.recovery.strength", 0.0, 1.0, 0.28);
        healthy &= checkRange(config, "economy.recovery.max-step-per-cycle", 0.0, 0.2, 0.03);
        healthy &= checkRange(config, "economy.player-market.quota.period-hours", 1, 720, 168);
        healthy &= checkRange(config, "economy.player-market.quota.base", 1.0, 100000.0, 64.0);
        healthy &= checkRange(config, "economy.player-market.quota.gamma-per-hour", 0.0, 100.0, 0.4);
        healthy &= checkRange(config, "economy.player-market.quota.global-cap", 1.0, 10000000.0, 4096.0);
        healthy &= checkRange(config, "economy.player-market.quota.share-mode.pool-base", 0.0, 100000000.0, 0.0);
        healthy &= checkRange(config, "economy.player-market.quota.share-mode.pool-per-online-player", 1.0, 1000000.0, 96.0);
        healthy &= checkRange(config, "economy.player-market.decay.delta", 0.01, 10.0, 0.8);
        healthy &= checkRange(config, "economy.player-market.decay.tau-days", 0.0, 365.0, 3.0);
        healthy &= checkRange(config, "economy.player-market.decay.window-days", 1, 365, 21);
        healthy &= checkRange(config, "economy.player-market.decay.min-multiplier", 0.01, 1.0, 0.10);
        healthy &= checkRange(config, "economy.player-market.indices.weekend-factor", 0.5, 1.5, 0.98);
        healthy &= checkRange(config, "economy.player-market.indices.holiday-factor", 0.5, 1.5, 0.95);
        healthy &= checkRange(config, "economy.player-market.indices.noise-stddev", 0.0, 0.5, 0.02);
        healthy &= checkRange(config, "economy.player-market.indices.epsilon-min", 0.1, 2.0, 0.85);
        healthy &= checkRange(config, "economy.player-market.indices.epsilon-max", 0.1, 2.0, 1.10);

        // --- 3. 基础市场参数 ---
        healthy &= checkRange(config, "economy.decay-rate", 0.0, 1.0, 0.05);
        healthy &= checkRange(config, "economy.default-lambda", 0.00001, 0.1, 0.002);
        healthy &= checkRange(config, "economy.tau", 0.1, 365.0, 7.0);
        healthy &= checkRange(config, "economy.sell-ratio", 0.0, 1.0, 0.5);

        // --- 4. 审计参数 ---
        healthy &= checkRange(config, "economy.audit-settings.base-tax-rate", 0.0, 1.0, 0.05);
        healthy &= checkRange(config, "economy.audit-settings.luxury-tax-rate", 0.0, 1.0, 0.1);

        // --- 5. 密钥安全审计 ---
        validateSecrets(config);

        // --- 6. 系统设置 ---
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
     * 密钥安全审计：检测明文密码是否为默认占位值。
     */
    private static void validateSecrets(FileConfiguration config) {
        String dbPassword = config.getString("database.password", "");
        if (dbPassword.equals("change_me_to_real_password") || dbPassword.isEmpty()) {
            LogUtil.warn("security: database.password 未配置！请设置 ECOBRIDGE_DB_PASSWORD 环境变量或修改 config.yml");
        }

        boolean redisEnabled = config.getBoolean("redis.enabled", false);
        if (redisEnabled) {
            String redisPassword = config.getString("redis.password", "");
            if (redisPassword != null && redisPassword.isEmpty()) {
                LogUtil.info("security: Redis 密码为空，若需要认证请设置 ECOBRIDGE_REDIS_PASSWORD 环境变量");
            }
        }
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
