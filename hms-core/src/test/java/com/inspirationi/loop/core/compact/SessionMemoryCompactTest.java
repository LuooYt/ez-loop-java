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

        List<Message> result = compact.getCompactedHistory(h);

        assertNotNull(result, "历史远超上限时 Session Memory 压缩必须生效");
        assertEquals(1, calls.get(), "应调用一次模型生成摘要");
        assertTrue(result.size() < h.size(), "压缩后消息数应减少");
        assertTrue(result.get(1) instanceof SystemMessage sm
                        && sm.getText().startsWith("[Conversation Summary]"),
                "压缩结果第二条应是摘要");
    }

    /**
     * <b>缺陷验证</b>：历史落在 MIN_KEEP_TOKENS(10K) 与 MAX_KEEP_TOKENS(40K)
     * 之间时，压缩静默失效。
     * <p>
     * {@code findKeepStart} 只在 {@code estimatedTokens >= MAX_KEEP_TOKENS} 时
     * 才提前返回；总量够不到 40K 就一路扫到 {@code minStart} 并返回它，
     * 使 {@code keepStart - compressibleStart == 0}，随后被
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

        List<Message> result = compact.getCompactedHistory(h);

        assertNotNull(result,
                "历史约 " + total + " token（超过 MIN_KEEP 两倍以上、60 条消息）时，"
                        + "Session Memory 压缩应当生效；返回 null 会让编排层跳到"
                        + "付费的 FullCompact，白付一次全量摘要且丢弃更多上下文");
        assertTrue(result.size() < h.size(), "压缩后消息数应减少");
    }
}
