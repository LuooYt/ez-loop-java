package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.api.DefaultHmsSessionManager;
import com.inspirationi.loop.api.DefaultPromptManager;
import com.inspirationi.loop.api.HmsCallbacks;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 纯文本对话（模型不调工具）也必须能触发上下文压缩。
 * <p>
 * 修复前，{@code AgentLoop.run} 的压缩检查只在「执行完工具调用、进入下一次迭代前」
 * 那一段。而没有工具调用的轮次会在更早的
 * {@code if (!result.assistant.hasToolCalls()) break;} 处退出循环，于是永远走不到
 * 压缩检查点 —— 只聊天不用工具的会话，上下文再涨也不会被压缩，直到超出模型窗口
 * 被上游直接拒绝。修法是把检查提到该 break 之前，两条出路都经过它。
 * <p>
 * 原有测试没能发现它，是因为都绕开了这条控制流：
 * {@code AutoCompactTriggerTest} 直接调 {@code manager.autoCompactIfNeeded(...)}，
 * {@code SessionAutoCompactWiringTest} 只断言装配关系，而
 * {@code CompactionEventDeliveryTest} 的 mock 模型「每轮调一次工具」——
 * 恰好一直待在能触发压缩的那条分支上。因此本测试特意走
 * {@code HmsSessionManager.send} 的完整路径，而不是直接调压缩器。
 */
class TextOnlyCompactionTest {

    /** 有效窗口：contextWindowSize(默认 200K) - reservedTokens(20K) */
    private static final long EFFECTIVE_WINDOW = 200_000 - 20_000;

    /**
     * 只回纯文本、从不发起工具调用的模型，每轮都报超阈值的 promptTokens。
     * <p>
     * 用量报到有效窗口的 95%，跨过 93% 的自动压缩阈值 —— 真实场景里这相当于
     * 用户贴了几篇长文档后继续聊天。
     */
    private static ChatModel textOnlyModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                AssistantMessage msg = new AssistantMessage("ok");
                long promptTokens = (long) (EFFECTIVE_WINDOW * 0.95);
                ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                        .usage(new DefaultUsage((int) promptTokens, 10))
                        .build();
                return new ChatResponse(
                        List.of(new Generation(msg, ChatGenerationMetadata.NULL)), metadata);
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

    private static DefaultHmsSessionManager newManager(ChatModel model) {
        return DefaultHmsSessionManager.builder(
                        model, new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(10)
                .build();
    }

    /**
     * 上下文已越过压缩阈值时，纯文本轮次也应触发压缩。
     * <p>
     * 多发几轮：即便压缩逻辑要求「攒够可裁剪的历史」，8 轮之后也早该动作了。
     */
    @Test
    void textOnlyConversationStillCompacts() {
        DefaultHmsSessionManager manager = newManager(textOnlyModel());
        String sessionId = manager.createSession("test");

        List<CompactionResult> compactions = new ArrayList<>();
        HmsCallbacks callbacks = new HmsCallbacks() {
            @Override
            public void onCompaction(CompactionResult result) {
                compactions.add(result);
            }
        };

        for (int i = 0; i < 8; i++) {
            manager.send(sessionId, "第 " + i + " 轮：继续聊，别调工具。", callbacks);
        }

        assertFalse(compactions.isEmpty(),
                "上下文已达有效窗口的 95%（阈值 93%），但 8 轮纯文本对话一次压缩都没发生。"
                        + "AgentLoop 的压缩检查在工具调用分支内，无工具调用的轮次提前 break，"
                        + "永远到不了检查点。");
    }
}
