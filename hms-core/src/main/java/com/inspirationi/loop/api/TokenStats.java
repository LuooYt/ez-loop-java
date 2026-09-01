package com.inspirationi.loop.api;

/**
 * Token 使用统计 —— 累加整个会话的 Token 消耗。
 */
public record TokenStats(
        /** 累计输入 Token */
        long inputTokens,

        /** 累计输出 Token */
        long outputTokens,

        /** 总 Token（输入 + 输出） */
        long totalTokens
) {

    /** 空统计 */
    public static final TokenStats ZERO = new TokenStats(0, 0, 0);

    /** 创建新统计 */
    public static TokenStats of(long input, long output) {
        return new TokenStats(input, output, input + output);
    }
}
