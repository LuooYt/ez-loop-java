package com.inspirationi.hmsweb.model;

/**
 * 会话提示词更新请求。
 */
public record PromptUpdateRequest(
        /** 新的会话提示词 */
        String sessionPrompt
) {}
