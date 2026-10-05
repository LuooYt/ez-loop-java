package com.inspirationi.loop.agent;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolRegistry;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 工具选择器 —— 决定一个智能体从全局工具集中「看得见」哪些工具。
 * <p>
 * 这是多智能体差异化的三个维度之一（另两个是提示词与权限）。选择器在
 * {@code AgentRuntimeFactory} 装配 Agent 时**求值一次**，结果物化进该 Agent
 * 私有的 {@link ToolRegistry}；此后新建的全局工具不会自动出现在已有 Agent 上
 * （与「会话创建即复制全局工具」的现有语义同构，但粒度更粗），需要时显式刷新。
 * <p>
 * 采用函数式接口而非枚举/集合：选择既可能是简单的白黑名单，也可能是按
 * {@link Tool#riskLevel()}、{@link Tool#isReadOnly()} 或自定义标签的谓词，
 * 用一套统一抽象避免为每种形态各加一个方法。
 */
@FunctionalInterface
public interface ToolSelect {

    /**
     * 从全局注册中心选出本 Agent 可见的工具。
     *
     * @param globalRegistry 全局工具注册中心（只读，实现不得修改它）
     * @return 选中的工具列表（保注册顺序）
     */
    List<Tool> select(ToolRegistry globalRegistry);

    /** 全部工具 —— 默认 Agent 与「全能」Agent 使用。 */
    static ToolSelect all() {
        return ToolRegistry::getTools;
    }

    /** 不选任何工具 —— 纯对话 Agent。 */
    static ToolSelect none() {
        return registry -> List.of();
    }

    /**
     * 白名单：只保留指定名称的工具。
     * <p>
     * 名单里不存在于全局注册中心的名称被静默忽略 —— 不同部署可能注册的工具集不同，
     * 让选择器对「某个专用工具在当前环境未装配」保持宽容，而不是整个 Agent 装配失败。
     */
    static ToolSelect allowlist(Set<String> toolNames) {
        Set<String> names = Set.copyOf(toolNames);
        return registry -> registry.getTools().stream()
                .filter(tool -> names.contains(tool.name()))
                .toList();
    }

    /** 白名单（可变参数便捷形式）。 */
    static ToolSelect allowlist(String... toolNames) {
        return allowlist(Set.of(toolNames));
    }

    /** 黑名单：排除指定名称的工具，其余全保留。 */
    static ToolSelect denylist(String... toolNames) {
        Set<String> names = Set.of(toolNames);
        return registry -> registry.getTools().stream()
                .filter(tool -> !names.contains(tool.name()))
                .toList();
    }

    /** 谓词选择：按工具的任意属性筛选（如仅只读工具、风险等级低于某阈值）。 */
    static ToolSelect byPredicate(Predicate<Tool> predicate) {
        return registry -> registry.getTools().stream()
                .filter(predicate)
                .toList();
    }

    /** 仅保留只读工具 —— 适合「分析/审查」类只读 Agent。 */
    static ToolSelect readOnlyOnly() {
        return byPredicate(Tool::isReadOnly);
    }
}
