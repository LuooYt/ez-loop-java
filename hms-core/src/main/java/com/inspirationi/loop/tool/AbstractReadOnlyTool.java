package com.inspirationi.loop.tool;

import java.util.Map;

/**
 * 只读工具抽象基类 —— 18+ 只读工具共享的基础实现。
 * <p>
 * 提供:
 * <ul>
 *   <li>{@link #isReadOnly()} 固定返回 true</li>
 *   <li>{@link #isEnabled()} 默认返回 true</li>
 *   <li>{@link #checkPermission} 默认 ALLOW</li>
 *   <li>{@link #errorResult(String)} 标准化错误格式</li>
 * </ul>
 * 子类只需实现 name/description/inputSchema/execute。
 */
public abstract class AbstractReadOnlyTool implements Tool {

    /** 只读工具始终返回 true，保证不产生任何副作用 */
    @Override
    public final boolean isReadOnly() {
        return true;
    }

    /** 只读工具的风险等级固定为 {@link RiskLevel#READ_ONLY} */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.READ_ONLY;
    }

    /** 只读工具默认启用（子类可按需覆写） */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /** 只读工具默认全部放行，不做权限拦截 */
    @Override
    public PermissionResult checkPermission(Map<String, Object> input, ToolContext context) {
        return PermissionResult.ALLOW;
    }

    /** 只读操作的人类可读活动描述，固定为 "Reading <工具名>..." */
    @Override
    public String activityDescription(Map<String, Object> input) {
        return "Reading " + name() + "...";
    }

    /**
     * 标准化错误结果。
     */
    protected String errorResult(String message) {
        return "Error: " + message;
    }

    /**
     * 从 input 中获取必填 String 参数，缺失则返回 null 并可由调用方提前 return errorResult。
     */
    protected String requireParam(Map<String, Object> input, String paramName) {
        String err = ToolValidator.requireString(input, paramName);
        return err == null ? input.get(paramName).toString() : null;
    }
}
