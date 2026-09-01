package com.inspirationi.loop.core.compact;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session Memory 压缩（第二层）的生效范围。
 * <p>
 * 这是 {@code AutoCompactManager} 的主力压缩层，位于免费的微压缩之后、
 * 付费兜底的全量压缩之前。它若在某个历史规模区间静默失效，编排逻辑会直接
 * 跳到 {@link FullCompact} —— 行为上「压缩成功了」，代价是多一次全量摘要的
 * API 调用，且丢失的上下文远多于必要。
 */
class SessionMemoryCompactTest {

    /** 每条消息约 250 字符 → 按 CHARS_PER_TOKEN=4、安全系数 4/3 估算约 83 token。 */
    private static final String FILLER = "x".repeat(250);

    private static ChatModel summarizer(AtomicInteger callCount) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                callCount.incrementAndGet();
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("SUMMARY"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    /**
     * 构造 [system] + n 轮 (user, assistant) 的历史。
     *
     * @param charsPerMessage 每条文本消息的长度，用于控制估算 token 总量
     */
    private static List<Message> history(int rounds, int charsPerMessage) {
        String body = "x".repeat(charsPerMessage);
        List<Message> h = new ArrayList<>();
        h.add(new SystemMessage("sys"));
        for (int i = 0; i < rounds; i++) {
            h.add(new UserMessage("u" + i + body));
            h.add(new AssistantMessage("a" + i + body));
        }
        return h;
    }

    /** 估算一段历史的 token 总量，与被测类的公式保持一致。 */
    private static long estimate(int messages, int charsPerMessage) {
        return (long) (messages * (charsPerMessage / 4.0) * (4.0 / 3.0));
    }

    /**
     * 历史总量远超 MAX_KEEP_TOKENS（40K）时，压缩必须生效 —— 这是该层设计上
     * 明确要覆盖的情形，作为下面区间测试的对照组。
     */
    @Test
    void compactsWhenHistoryFarExceedsMaxKeep() {
        AtomicInteger calls = new AtomicInteger();
        SessionMemoryCompact compact = new SessionMemoryCompact(summarizer(calls));

        // 120 条 × 4000 字符 ≈ 160K token，远超 40K 上限
        List<Message> h = history(60, 4000);
        assertTrue(estimate(120, 4000) > 40_000, "前提：构造的历史应远超 MAX_KEEP_TOKENS");

        SessionMemoryCompact.CompactAttempt attempt = compact.tryCompact(h);

        assertTrue(attempt.isCompacted(),
                "历史远超上限时 Session Memory 压缩必须生效，实际结论=" + attempt.outcome());
        List<Message> result = attempt.history();
        assertNotNull(result, "已压缩的结果必须带上新历史");
        assertEquals(1, calls.get(), "应调用一次模型生成摘要");
        assertTrue(result.size() < h.size(), "压缩后消息数应减少");
        assertTrue(result.get(1) instanceof SystemMessage sm
                        && sm.getText().startsWith("[Conversation Summary]"),
                "压缩结果第二条应是摘要");
    }

    /**
     * 历史落在 MIN_KEEP_TOKENS(10K) 与 MAX_KEEP_TOKENS(40K) 之间时，
     * 压缩必须照常生效。
     * <p>
     * 曾经的缺陷：{@code findKeepStart} 只在 {@code estimatedTokens >=
     * MAX_KEEP_TOKENS} 时才提前返回；总量够不到 40K 就一路扫到 {@code minStart}
     * 并返回它，使 {@code keepStart - compressibleStart == 0}，随后被
     * {@code < 4} 的判断挡掉 → 返回 null → 编排层升级到付费的全量压缩。
     * <p>
     * 该区间恰是最常见的会话规模：远超微压缩能腾出的空间，又没到需要
     * 抛弃全部上下文的程度。
     */
    @Test
    void compactsWhenHistoryIsBetweenMinAndMaxKeep() {
        AtomicInteger calls = new AtomicInteger();
        SessionMemoryCompact compact = new SessionMemoryCompact(summarizer(calls));

        // 60 条 × 1200 字符 ≈ 24K token：稳稳落在 10K–40K 区间内
        List<Message> h = history(30, 1200);
        long total = estimate(60, 1200);
        assertTrue(total > 10_000 && total < 40_000,
                "前提：构造的历史应落在 MIN_KEEP(10K)–MAX_KEEP(40K) 区间，实际约 " + total);

        SessionMemoryCompact.CompactAttempt attempt = compact.tryCompact(h);

        assertTrue(attempt.isCompacted(),
                "历史约 " + total + " token（超过 MIN_KEEP 两倍以上、60 条消息）时，"
                        + "Session Memory 压缩应当生效；不压缩会让编排层跳到"
                        + "付费的 FullCompact，白付一次全量摘要且丢弃更多上下文。"
                        + "实际结论=" + attempt.outcome());
        assertTrue(attempt.history().size() < h.size(), "压缩后消息数应减少");
    }

    /**
     * 历史太短时返回 {@code NOTHING_TO_COMPACT}，而非 {@code FAILED}。
     * <p>
     * 二者都表示「没压缩」，但只有后者该计入 {@link AutoCompactManager} 的熔断
     * 预算。混为一谈会让一段本就没什么可压缩的短历史把预算耗尽 —— 而熔断是
     * 永久的，该会话此后再不压缩。
     */
    @Test
    void shortHistoryIsNothingToCompactNotFailure() {
        AtomicInteger calls = new AtomicInteger();
        SessionMemoryCompact compact = new SessionMemoryCompact(summarizer(calls));

        SessionMemoryCompact.CompactAttempt attempt = compact.tryCompact(history(2, 100));

        assertEquals(SessionMemoryCompact.CompactAttempt.Outcome.NOTHING_TO_COMPACT,
                attempt.outcome(),
                "历史太短属于正常状态，不是压缩失败");
        assertFalse(attempt.isFailure(), "不得计入熔断预算");
        assertEquals(0, calls.get(), "无可压缩时不该浪费一次摘要调用");
    }

    /** 摘要拿不到时返回 {@code FAILED} —— 这才是该计入熔断预算的情形。 */
    @Test
    void emptySummaryIsAFailure() {
        // 模型返回空白摘要
        ChatModel blankSummarizer = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("   "), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
        SessionMemoryCompact compact = new SessionMemoryCompact(blankSummarizer);

        SessionMemoryCompact.CompactAttempt attempt = compact.tryCompact(history(60, 4000));

        assertTrue(attempt.isFailure(),
                "可压缩区间存在但摘要为空，属于通路故障，实际结论=" + attempt.outcome());
    }
}
