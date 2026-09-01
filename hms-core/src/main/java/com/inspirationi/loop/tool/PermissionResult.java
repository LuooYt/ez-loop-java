package com.inspirationi.loop.tool;

/**
 * 工具权限检查结果。
 * <p>
 * 工具调用前权限检查的结果封装。
 */
public record PermissionResult(boolean allowed /* 是否放行 */, String message /* 拒绝原因，放行时为 null */) {

    /** 放行 */
    public static final PermissionResult ALLOW = new PermissionResult(true, null);

    /** 拒绝，附带原因 */
    public static PermissionResult deny(String reason) {
        return new PermissionResult(false, reason);
    }
}
