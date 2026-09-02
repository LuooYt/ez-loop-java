package com.inspirationi.loop.core.compact;

/**
 * 压缩操作的结果数据。
 *
 * @param success         是否成功
 * @param layer           执行的压缩层级
 * @param messagesBefore  压缩前消息数
 * @param messagesAfter   压缩后消息数
 * @param summary         AI 生成的摘要（可能为 null）
 * @param reason          结果原因/描述
 */
public record CompactionResult(
        boolean success,
        CompactLayer layer,
        int messagesBefore,
        int messagesAfter,
        String summary,
        String reason
) {

    /** 压缩层级 */
    public enum CompactLayer {
        /** 微压缩：裁剪旧 tool_result 内容 */
        MICRO,
        /** Session Memory：AI 摘要旧消息，保留近期段 */
        SESSION_MEMORY,
        /** 全量压缩：AI 摘要全部，PTL 重试 */
        FULL,
        /** 用户手动触发的全量压缩 */
        MANUAL
    }

    /** 构建成功结果：记录压缩前后的消息数 */
    public static CompactionResult success(CompactLayer layer, int before, int after, String summary) {
        return new CompactionResult(true, layer, before, after, summary,
                "Compacted from " + before + " to " + after + " messages");
    }

    /**
     * 构建微压缩的成功结果。
     * <p>
     * <b>为什么单列一个工厂</b>：{@link CompactLayer#MICRO} 用 {@code List.set()} 原地
     * 替换超长的 tool_result，<b>消息条数分毫不变</b>。走 {@link #success} 就只能把
     * 「工具响应条数」塞进 {@code messagesBefore} / {@code messagesAfter} —— 那两个字段
     * 的声明语义是消息数，且会经 {@code HmsEvent.Compaction} 直接推给前端，观测方
     * 无从知道 MICRO 层的这两个数字换了一套含义。
     * <p>
     * 因此这里让条数字段说真话（前后都是 {@code historySize}），把「裁掉了多少个
     * 工具结果」放进 {@code reason}。前端见到两数相等即改用 {@code reason} 展示。
     *
     * @param historySize        消息历史长度（压缩前后相同）
     * @param toolResultsTotal   历史中 tool_result 的总数
     * @param truncated          本次被截断的 tool_result 数
     */
    public static CompactionResult microSuccess(int historySize, int toolResultsTotal, int truncated) {
        return new CompactionResult(true, CompactLayer.MICRO, historySize, historySize, null,
                "Truncated " + truncated + " of " + toolResultsTotal
                        + " tool results (message count unchanged)");
    }

    /** 构建"无操作"结果：条件不足未实际触发压缩 */
    public static CompactionResult noAction(CompactLayer layer, String reason) {
        return new CompactionResult(false, layer, 0, 0, null, reason);
    }

    /** 构建失败结果 */
    public static CompactionResult failure(CompactLayer layer, String reason) {
        return new CompactionResult(false, layer, 0, 0, null, reason);
    }
}
