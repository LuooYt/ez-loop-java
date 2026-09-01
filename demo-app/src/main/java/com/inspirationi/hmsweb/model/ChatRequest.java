package com.inspirationi.hmsweb.model;

/**
 * 对话请求。
 */
public record ChatRequest(
        /** 用户消息 */
        String message
) {}
