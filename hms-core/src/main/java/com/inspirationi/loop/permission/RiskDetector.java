package com.inspirationi.loop.permission;

import java.util.Map;

/**
 * 风险检测器接口 —— 检测工具调用是否存在安全风险。
 * <p>
 * SDK 集成方可以实现此接口并注册到 {@link PermissionRuleEngine}，
 * 以支持场景特定的风险检测（如 SQL 注入、敏感数据泄露、路径遍历等）。
 * <p>
 * 默认实现 {@link DangerousPatterns} 提供 Shell 命令风险检测。
 */
@FunctionalInterface
public interface RiskDetector {

    /**
     * 检测工具调用是否存在风险。
     *
     * @param toolName 工具名称
     * @param input    工具参数
     * @return 风险描述字符串（如 "危险的 Shell 命令: rm -rf /"），
     *         返回 {@code null} 表示无风险
     */
    String detect(String toolName, Map<String, Object> input);

    /**
     * 检测规则字符串是否为危险的通配符规则。
     * <p>
     * 例如，"Bash(*)" 永久放行所有 Bash 命令应被视为危险。
     *
     * @param ruleStr 已格式化的规则字符串（如 "Bash(*)", "DatabaseTool(*)"）
     * @return true 表示该规则过于宽泛，不应被持久化
     */
    default boolean isDangerousWildcard(String ruleStr) {
        return false;
    }
}
