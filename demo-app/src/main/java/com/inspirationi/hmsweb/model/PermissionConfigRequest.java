package com.inspirationi.hmsweb.model;

/**
 * 权限配置请求。
 */
public record PermissionConfigRequest(
        /** 权限模式：STRICT / SAFE / DEFAULT / TRUSTED / BYPASS */
        String mode,
        /** 工具名称 */
        String toolName,
        /** 操作描述 */
        String description,
        /** allow 或 deny */
        String action
) {}
