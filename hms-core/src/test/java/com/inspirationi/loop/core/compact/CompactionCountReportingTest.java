package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.core.TokenTracker;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩结果里的 messagesBefore 必须是压缩前的真实条数。
 * <p>
 * {@code AutoCompactManager.succeed} 曾先调 {@code historyReplacer.accept(after)}
 * 再读 {@code before.size()}。而 {@code before} 传进来的就是调用方的历史列表本身
 * （{@code historySupplier.get()} 返回的同一引用），替换实现又是就地
 * {@code clear() + addAll()}（见 {@code AgentLoop.replaceHistory}）—— 于是读到的
 * "before" 已是替换之后的长度，事件里 messagesBefore 恒等于 messagesAfter。
 * <p>
 * 症状是日志与 SSE 事件里出现 {@code FULL compact: 4 → 4 messages}：压缩明明成功
 * 了，报出来却像没压。使用方靠这两个数字判断压缩效果，拿到的却是恒等值。
 */
class CompactionCountReportingTest {

    /** 只返回摘要文本的模型 —— 让 SessionMemory/Full 压缩能走通摘要通路。 */
    private static ChatModel summarizingModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("SUMMARY"), ChatGenerationMetadata.NULL)));
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
     * 走 {@code autoCompactIfNeeded} 的完整路径，用就地替换的 replacer 模拟
     * AgentLoop 的真实行为，断言事件报的 messagesBefore 是压缩前的条数。
     */
    @Test
    void messagesBeforeIsCapturedBeforeHistoryIsReplaced() {
        TokenTracker tracker = new TokenTracker();
        // 顶到有效窗口的 95%，跨过 93% 阈值，逼出深度压缩
        long promptTokens = (long) (tracker.getEffectiveWindow() * 0.95);
        tracker.recordUsage(promptTokens, 10);

        AutoCompactManager manager = new AutoCompactManager(summarizingModel(), tracker);
        AtomicReference<CompactionResult> event = new AtomicReference<>();
        manager.setOnCompactionEvent(event::set);

        // 足够长的历史，且都是短消息 —— 微压缩无可裁剪，从而升级到深度压缩
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(new UserMessage("u" + i));
            history.add(new AssistantMessage("a" + i));
        }
        int realBefore = history.size();

        // 就地替换，和 AgentLoop.replaceHistory 一致
        CompactionResult result = manager.autoCompactIfNeeded(() -> history, replacement -> {
            history.clear();
            history.addAll(replacement);
        });

        assertNotNull(result, "上下文已越阈值且历史够长，应当发生压缩");
        assertTrue(result.success(), "本用例期望压缩成功：" + result.reason());
        assertEquals(realBefore, result.messagesBefore(),
                "messagesBefore 必须是压缩前的真实条数。若等于 messagesAfter，"
                        + "说明它在 historyReplacer 就地改写历史之后才被读取");
        assertTrue(result.messagesAfter() < result.messagesBefore(),
                "压缩成功就该真的减少条数，报告才有意义："
                        + result.messagesBefore() + " → " + result.messagesAfter());
    }
}
