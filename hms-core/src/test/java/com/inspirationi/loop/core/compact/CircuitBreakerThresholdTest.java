package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.core.TokenTracker;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动压缩熔断器的触发门槛。
 * <p>
 * {@code MAX_CONSECUTIVE_FAILURES = 3} 与字段名 {@code consecutiveFailures}
 * 都表达「连续失败 3 次才熔断」。但 {@code autoCompactIfNeeded} 单次调用内会
 * 累加该计数<b>三次</b>：阶段 2 失败一次、阶段 3 失败一次、末尾「所有方式均失败」
 * 再无条件一次 —— 于是首次调用失败就直接达到阈值。
 * <p>
 * 熔断是<b>永久</b>的（{@code circuitBroken} 只能由
 * {@link AutoCompactManager#resetCircuitBreaker()} 手动清除，而
 * {@code HmsSessionManager} 不暴露压缩器）。因此一次偶发失败会让该会话
 * 此后再不压缩 —— 上下文持续增长直到 API 报 prompt too long。
 */
class CircuitBreakerThresholdTest {

    /** 摘要调用一律失败的模型 —— 迫使阶段 2 / 阶段 3 都走失败分支。 */
    private static ChatModel alwaysFailingSummarizer() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new RuntimeException("summarizer unavailable");
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    /** 返回 null 摘要的模型 —— 另一条失败路径（非异常，而是拿不到摘要）。 */
    private static ChatModel nullSummarizer() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage(""), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    /** 构造一段足够长、且能进入深层压缩的历史。 */
    private static List<Message> longHistory(int rounds) {
        String filler = "x".repeat(2000);
        List<Message> h = new ArrayList<>();
        h.add(new SystemMessage("sys"));
        for (int i = 0; i < rounds; i++) {
            h.add(new UserMessage("u" + i + filler));
            h.add(new AssistantMessage("a" + i + filler));
        }
        return h;
    }

    /** 把 token 用量顶到自动压缩阈值以上。 */
    private static TokenTracker trackerAboveThreshold() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage((long) (tracker.getEffectiveWindow() * 0.95), 100);
        return tracker;
    }

    /**
     * 单次调用内的失败不应耗尽整个熔断预算。
     * <p>
     * 「连续 3 次」的语义应当是跨调用累计：一次压缩尝试失败记一次，
     * 连续 3 次尝试都失败才熔断。
     */
    @Test
    void singleFailedAttemptDoesNotTripBreaker() {
        AutoCompactManager manager = new AutoCompactManager(
                alwaysFailingSummarizer(), trackerAboveThreshold());
        List<Message> history = longHistory(30);

        manager.autoCompactIfNeeded(() -> history, replacement -> { });

        assertFalse(manager.isCircuitBroken(),
                "单次压缩尝试失败就熔断，与 MAX_CONSECUTIVE_FAILURES=3 的语义不符。"
                        + "实际失败计数=" + manager.getConsecutiveFailures()
                        + "（一次调用内被累加了 3 次：阶段 2、阶段 3、末尾兜底各一次）。"
                        + "熔断是永久的，这意味着一次偶发的摘要失败会让该会话此后"
                        + "再不压缩，上下文持续增长直到 API 报 prompt too long");
    }

    /** 一次失败的尝试只应记一次失败。 */
    @Test
    void oneAttemptCountsAsOneFailure() {
        AutoCompactManager manager = new AutoCompactManager(
                alwaysFailingSummarizer(), trackerAboveThreshold());
        List<Message> history = longHistory(30);

        manager.autoCompactIfNeeded(() -> history, replacement -> { });

        assertEquals(1, manager.getConsecutiveFailures(),
                "一次失败的压缩尝试应只记一次失败，而非按内部阶段数重复累加");
    }

    /**
     * 连续 3 次尝试失败后才应熔断 —— 确认阈值本身仍然生效，
     * 而不是把熔断改成永不触发。
     */
    @Test
    void breakerStillTripsAfterThreeFailedAttempts() {
        AutoCompactManager manager = new AutoCompactManager(
                nullSummarizer(), trackerAboveThreshold());
        List<Message> history = longHistory(30);

        for (int i = 0; i < 3; i++) {
            manager.autoCompactIfNeeded(() -> history, replacement -> { });
        }

        assertTrue(manager.isCircuitBroken(),
                "连续 3 次压缩尝试均失败后必须熔断，否则每轮都要白付一次摘要调用");
    }

    /**
     * 「无可压缩」不等于「压缩失败」。
     * <p>
     * 曾经 {@code SessionMemoryCompact} 对两种情形都返回 {@code null}：
     * 历史太短/可压缩区间不足（<b>正常</b>，无事可做），以及摘要生成失败
     * （<b>异常</b>）。{@code AutoCompactManager} 无法区分、一律记作失败 ——
     * 于是一段本就没什么可压缩的短历史，也会把熔断预算耗尽。现由
     * {@code tryCompact} 返回的 {@code Outcome} 区分。
     * <p>
     * 这类会话恰恰最不该被熔断：它当前用量高但历史短（比如单条巨型消息），
     * 后续增长后本可以正常压缩，却已被永久禁用。
     */
    @Test
    void nothingToCompactIsNotAFailure() {
        AutoCompactManager manager = new AutoCompactManager(
                alwaysFailingSummarizer(), trackerAboveThreshold());
        // 5 条消息：勉强越过各压缩层的最低门槛，但没有真正可压缩的区间
        List<Message> shortHistory = longHistory(2);

        manager.autoCompactIfNeeded(() -> shortHistory, replacement -> { });

        assertFalse(manager.isCircuitBroken(),
                "「历史太短、无可压缩」与「摘要调用失败」都返回 null，被一律记作失败。"
                        + "短历史会话因此被永久禁用压缩，而它后续增长后本可正常压缩");
    }
}
