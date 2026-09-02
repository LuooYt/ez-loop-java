package com.inspirationi.loop.telemetry;

/**
 * 一次或多次 API 调用的四类 token 用量。
 * <p>
 * <b>四类必须分开承载</b>：缓存读取的单价约为普通输入的 1/10，把它并入
 * {@code inputTokens} 会让费用按全价计算 —— 长会话里可高估数倍。缓存写入
 * （{@code cacheCreationTokens}）反过来比基础输入更贵（Anthropic 约 1.25 倍），
 * 混入同样失真，只是方向相反。
 *
 * @param inputTokens          普通输入 token（未命中缓存的部分）
 * @param outputTokens         输出 token
 * @param cacheReadTokens      命中缓存而读取的 token
 * @param cacheCreationTokens  写入缓存所消耗的 token
 */
public record TokenUsage(long inputTokens, long outputTokens,
                         long cacheReadTokens, long cacheCreationTokens) {

    /** 全零用量 —— provider 未报告任何用量时使用。 */
    public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

    /** 仅有输入与输出的用量（provider 不报告缓存时）。 */
    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, 0, 0);
    }

    /**
     * 是否有任何用量需要记账。
     * <p>
     * 四项全零通常意味着 provider 没有回报 usage 元数据，而非「真的没消耗」——
     * 调用方据此决定是否跳过记账，避免把缺失数据记成一次零消耗的调用。
     */
    public boolean hasAny() {
        return inputTokens > 0 || outputTokens > 0
                || cacheReadTokens > 0 || cacheCreationTokens > 0;
    }

    /** 计费口径的 token 总量（四类相加）。 */
    public long totalTokens() {
        return inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens;
    }
}
