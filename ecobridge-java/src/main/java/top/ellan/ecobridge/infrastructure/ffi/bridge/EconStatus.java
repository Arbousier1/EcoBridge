package top.ellan.ecobridge.infrastructure.ffi.bridge;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

/**
 * 跨语言通讯状态协议 (SSoT)
 */
public enum EconStatus {
    OK(0),
    NULL_POINTER(1),
    INVALID_LENGTH(2),
    INVALID_VALUE(3),
    NUMERIC_OVERFLOW(10),
    INTERNAL_ERROR(100),
    PANIC(101),
    FATAL(255);

    private final int code;

    EconStatus(int code) {
        this.code = code;
    }

    public int code() { return code; }

    public static EconStatus from(int code) {
        for (EconStatus s : values()) {
            if (s.code == code) return s;
        }
        if (EcoBridge.getInstanceOrNull() != null) {
            LogUtil.warn("收到未定义的 Native 状态码: " + code);
        }
        return INTERNAL_ERROR;
    }

    public boolean isOk() {
        return this == OK;
    }

    /**
     * 核心校验逻辑：如果状态不正常，根据严重程度执行决策
     */
    public void check(String context) {
        if (isOk()) return;

        String msg = "[Native Error] " + context + " failed with status: " + this.name();
        
        switch (this) {
            case PANIC, FATAL -> {
                LogUtil.error(msg + " | 系统将尝试进入降级模式。", null);
                // 这里可以触发插件的自动熔断逻辑
            }
            default -> LogUtil.warn(msg);
        }
    }
}