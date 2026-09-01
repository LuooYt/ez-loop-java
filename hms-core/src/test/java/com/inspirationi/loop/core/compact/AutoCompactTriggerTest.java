package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.core.TokenTracker;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutoCompactManager} 的触发行为测试。
 * <p>
 * 覆盖「阈值未达 → 只做微压缩」与「阈值达到 → 进入深度压缩」两条路径，
 * 以及 ChatModel 不可用时的熔断保护。
 */
class AutoCompactTriggerTest {

    private static final long EFFECTIVE_WINDOW = 180_000;

    /** 永远抛异常的 ChatModel —— 用于验证深度压缩失败后的熔断路径。 */
    private static ChatModel failingChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("stub model unavailable");
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };
    }

    /** 构造一段含大体积 tool 结果的历史，供微压缩裁剪。 */
    private static List<Message> historyWithBulkyToolResults(int toolResultCount) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        String bulky = "x".repeat(5_000);
        for (int i = 0; i < toolResultCount; i++) {
            history.add(new UserMessage("user " + i));
            history.add(new AssistantMessage("assistant " + i));
            history.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call-" + i, "SomeTool", bulky)))
                    .build());
        }
        return history;
    }

    @Test
    void belowThresholdOnlyRunsMicroCompactAndReturnsNull() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage(1_000, 10);   // 远低于阈值

        AutoCompactManager manager = new AutoCompactManager(failingChatModel(), tracker);
        List<Message> history = historyWithBulkyToolResults(10);

        CompactionResult result = manager.autoCompactIfNeeded(() -> history, replacement -> {
            throw new AssertionError("阈值未达时不应替换历史");
        });

        assertNull(result, "阈值未达时应返回 null（仅静默执行微压缩）");
    }

    @Test
    void microCompactTruncatesOldToolResultsInPlace() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage(1_000, 10);

        AutoCompactManager manager = new AutoCompactManager(failingChatModel(), tracker);
        // 10 条 tool 结果，微压缩保留最近 6 条，应裁剪 4 条
        List<Message> history = historyWithBulkyToolResults(10);

        manager.autoCompactIfNeeded(() -> history, r -> { });

        long bulkyRemaining = history.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .filter(trm -> trm.getResponses().stream()
                        .anyMatch(r -> r.responseData() != null
                                && r.responseData().length() > 1_000))
                .count();

        assertEquals(6, bulkyRemaining,
                "微压缩应保留最近 6 条完整 tool 结果，更早的被截断");
    }

    @Test
    void aboveThresholdAttemptsDeepCompaction() {
        TokenTracker tracker = new TokenTracker();
        // 95% > 93% 阈值 → 触发深度压缩
        tracker.recordUsage((long) (EFFECTIVE_WINDOW * 0.95), 100);

        AutoCompactManager manager = new AutoCompactManager(failingChatModel(), tracker);
        List<Message> history = historyWithBulkyToolResults(10);

        CompactionResult result = manager.autoCompactIfNeeded(() -> history, r -> { });

        // ChatModel 不可用 → Session Memory 与全量压缩都会失败，
        // 但关键是它「尝试过」（返回非 null 结果而非静默跳过）
        assertNotNull(result, "阈值达到时应返回压缩结果（成功或失败）");
        assertTrue(manager.getConsecutiveFailures() > 0,
                "深度压缩失败应被计入连续失败次数");
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailures() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage((long) (EFFECTIVE_WINDOW * 0.95), 100);

        AutoCompactManager manager = new AutoCompactManager(failingChatModel(), tracker);

        // 反复触发直到熔断
        for (int i = 0; i < 5 && !manager.isCircuitBroken(); i++) {
            manager.autoCompactIfNeeded(AutoCompactTriggerTest::freshHistory, r -> { });
        }

        assertTrue(manager.isCircuitBroken(),
                "连续失败达阈值后应打开熔断器，避免每轮都白烧一次 API 调用");

        // 熔断后应立即返回 null，不再尝试
        AtomicInteger supplierCalls = new AtomicInteger();
        CompactionResult afterBreak = manager.autoCompactIfNeeded(() -> {
            supplierCalls.incrementAndGet();
            return freshHistory();
        }, r -> { });

        assertNull(afterBreak, "熔断后应直接返回 null");
        assertEquals(0, supplierCalls.get(), "熔断后不应再读取历史");
    }

    @Test
    void resetCircuitBreakerAllowsRetry() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage((long) (EFFECTIVE_WINDOW * 0.95), 100);

        AutoCompactManager manager = new AutoCompactManager(failingChatModel(), tracker);
        for (int i = 0; i < 5 && !manager.isCircuitBroken(); i++) {
            manager.autoCompactIfNeeded(AutoCompactTriggerTest::freshHistory, r -> { });
        }
        assertTrue(manager.isCircuitBroken());

        manager.resetCircuitBreaker();
        assertTrue(!manager.isCircuitBroken());
        assertEquals(0, manager.getConsecutiveFailures());
    }

    private static List<Message> freshHistory() {
        return historyWithBulkyToolResults(10);
    }
}
