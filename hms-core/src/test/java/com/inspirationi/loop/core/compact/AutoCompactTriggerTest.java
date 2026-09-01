package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.core.TokenTracker;
import com.inspirationi.loop.core.compact.CompactionResult.CompactLayer;

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
            public ChatOptions getOptions() {
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
    void microCompactSucceedsDefersDeepCompaction() {
        TokenTracker tracker = new TokenTracker();
        // 95% > 93% 阈值，但未达 98% 阻塞阈值
        tracker.recordUsage((long) (EFFECTIVE_WINDOW * 0.95), 100);

        AutoCompactManager manager = new AutoCompactManager(failingChatModel(), tracker);
        List<Message> history = historyWithBulkyToolResults(10);

        CompactionResult result = manager.autoCompactIfNeeded(() -> history, r -> { });

        // 微压缩有内容可裁剪就应就此返回：shouldAutoCompact() 读的是 lastPromptTokens，
        // 只在下一次 API 调用后刷新，此刻重查必然仍超阈值。若不提前返回，微压缩每轮
        // 都白做一遍又立刻走进阶段 2/3 的付费 AI 摘要。
        assertNotNull(result, "微压缩生效时应返回其结果");
        assertEquals(CompactLayer.MICRO, result.layer(), "应停在微压缩层，不升级");
        assertEquals(0, manager.getConsecutiveFailures(),
                "微压缩成功不是失败，不应计入连续失败次数");
    }

    @Test
    void aboveThresholdAttemptsDeepCompactionWhenMicroHasNothingToTrim() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage((long) (EFFECTIVE_WINDOW * 0.95), 100);

        AutoCompactManager manager = new AutoCompactManager(failingChatModel(), tracker);
        // 无大体积 tool 结果 → 微压缩无可裁剪内容，自然升级到深度压缩
        List<Message> history = freshHistory();

        CompactionResult result = manager.autoCompactIfNeeded(() -> history, r -> { });

        // ChatModel 不可用 → Session Memory 与全量压缩都会失败，
        // 但关键是它「尝试过」（返回非 null 结果而非静默跳过）
        assertNotNull(result, "阈值达到且微压缩无能为力时应尝试深度压缩");
        assertTrue(manager.getConsecutiveFailures() > 0,
                "深度压缩失败应被计入连续失败次数");
    }

    @Test
    void blockingThresholdForcesDeepCompactionEvenAfterMicroCompact() {
        TokenTracker tracker = new TokenTracker();
        // 99% ≥ 98% 阻塞阈值 —— 下一次 API 调用可能直接超限，不能延后到下一轮
        tracker.recordUsage((long) (EFFECTIVE_WINDOW * 0.99), 100);

        AutoCompactManager manager = new AutoCompactManager(failingChatModel(), tracker);
        // 有大体积 tool 结果：微压缩会成功，但阻塞阈值下仍须继续深度压缩
        List<Message> history = historyWithBulkyToolResults(10);

        CompactionResult result = manager.autoCompactIfNeeded(() -> history, r -> { });

        assertNotNull(result);
        assertTrue(manager.getConsecutiveFailures() > 0,
                "达阻塞阈值时不得因微压缩成功而提前返回，深度压缩失败应计入");
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

    /**
     * 构造一段<b>无可裁剪内容</b>的历史 —— tool 结果都很短，微压缩会返回 noAction，
     * 于是每次调用都必然升级到深度压缩。熔断相关测试依赖这一点：若历史里有大体积
     * tool 结果，微压缩会成功并提前返回，深度压缩永远不被尝试，也就永远不会熔断。
     */
    private static List<Message> freshHistory() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        for (int i = 0; i < 10; i++) {
            history.add(new UserMessage("user " + i));
            history.add(new AssistantMessage("assistant " + i));
            history.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call-" + i, "SomeTool", "ok")))
                    .build());
        }
        return history;
    }
}
