package com.inspirationi.loop.api;

import java.time.Instant;
import java.util.List;

/**
 * 会话信息 DTO —— 通过 {@link HmsSessionManager#getSessionInfo(String)}
 * 或 {@link HmsSessionManager#listSessions()} 返回。
 */
public record SessionInfo(
        /** 会话唯一标识 */
        String sessionId,
        /** 生命周期状态 —— 能否接收消息 */
        SessionStatus status,
        /**
         * 运行时活动状态 —— 此刻正在做什么。
         * <p>
         * 与 {@link #status} 是正交的两个维度：{@code ACTIVE} 会话既可能空闲，
         * 也可能正在调模型或执行工具。
         */
        SessionActivity activity,
        /** 会话级提示词（不含全局前缀） */
        String sessionPrompt,
        /** 会话当前可用的工具名称列表 */
        List<String> toolNames,
        /** 会话创建时间 */
        Instant createdAt,
        /** 会话最后访问时间 */
        Instant lastAccessTime,
        /** 会话空闲时长（秒） */
        long idleSeconds,
        /** 累计输入 Token */
        long inputTokens,
        /** 累计输出 Token */
        long outputTokens,
        /** 会话消息数 */
        int messageCount
) {
    /** 总 Token 消耗（输入 + 输出） */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
