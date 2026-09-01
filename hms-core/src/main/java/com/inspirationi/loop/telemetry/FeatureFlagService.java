package com.inspirationi.loop.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature Flag 服务 —— 纯内存管理，不读写磁盘文件。
 * <p>
 * SDK 场景下，feature flags 完全由 API 调用方通过
 * {@link #setFlag(String, Object)} 和环境变量覆盖控制。
 * <p>
 * 环境变量覆盖规则：{@code HMS_CORE_FF_<FLAG_NAME>}（最高优先级）。
 */
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    /** flag 默认值 —— 通用 SDK 场景的功能开关，不包含 CLI 专属概念 */
    private static final Map<String, Object> DEFAULTS = Map.ofEntries(
            Map.entry("SESSION_MEMORY", true),
            Map.entry("PLUGIN_SUPPORT", true),
            Map.entry("METRICS_COLLECTION", true),
            Map.entry("REQUEST_TRACING", true),
            Map.entry("ASYNC_CALLBACKS", true)
    );

    /** 运行时 flag 存储（线程安全），通过 setFlag/setFlags 写入，优先级高于默认值但低于环境变量。 */
    private final ConcurrentHashMap<String, Object> flags = new ConcurrentHashMap<>();

    public FeatureFlagService() {
    }

    /**
     * 获取布尔型 flag。
     */
    public boolean isEnabled(String flagName) {
        // 环境变量覆盖（最高优先级）
        String envKey = "HMS_CORE_FF_" + flagName;
        String envVal = System.getenv(envKey);
        if (envVal != null) {
            return "true".equalsIgnoreCase(envVal) || "1".equals(envVal);
        }

        // 运行时 flag 值（优先级低于环境变量，高于默认值）
        Object value = flags.get(flagName);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());

        // 默认值
        Object def = DEFAULTS.get(flagName);
        if (def instanceof Boolean b) return b;
        return false;
    }

    /**
     * 获取字符串型 flag。
     */
    public String getString(String flagName, String defaultValue) {
        String envKey = "HMS_CORE_FF_" + flagName;
        String envVal = System.getenv(envKey);
        if (envVal != null) return envVal;

        // 运行时 flag 值（优先级低于环境变量，高于默认值）
        Object value = flags.get(flagName);
        if (value != null) return value.toString();

        Object def = DEFAULTS.get(flagName);
        if (def != null) return def.toString();

        return defaultValue;
    }

    /**
     * 获取数字型 flag。
     */
    public long getNumber(String flagName, long defaultValue) {
        String envKey = "HMS_CORE_FF_" + flagName;
        String envVal = System.getenv(envKey);
        if (envVal != null) {
            try {
                return Long.parseLong(envVal);
            } catch (NumberFormatException ignored) {}
        }

        // 运行时 flag 值（优先级低于环境变量，高于默认值）
        Object value = flags.get(flagName);
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {}
        }

        Object def = DEFAULTS.get(flagName);
        if (def instanceof Number n) return n.longValue();

        return defaultValue;
    }

    /**
     * 设置 flag 值（运行时）。
     */
    public void setFlag(String flagName, Object value) {
        flags.put(flagName, value);
    }

    /**
     * 获取所有 flag 及其当前值。
     */
    public Map<String, Object> getAllFlags() {
        Map<String, Object> result = new ConcurrentHashMap<>(DEFAULTS);
        result.putAll(flags);
        return result;
    }

    /**
     * 批量设置 flags（用于 SDK 调用方初始化）。
     */
    public void setFlags(Map<String, Object> flagValues) {
        if (flagValues != null) {
            flags.putAll(flagValues);
        }
    }

    /**
     * 清除所有运行时 flag（回退到默认值）。
     */
    public void reset() {
        flags.clear();
    }
}