package com.inspirationi.loop.agent;

import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.permission.PermissionTypes;

import java.util.Map;

/**
 * 智能体定义 —— 不可变蓝图，多智能体差异化的载体。
 * <p>
 * 差异集中在三处：{@link #basePrompt() 提示词}、{@link #toolSelect() 工具集}、
 * {@link #permissionSettings() 权限}。模型不在此列 —— 全进程共享同一个 ChatModel，
 * 因此本定义里没有任何模型相关字段。
 * <p>
 * 用 record 而非可变类：定义被 {@code AgentRegistry} 的并发 map 持有，不可变保证
 * 「活会话运行途中定义被就地改写」这类歧义被结构性排除。需要改变时构造新定义并
 * 通过 {@code registerOrReplace} 替换。
 *
 * @param id                 唯一标识（必填、非空、稳定）—— 注册、查找、会话归属都靠它
 * @param name               展示名（空则回落 id）
 * @param description        给模型看的用途说明（供 ListAgents 输出，帮助模型选择委派目标）
 * @param basePrompt         基座提示词 —— 三级拼接的第一级（取代旧的全局提示词）
 * @param toolSelect         工具选择器（空则全部工具）
 * @param permissionSettings 本 Agent 的权限设置；{@code null} 表示装配时新建私有实例
 *                           （默认 Agent 会显式传入全局 Bean）
 * @param permissionMode     初始权限模式；{@code null} 表示沿用 settings 的当前模式
 * @param memory             记忆规格（空则关闭记忆）
 * @param activationMode     被动 / 主动（空则被动）
 * @param heartbeat          心跳规格，仅主动 Agent 使用（被动时归一化为 {@code null}）
 * @param maxSessions        会话数上限；{@code <=0} 表示用全局默认
 * @param maxIterations      单轮最大迭代；{@code <=0} 表示用全局默认
 * @param idleTimeoutSeconds 空闲回收阈值；{@code <=0} 表示用全局默认
 * @param delegatable        是否允许被其他 Agent 委派调用
 * @param tags               元数据标签
 */
public record AgentDefinition(
        String id,
        String name,
        String description,
        String basePrompt,
        ToolSelect toolSelect,
        PermissionSettings permissionSettings,
        PermissionTypes.PermissionMode permissionMode,
        MemorySpec memory,
        ActivationMode activationMode,
        HeartbeatSpec heartbeat,
        int maxSessions,
        int maxIterations,
        long idleTimeoutSeconds,
        boolean delegatable,
        Map<String, String> tags
) {

    /** 紧凑构造器：归一化可空字段，并丢弃被动 Agent 的无意义心跳配置。 */
    public AgentDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("agent id must not be blank");
        }
        name = (name == null || name.isBlank()) ? id : name;
        toolSelect = toolSelect == null ? ToolSelect.all() : toolSelect;
        memory = memory == null ? MemorySpec.disabled() : memory;
        activationMode = activationMode == null ? ActivationMode.PASSIVE : activationMode;
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        if (activationMode == ActivationMode.PASSIVE) {
            heartbeat = null;
        } else if (heartbeat == null) {
            heartbeat = HeartbeatSpec.defaults();
        }
    }

    /** 开始构建一个定义。 */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** 是否主动 Agent。 */
    public boolean isProactive() {
        return activationMode == ActivationMode.PROACTIVE;
    }

    /** 复制一份、仅替换基座提示词。 */
    public AgentDefinition withBasePrompt(String newBasePrompt) {
        return new AgentDefinition(id, name, description, newBasePrompt, toolSelect,
                permissionSettings, permissionMode, memory, activationMode, heartbeat,
                maxSessions, maxIterations, idleTimeoutSeconds, delegatable, tags);
    }

    /** 复制一份、仅替换权限模式。 */
    public AgentDefinition withPermissionMode(PermissionTypes.PermissionMode newMode) {
        return new AgentDefinition(id, name, description, basePrompt, toolSelect,
                permissionSettings, newMode, memory, activationMode, heartbeat,
                maxSessions, maxIterations, idleTimeoutSeconds, delegatable, tags);
    }

    /** 复制一份、仅替换激活模式与心跳。 */
    public AgentDefinition withActivation(ActivationMode mode, HeartbeatSpec spec) {
        return new AgentDefinition(id, name, description, basePrompt, toolSelect,
                permissionSettings, permissionMode, memory, mode, spec,
                maxSessions, maxIterations, idleTimeoutSeconds, delegatable, tags);
    }

    /** 定义构建器 —— 未显式设置的字段取中性默认值。 */
    public static final class Builder {
        private final String id;
        private String name;
        private String description;
        private String basePrompt;
        private ToolSelect toolSelect = ToolSelect.all();
        private PermissionSettings permissionSettings;
        private PermissionTypes.PermissionMode permissionMode;
        private MemorySpec memory = MemorySpec.disabled();
        private ActivationMode activationMode = ActivationMode.PASSIVE;
        private HeartbeatSpec heartbeat;
        private int maxSessions = -1;
        private int maxIterations = -1;
        private long idleTimeoutSeconds = -1;
        private boolean delegatable = true;
        private Map<String, String> tags = Map.of();

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder basePrompt(String basePrompt) {
            this.basePrompt = basePrompt;
            return this;
        }

        public Builder toolSelect(ToolSelect toolSelect) {
            this.toolSelect = toolSelect;
            return this;
        }

        /** {@link #toolSelect} 的别名，读起来更顺。 */
        public Builder tools(ToolSelect toolSelect) {
            return toolSelect(toolSelect);
        }

        public Builder permissionSettings(PermissionSettings permissionSettings) {
            this.permissionSettings = permissionSettings;
            return this;
        }

        public Builder permissionMode(PermissionTypes.PermissionMode permissionMode) {
            this.permissionMode = permissionMode;
            return this;
        }

        public Builder memory(MemorySpec memory) {
            this.memory = memory;
            return this;
        }

        public Builder activationMode(ActivationMode activationMode) {
            this.activationMode = activationMode;
            return this;
        }

        /** 声明为主动 Agent 并指定心跳规格。 */
        public Builder proactive(HeartbeatSpec heartbeat) {
            this.activationMode = ActivationMode.PROACTIVE;
            this.heartbeat = heartbeat;
            return this;
        }

        public Builder heartbeat(HeartbeatSpec heartbeat) {
            this.heartbeat = heartbeat;
            return this;
        }

        public Builder maxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder idleTimeoutSeconds(long idleTimeoutSeconds) {
            this.idleTimeoutSeconds = idleTimeoutSeconds;
            return this;
        }

        public Builder delegatable(boolean delegatable) {
            this.delegatable = delegatable;
            return this;
        }

        public Builder tag(String key, String value) {
            var next = new java.util.LinkedHashMap<>(tags);
            next.put(key, value);
            this.tags = next;
            return this;
        }

        public AgentDefinition build() {
            return new AgentDefinition(id, name, description, basePrompt, toolSelect,
                    permissionSettings, permissionMode, memory, activationMode, heartbeat,
                    maxSessions, maxIterations, idleTimeoutSeconds, delegatable, tags);
        }
    }
}
