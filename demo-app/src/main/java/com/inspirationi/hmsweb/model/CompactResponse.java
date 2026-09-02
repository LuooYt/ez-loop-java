package com.inspirationi.hmsweb.model;

import com.inspirationi.loop.core.compact.CompactionResult;

/**
 * 手动压缩的结果。
 * <p>
 * 用 record 而非 Map：这里每个字段都恒非 null（{@code layer} 与 {@code reason} 由
 * {@link CompactionResult} 的三个静态工厂各自填充），从结构上避免了 {@code Map.of}
 * 遇 null value 抛 NPE 的老问题。
 * <p>
 * 不透出 {@code CompactionResult.summary()} —— 那个参数位实际传入的是原因字符串
 * 而非 AI 摘要正文，且失败/无操作时恒为 null，暴露只会给前端一个含义错乱的字段。
 */
public record CompactResponse(
        /** 是否实际执行了压缩：历史过短或摘要失败时为 false */
        boolean compacted,
        /** 压缩层级，手动触发恒为 MANUAL */
        String layer,
        /** 压缩前消息数 */
        int messagesBefore,
        /** 压缩后消息数 */
        int messagesAfter,
        /** 结果原因描述 */
        String reason
) {

    /** 从 SDK 结果转换。 */
    public static CompactResponse from(CompactionResult result) {
        return new CompactResponse(
                result.success(),
                result.layer() != null ? result.layer().name() : "UNKNOWN",
                result.messagesBefore(),
                result.messagesAfter(),
                result.reason());
    }
}
