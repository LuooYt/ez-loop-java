package com.inspirationi.hmsweb.model;

/**
 * 用户回答请求（用于 onAskUser 和 onPermissionRequest 的异步回复）。
 */
public record UserResponseRequest(
        /** 回答内容 */
        String response
) {}
