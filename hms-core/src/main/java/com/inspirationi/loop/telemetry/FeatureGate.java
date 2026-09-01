package com.inspirationi.loop.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature Gate — 连接 FeatureFlagService 到具体功能/工具的开关控制。
 * <p>
 * 集中管理所有 Feature Flag 的条件检查逻辑。
 * 提供统一的 gate 注册和查询机制。
 */
public class FeatureGate {

    private static final Logger log = LoggerFactory.getLogger(FeatureGate.class);

    /** 底层 Feature Flag 服务，gate 检查最终委托给它判断。 */
    private final FeatureFlagService flagService;

    /**
     * tool name → flag name 映射。
     * <p>
     * 并发安全：本类是单例 Bean，{@code registerToolGate} 是公开写入口，
     * 而 gate 检查与 {@code describe} 会并发遍历。
     */
    private final Map<String, String> toolGates = new ConcurrentHashMap<>();

    /** feature category → flag name 映射（并发安全，同 {@link #toolGates}）。 */
    private final Map<String, String> featureGates = new ConcurrentHashMap<>();

    /**
     * 创建 Feature Gate 并注册默认的工具级 / 功能级 gate。
     *
     * @param flagService 底层 Feature Flag 服务
     */
    public FeatureGate(FeatureFlagService flagService) {
        this.flagService = flagService;
        registerDefaults();
    }

    /** 注册内置的默认工具级 / 功能级 gate 映射。 */
    private void registerDefaults() {
        // 工具级 gate
        registerToolGate("enter_worktree", "WORKTREE_MODE");
        registerToolGate("exit_worktree", "WORKTREE_MODE");

        // 功能级 gate
        registerFeatureGate("server_mode", "DIRECT_CONNECT");
        registerFeatureGate("lsp", "LSP_INTEGRATION");
        registerFeatureGate("session_memory", "SESSION_MEMORY");
        registerFeatureGate("coordinator", "COORDINATOR_MODE");
        registerFeatureGate("plugin_marketplace", "PLUGIN_MARKETPLACE");
        registerFeatureGate("auto_compact", "AUTO_COMPACT");
        registerFeatureGate("metrics", "METRICS_COLLECTION");
        registerFeatureGate("voice", "VOICE_INPUT");
        registerFeatureGate("advanced_ui", "ADVANCED_UI");
    }

    /**
     * 注册工具级 gate。
     */
    public void registerToolGate(String toolName, String flagName) {
        toolGates.put(toolName, flagName);
    }

    /**
     * 注册功能级 gate。
     */
    public void registerFeatureGate(String featureName, String flagName) {
        featureGates.put(featureName, flagName);
    }

    /**
     * 检查工具是否启用。
     */
    public boolean isToolEnabled(String toolName) {
        String flagName = toolGates.get(toolName);
        if (flagName == null) return true; // 没注册 gate 的工具默认启用
        return flagService.isEnabled(flagName);
    }

    /**
     * 检查功能是否启用。
     */
    public boolean isFeatureEnabled(String featureName) {
        String flagName = featureGates.get(featureName);
        if (flagName == null) return true;
        return flagService.isEnabled(flagName);
    }

    /**
     * 获取所有已禁用的工具名称。
     */
    public Set<String> getDisabledTools() {
        Set<String> disabled = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : toolGates.entrySet()) {
            if (!flagService.isEnabled(entry.getValue())) {
                disabled.add(entry.getKey());
            }
        }
        return disabled;
    }

    /**
     * 获取所有已禁用的功能名称。
     */
    public Set<String> getDisabledFeatures() {
        Set<String> disabled = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : featureGates.entrySet()) {
            if (!flagService.isEnabled(entry.getValue())) {
                disabled.add(entry.getKey());
            }
        }
        return disabled;
    }

    /**
     * 生成人类可读的 gate 状态报告。
     */
    public String statusReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Feature Gates ===\n");

        if (!featureGates.isEmpty()) {
            sb.append("\nFeatures:\n");
            for (Map.Entry<String, String> entry : featureGates.entrySet()) {
                boolean enabled = flagService.isEnabled(entry.getValue());
                sb.append("  ").append(enabled ? "✓" : "✗").append(" ")
                        .append(entry.getKey()).append(" (").append(entry.getValue()).append(")\n");
            }
        }

        if (!toolGates.isEmpty()) {
            sb.append("\nTools:\n");
            for (Map.Entry<String, String> entry : toolGates.entrySet()) {
                boolean enabled = flagService.isEnabled(entry.getValue());
                sb.append("  ").append(enabled ? "✓" : "✗").append(" ")
                        .append(entry.getKey()).append(" (").append(entry.getValue()).append(")\n");
            }
        }

        return sb.toString();
    }

    public FeatureFlagService getFlagService() {
        return flagService;
    }
}
