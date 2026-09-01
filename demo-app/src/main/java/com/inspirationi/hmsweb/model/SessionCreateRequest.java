package com.inspirationi.hmsweb.model;

/**
 * 创建会话请求。
 */
public record SessionCreateRequest(
        /** 自定义会话提示词（可选） */
        String sessionPrompt
) {}
