package com.inspirationi.loop.permission;

import com.inspirationi.loop.tool.Tool;

import java.util.List;

/**
 * 权限管理类型定义 —— 通用 Web SDK 场景的权限模型。
 * <p>
 * 权限模式基于工具风险等级评估，而非硬编码的工具名称列表。
 * 适用于任何场景（编码助手、客服系统、数据库操作、审批流等）。
 */
public final class PermissionTypes {

    private PermissionTypes() {}

    /** 权限行为 */
    public enum PermissionBehavior {
        ALLOW,  // 允许执行
        DENY,   // 拒绝执行
        ASK     // 需要用户确认
    }

    /**
     * 权限模式 —— 基于风险等级的通用权限控制。
     * <p>
     * 每种模式定义了一个"自动放行"的最高风险等级，
     * 超过该等级的工具调用将被拒绝或需确认。
     */
    public enum PermissionMode {
        /**
         * 严格模式 —— 只允许只读操作。
         * 适用于：代码分析、架构审查、安全审计等纯读场景。
         * 等效于旧的 PLAN 模式（但去除了 PLAN.md 等文件编辑特例）。
         */
        STRICT,

        /**
         * 安全模式 —— 自动放行 READ_ONLY + LOW 风险等级。
         * 适用于：需要轻度副作用但拒绝高风险操作的场景（如客服系统）。
         */
        SAFE,

        /**
         * 默认模式 —— READ_ONLY + LOW + MEDIUM 自动放行，HIGH+ 需确认。
         * 适用于：日常交互场景（默认）。
         */
        DEFAULT,

        /**
         * 信任模式 —— 仅 CRITICAL 风险等级需确认。
         * 适用于：高度信任的内部系统集成。
         */
        TRUSTED,

        /**
         * 旁路模式 —— 跳过所有权限检查。
         * 适用于：自动化脚本（需谨慎使用）。
         */
        BYPASS
    }

    /**
     * 将权限模式映射为"自动放行"的最高风险等级。
     */
    public static Tool.RiskLevel autoAllowUpTo(PermissionMode mode) {
        return switch (mode) {
            case STRICT  -> Tool.RiskLevel.READ_ONLY;
            case SAFE    -> Tool.RiskLevel.LOW;
            case DEFAULT -> Tool.RiskLevel.MEDIUM;
            case TRUSTED -> Tool.RiskLevel.HIGH;
            case BYPASS  -> Tool.RiskLevel.CRITICAL; // 实际上全部放行
        };
    }

    /**
     * 判断指定风险等级在给定模式下是否自动允许。
     */
    public static boolean isAutoAllowed(Tool.RiskLevel toolRisk, PermissionMode mode) {
        if (mode == PermissionMode.BYPASS) return true;
        return toolRisk.ordinal() <= autoAllowUpTo(mode).ordinal();
    }

    /**
     * 权限规则 —— 定义工具和命令模式的权限行为。
     * <p>
     * 示例：
     * <ul>
     *   <li>{@code PermissionRule("Bash", "npm:*", ALLOW)} — 允许所有 npm 命令</li>
     *   <li>{@code PermissionRule("Bash", "rm -rf:*", DENY)} — 拒绝 rm -rf</li>
     *   <li>{@code PermissionRule("DatabaseTool", "*", ALLOW)} — 允许所有数据库操作</li>
     * </ul>
     *
     * @param toolName    工具名称
     * @param ruleContent 规则内容，支持通配符 *（如 "SELECT:*", "DROP:*", "*"）
     * @param behavior    权限行为
     */
    public record PermissionRule(
            String toolName,
            String ruleContent,
            PermissionBehavior behavior
    ) {
        /** 匹配整个工具（无命令模式限制） */
        public static PermissionRule forTool(String toolName, PermissionBehavior behavior) {
            return new PermissionRule(toolName, "*", behavior);
        }

        /** 匹配工具的特定命令前缀 */
        public static PermissionRule forCommand(String toolName, String prefix, PermissionBehavior behavior) {
            return new PermissionRule(toolName, prefix + ":*", behavior);
        }
    }

    /** 权限决策结果 */
    public record PermissionDecision(
            PermissionBehavior behavior,
            String reason,
            String toolName,
            String commandPrefix,
            List<PermissionRule> suggestedRules
    ) {
        /** 构造"允许"决策（不绑定具体工具）。 */
        public static PermissionDecision allow(String reason) {
            return new PermissionDecision(PermissionBehavior.ALLOW, reason, null, null, List.of());
        }

        /** 构造"拒绝"决策（无工具上下文信息）。 */
        public static PermissionDecision deny(String reason) {
            return new PermissionDecision(PermissionBehavior.DENY, reason, null, null, List.of());
        }

        /** 构造"拒绝"决策（携带工具名与命令前缀，便于审计记录）。 */
        public static PermissionDecision deny(String reason, String toolName, String commandPrefix) {
            return new PermissionDecision(PermissionBehavior.DENY, reason, toolName, commandPrefix, List.of());
        }

        /** 构造"需用户确认"决策，并附带建议规则（如"始终允许该命令前缀"）。 */
        public static PermissionDecision ask(String toolName, String commandPrefix) {
            // 生成建议规则供用户选择 "always allow"
            var suggested = List.of(
                    PermissionRule.forCommand(toolName, commandPrefix, PermissionBehavior.ALLOW)
            );
            return new PermissionDecision(PermissionBehavior.ASK, "Requires user confirmation",
                    toolName, commandPrefix, suggested);
        }

        /** 是否为"允许"结果。 */
        public boolean isAllowed() {
            return behavior == PermissionBehavior.ALLOW;
        }

        /** 是否为"拒绝"结果。 */
        public boolean isDenied() {
            return behavior == PermissionBehavior.DENY;
        }

        /** 是否需要用户确认。 */
        public boolean needsAsk() {
            return behavior == PermissionBehavior.ASK;
        }
    }

    /** 权限确认选项（用户在 UI 中的选择） */
    public enum PermissionChoice {
        /** 允许本次执行 */
        ALLOW_ONCE,
        /** 始终允许此模式 */
        ALWAYS_ALLOW,
        /** 拒绝本次执行 */
        DENY_ONCE,
        /** 始终拒绝此模式 */
        ALWAYS_DENY
    }
}
