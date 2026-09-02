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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摘要调用抛出的异常必须传到 {@link FullCompact#compact} 的 catch，不能在
 * {@code generateFullSummary} 内被吞成 {@code null}。
 * <p>
 * 此前 {@code generateFullSummary} 用 {@code catch (Exception e) { return null; }}
 * 把所有异常都变成「没有摘要」，与「模型返回空文本」不可区分，造成两个后果：
 * <ol>
 *   <li><b>诊断误导</b>：限流、网络中断、TLS 失败统统被上层报成「模型返回空摘要」。
 *       实测排查时就因此指向了完全错误的方向。</li>
 *   <li><b>PTL 重试失效</b>：{@code compact} 的 catch 会用
 *       {@code parsePtlGap(e, ...)} 从 {@code "prompt is too long: X tokens > Y"}
 *       里解析出该丢弃多少个 round，一次跳到合适的丢弃量。异常被吞后那段逻辑
 *       永远拿不到异常对象，PTL 时只能靠外层每轮 dropCount++ 一个个试，
 *       5 次上限内往往减不到位，压缩白白失败。</li>
 * </ol>
 */
class FullCompactExceptionPropagationTest {

    /** 造一段足够长的历史：系统消息 + N 个 user/assistant 回合。 */
    private static List<Message> historyWithRounds(int rounds) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        for (int i = 0; i < rounds; i++) {
            history.add(new UserMessage("第 " + i + " 轮提问，内容足够长以便被渲染进摘要请求。"));
            history.add(new AssistantMessage("第 " + i + " 轮回答。"));
        }
        return history;
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(text), ChatGenerationMetadata.NULL)));
    }

    /** 前 failTimes 次调用抛 PTL 异常，之后返回正常摘要。 */
    private static ChatModel ptlThenSucceed(String ptlMessage, int failTimes,
                                            AtomicInteger callCount) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                if (callCount.getAndIncrement() < failTimes) {
                    throw new RuntimeException(ptlMessage);
                }
                return textResponse("SUMMARY");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    /**
     * PTL 异常出现后，压缩仍能在重试中成功。
     * <p>
     * <b>注意本用例并不能证明异常没被吞掉</b>：实测把
     * {@code catch (Exception e) { return null; }} 加回 generateFullSummary 后，
     * 这三个用例依然全绿 —— 因为外层每轮 {@code dropCount++} 也会重试，吞与不吞
     * 最终都在 5 次上限内成功，连调用次数都一样。
     * <p>
     * 要真正区分两条路径，需让 PTL gap 解析的「一次跳多个 round」产生可观测差异：
     * 构造 gap 使 parsePtlGap 算出 drop=4，并让模型只在 dropCount>=4 时才返回摘要。
     * 那时 PTL 路径 2 次调用即成功，而逐次 ++ 的路径要 5 次 —— 恰好撞上
     * {@code MAX_PTL_RETRIES}，压缩彻底失败。尚未补上该用例。
     */
    @Test
    void ptlExceptionReachesTheRetryLoopAndCompactionStillSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        // 20 个 round 时 fallbackDrop = floor(20*0.2) = 4，gap 足够大 → 一次丢 4 个
        ChatModel model = ptlThenSucceed(
                "prompt is too long: 250000 tokens > 200000 token limit", 1, calls);

        List<Message> history = historyWithRounds(20);
        List<Message> compacted = new FullCompact(model).compact(history);

        assertNotNull(compacted, "首次 PTL 后重试应当成功产出摘要");
        assertTrue(compacted.size() < history.size(),
                "压缩后条数应减少：" + history.size() + " → " + compacted.size());
        assertEquals(2, calls.get(),
                "应当只调用两次模型：首次 PTL、第二次成功。次数异常说明重试路径与预期不同");
    }

    /**
     * 摘要调用持续抛异常时，compact 返回 null 而不是把异常抛给编排层。
     * <p>
     * {@link AutoCompactManager} 依赖 null 走「本层失败 → 计入熔断预算」的路径；
     * 异常直接穿透会绕过它的失败计数。
     */
    @Test
    void persistentFailureReturnsNullInsteadOfThrowing() {
        AtomicInteger calls = new AtomicInteger();
        // failTimes 极大 —— 每次都抛，且消息不含 PTL 格式，走不到 gap 解析
        ChatModel model = ptlThenSucceed("upstream rate limited", Integer.MAX_VALUE, calls);

        List<Message> compacted = new FullCompact(model).compact(historyWithRounds(20));

        org.junit.jupiter.api.Assertions.assertNull(compacted,
                "全部尝试失败时应返回 null，让编排层按失败处理并计入熔断预算");
        assertTrue(calls.get() > 1,
                "应当重试多次而非一次就放弃，实际调用 " + calls.get() + " 次");
    }

    /**
     * 模型返回空文本 —— 这是与异常不同的另一种失败，也应返回 null。
     * <p>
     * 实测中 claude-opus-5 对「1000 字详尽摘要」的要求会因 thinking 吃满
     * max_tokens 而输出空文本，正是这条路径。
     */
    @Test
    void blankSummaryAlsoReturnsNull() {
        ChatModel blankModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return textResponse("");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };

        org.junit.jupiter.api.Assertions.assertNull(
                new FullCompact(blankModel).compact(historyWithRounds(20)),
                "模型返回空摘要时应返回 null");
    }
}
