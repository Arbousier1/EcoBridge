package top.ellan.ecobridge.infrastructure;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

/**
 * Resolves sensitive configuration values with environment-variable precedence.
 * Environment variables take priority over config.yml entries, so credentials
 * never need to be stored in plaintext on disk.
 */
public final class SecurityConfig {

    private SecurityConfig() {}

    /**
     * Resolve a password or secret with the following precedence:
     * 1. Environment variable {@code envVar}
     * 2. config.yml path {@code configPath}
     * 3. {@code defaultValue} if neither is set
     *
     * Logs a warning when the value matches a known placeholder default.
     */
    public static String resolvePassword(String configPath, String envVar, String defaultValue) {
        String fromEnv = System.getenv(envVar);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }

        EcoBridge plugin = EcoBridge.getInstanceOrNull();
        if (plugin != null) {
            String fromConfig = plugin.getConfig().getString(configPath, null);
            if (fromConfig != null && !fromConfig.isEmpty()) {
                if (isPlaceholder(fromConfig)) {
                    LogUtil.warn(
                        "security: " + configPath + " is still set to a placeholder default. "
                        + "Set the " + envVar + " environment variable or update config.yml."
                    );
                }
                return fromConfig;
            }
        }

        if (defaultValue != null && isPlaceholder(defaultValue)) {
            LogUtil.warn(
                "security: no value configured for " + configPath + ". "
                + "Set the " + envVar + " environment variable or update config.yml."
            );
        }
        return defaultValue != null ? defaultValue : "";
    }

    private static boolean isPlaceholder(String value) {
        return value.equals("change_me_to_real_password")
            || value.equals("changeme")
            || value.equals("password");
    }
}
