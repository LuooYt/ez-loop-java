package com.inspirationi.loop.tool;

import java.util.Map;

/**
 * 工具协议接口 —— 定义工具的完整生命周期（定义、执行、权限检查）。
 * <p>
 * 每个工具是一个完整的协议实现，包含：
 * <ul>
 *   <li>工具定义（name、description、inputSchema）—— 告知 LLM 如何调用</li>
 *   <li>执行逻辑（execute）—— 实际运行</li>
 *   <li>权限检查（checkPermission）—— 安全前置检查</li>
 *   <li>特性门控（isEnabled）—— 条件注册</li>
 *   <li>活动描述（activityDescription）—— 人类可读的进度</li>
 * </ul>
 */
public interface Tool {

    /** 工具唯一名称标识 */
    String name();

    /** 给 LLM 看的工具描述 */
    String description();

    /**
     * 输入参数的 JSON Schema 定义。
     * <p>
     * 示例：
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "command": { "type": "string", "description": "Shell command to execute" }
     *   },
     *   "required": ["command"]
     * }
     * }</pre>
     */
    String inputSchema();

    /**
     * 执行工具。
     *
     * @param input   JSON 解析后的输入参数
     * @param context 执行上下文（工作目录、会话状态等）
     * @return 执行结果文本
     */
    String execute(Map<String, Object> input, ToolContext context);

    /**
     * 权限前置检查，在 execute 之前调用。
     * 默认放行。
     */
    default PermissionResult checkPermission(Map<String, Object> input, ToolContext context) {
        return PermissionResult.ALLOW;
    }

    /** 工具是否启用（特性门控），返回 false 则不注册 */
    default boolean isEnabled() {
        return true;
    }

    /** 是否为只读操作 */
    default boolean isReadOnly() {
        return false;
    }

    /**
     * 工具的风险等级，供权限引擎评估。
     * <p>
     * 工具实现方自行声明风险程度，权限引擎根据当前模式决定是否放行：
     * <ul>
     *   <li>{@link RiskLevel#READ_ONLY} — 纯读取，无副作用（如查询、搜索）</li>
     *   <li>{@link RiskLevel#LOW} — 轻度副作用（如日志记录、缓存写入）</li>
     *   <li>{@link RiskLevel#MEDIUM} — 中等副作用（如数据变更、消息推送）</li>
     *   <li>{@link RiskLevel#HIGH} — 高风险（如删除、外部 API 调用、系统命令）</li>
     *   <li>{@link RiskLevel#CRITICAL} — 关键操作（如权限变更、资金操作、不可逆删除）</li>
     * </ul>
     * <p>
     * 默认根据 {@link #isReadOnly()} 推断：只读 → READ_ONLY，否则 → MEDIUM。
     * 子类应覆写此方法提供更准确的风险声明。
     */
    default RiskLevel riskLevel() {
        return isReadOnly() ? RiskLevel.READ_ONLY : RiskLevel.MEDIUM;
    }

    /** 工具风险等级 */
    enum RiskLevel {
        /** 纯读取，无副作用 */
        READ_ONLY,
        /** 轻度副作用 */
        LOW,
        /** 中等副作用（默认） */
        MEDIUM,
        /** 高风险 */
        HIGH,
        /** 关键操作 */
        CRITICAL
    }

    /** 人类可读的活动描述，用于 UI 显示执行进度 */
    default String activityDescription(Map<String, Object> input) {
        return "Running " + name() + "...";
    }

    /**
     * 提取面向模型的异常描述 —— 供工具在返回错误文本时使用。
     * <p>
     * 工具返回值会作为 tool_result 回传给模型，必须让它能判断下一步动作。
     * NPE 等异常的 {@code getMessage()} 常为 {@code null}，直接拼接会得到无信息的
     * {@code "Error: null"}，模型往往因此反复重试同一个失败调用直到迭代耗尽。
     * 此时回退到异常类名。
     *
     * @param error 待描述的异常，可为 {@code null}
     * @return 非空的可读描述
     */
    static String describeError(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return error.getClass().getSimpleName();
    }
}
