package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 验证 {@link DefaultHmsSessionManager#createSession()} 正确装配了自动压缩链路。
 * <p>
 * 这是一个回归测试：AutoCompactManager 曾经只作为全局 Bean 存在、从未绑定到
 * AgentLoop，导致 README 宣传的三层上下文压缩完全不生效。同时它必须绑定
 * <b>该会话自己的</b> TokenTracker —— 绑到其他实例会让阈值判断恒读到 0。
 */
class SessionAutoCompactWiringTest {

    /** 最小可用的 ChatModel 桩件 —— 只需能被构造并返回默认选项。 */
    private static ChatModel stubChatModel(String model) {
        // ChatOptions.builder() 而非 new DefaultChatOptions()：后者在 Spring AI
        // 2.0 GA 起构造器改为 protected 且需 8 个参数，不再供外部直接实例化。
        ChatOptions options = ChatOptions.builder().model(model).build();
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("stub"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return options;
            }
        };
    }

    private static DefaultHmsSessionManager newManager(ChatModel chatModel) {
        ToolRegistry registry = new ToolRegistry();
        PromptManager promptManager = new DefaultPromptManager(null, "global");
        // idleTimeout 很大、cleanupInterval 很大 —— 测试期间不触发清理
        return new DefaultHmsSessionManager(chatModel, registry, null, promptManager,
                3600, 3600);
    }

    @Test
    void createSessionWiresAutoCompactManager() {
        DefaultHmsSessionManager manager = newManager(stubChatModel("claude-sonnet-4-20250514"));
        String sessionId = manager.createSession("test");

        LoopSession session = manager.getSessionInternal(sessionId);
        AgentLoop loop = session.getAgentLoop();

        assertNotNull(loop.getAutoCompactManager(),
                "createSession 必须为 AgentLoop 装配 AutoCompactManager，"
                        + "否则上下文压缩永不触发");
    }

    @Test
    void autoCompactManagerBindsToTheSameTrackerAsTheSession() {
        DefaultHmsSessionManager manager = newManager(stubChatModel("claude-sonnet-4-20250514"));
        String sessionId = manager.createSession("test");

        LoopSession session = manager.getSessionInternal(sessionId);

        // AgentLoop 与 LoopSession 必须共享同一个 tracker 实例，
        // 压缩器的阈值判断才能读到真实用量。
        assertSame(session.getTokenTracker(), session.getAgentLoop().getTokenTracker(),
                "会话与其 AgentLoop 必须共享同一个 TokenTracker");
    }

    @Test
    void eachSessionGetsItsOwnCompactManagerAndTracker() {
        DefaultHmsSessionManager manager = newManager(stubChatModel("claude-sonnet-4-20250514"));
        String first = manager.createSession("a");
        String second = manager.createSession("b");

        LoopSession s1 = manager.getSessionInternal(first);
        LoopSession s2 = manager.getSessionInternal(second);

        // 会话隔离：两个会话的压缩器与 tracker 都不能是同一实例，
        // 否则一个会话的用量会污染另一个的压缩判断。
        org.junit.jupiter.api.Assertions.assertNotSame(
                s1.getAgentLoop().getAutoCompactManager(),
                s2.getAgentLoop().getAutoCompactManager());
        org.junit.jupiter.api.Assertions.assertNotSame(
                s1.getTokenTracker(), s2.getTokenTracker());
    }

    @Test
    void sessionTrackerPicksUpPricingFromChatModel() {
        // 模型名从 ChatModel 的默认选项读取，用于 estimateCost 定价
        DefaultHmsSessionManager manager = newManager(stubChatModel("claude-3-haiku-20240307"));
        String sessionId = manager.createSession("test");

        var tracker = manager.getSessionInternal(sessionId).getTokenTracker();
        tracker.recordUsage(1_000_000, 0);

        // Haiku 输入定价 $0.25/M —— 若未取到模型名会回退 Sonnet 的 $3/M
        org.junit.jupiter.api.Assertions.assertEquals(0.25, tracker.estimateCost(), 0.001,
                "会话 TokenTracker 应按 ChatModel 的模型名配置定价");
    }
}
