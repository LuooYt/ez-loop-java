package com.inspirationi.loop.agent;

/**
 * 主动智能体的心跳规格 —— 触发节律与防失控护栏。
 * <p>
 * 心跳是「固定周期」语义：无论工作队列是否为空，tick 到点都会唤醒 Agent（队列有活
 * 则消费工作项，无活则用 {@link #heartbeatPrompt} 自省）。固定周期意味着**空转也会
 * 消耗 token**，所以本 record 的每一个护栏字段都不是可选项。
 *
 * @param intervalSeconds     心跳周期（秒）。须为正数。
 * @param maxConsecutiveRuns  连续运行次数上限 —— 达到后心跳**自动暂停**，需显式
 *                            {@code resumeHeartbeat()}。这是防「无限自触发烧钱」的最后一道闸。
 * @param idleBackoffFactor   连续无产出时的降频系数（如 2.0 表示周期翻倍）。须 &gt;= 1。
 * @param maxIdleRuns         连续无产出多少次后开始降频。
 * @param heartbeatPrompt     队列为空时发给 Agent 的自省提示词。
 */
public record HeartbeatSpec(
        long intervalSeconds,
        int maxConsecutiveRuns,
        double idleBackoffFactor,
        int maxIdleRuns,
        String heartbeatPrompt
) {

    /** 默认自省提示词（中文默认，经 PromptI18n 可按系统语言取用）。 */
    public static final String DEFAULT_HEARTBEAT_PROMPT = """
            这是一次定期巡检唤醒，并非用户发来的新消息。

            请：
            - 复查你的既定目标与待办事项的进展；
            - 如有确需跟进的工作，用 EnqueueWork 安排到工作队列，或直接完成它；
            - 若当前没有需要处理的事项，简要说明状态即可，**不要为了避免沉默而采取无意义的行动**。
            """;

    /** 默认心跳参数：5 分钟一跳、最多连续 100 次、连续 3 次无产出后周期翻倍。 */
    public static HeartbeatSpec defaults() {
        return new HeartbeatSpec(300, 100, 2.0, 3, DEFAULT_HEARTBEAT_PROMPT);
    }

    /** 指定周期、其余取默认值。 */
    public static HeartbeatSpec every(long intervalSeconds) {
        return new HeartbeatSpec(intervalSeconds, 100, 2.0, 3, DEFAULT_HEARTBEAT_PROMPT);
    }

    /** 紧凑构造器：把明显非法的参数就地归一化，避免调度期出现除零或永不停机。 */
    public HeartbeatSpec {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("heartbeat intervalSeconds must be positive");
        }
        if (maxConsecutiveRuns <= 0) {
            throw new IllegalArgumentException("maxConsecutiveRuns must be positive");
        }
        if (idleBackoffFactor < 1.0) {
            throw new IllegalArgumentException("idleBackoffFactor must be >= 1.0");
        }
        if (maxIdleRuns <= 0) {
            throw new IllegalArgumentException("maxIdleRuns must be positive");
        }
        if (heartbeatPrompt == null || heartbeatPrompt.isBlank()) {
            heartbeatPrompt = DEFAULT_HEARTBEAT_PROMPT;
        }
    }
}
