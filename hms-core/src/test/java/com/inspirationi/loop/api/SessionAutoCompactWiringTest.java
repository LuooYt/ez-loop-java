package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.telemetry.BuiltinModelPricing;
import com.inspirationi.loop.telemetry.TokenPricing;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            public ChatOptions getOptions() {
                return options;
            }
        };
    }

    private static DefaultHmsSessionManager newManager(ChatModel chatModel) {
        ToolRegistry registry = new ToolRegistry();
        PromptManager promptManager = new DefaultPromptManager(null, "global");
        // idleTimeout 很大、cleanupInterval 很大 —— 测试期间不触发清理
        return DefaultHmsSessionManager.builder(chatModel, registry, promptManager)
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .build();
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

    /**
     * 费用按 ChatModel 的模型名计算。
     * <p>
     * 模型名不再由 {@code createSession} 写进 TokenTracker（那个
     * {@code setModel} 已废弃）—— 定价改由 {@link TokenPricing} 承担，模型名在
     * 算费时从 {@code ChatModel.getOptions()} 现取。这样运行时换了模型，费用会跟着
     * 变，而不是沿用会话创建那一刻的快照。
     */
    @Test
    void sessionCostUsesModelNameFromChatModel() {
        DefaultHmsSessionManager manager = DefaultHmsSessionManager
                .builder(stubChatModel("claude-3-haiku-20240307"),
                        new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .tokenPricing(new BuiltinModelPricing())
                .build();
        String sessionId = manager.createSession("test");

        manager.getSessionInternal(sessionId).getTokenTracker().recordUsage(1_000_000, 0);

        TokenStats stats = manager.getSessionTokenStats(sessionId);
        // Haiku 输入定价 $0.25/M；取不到模型名则费用为 null（而非按某个默认价目表估）
        assertNotNull(stats.cost(), "应当算出费用 —— 为 null 说明模型名没传到定价环节");
        assertEquals(0, new BigDecimal("0.25").compareTo(stats.cost()),
                "应按 Haiku 价目表计费，实际 " + stats.cost());
        assertEquals("claude-3-haiku-20240307", stats.pricingModel(),
                "应注明算费所用的模型 —— 金额没有依据无法核对");
    }

    /** 未注入 TokenPricing 时，token 照常记账，但费用呈现为「未知」而非 0。 */
    @Test
    void withoutPricingCostIsUnknownRatherThanZero() {
        DefaultHmsSessionManager manager = newManager(stubChatModel("claude-3-haiku-20240307"));
        String sessionId = manager.createSession("test");
        manager.getSessionInternal(sessionId).getTokenTracker().recordUsage(1_000_000, 0);

        TokenStats stats = manager.getSessionTokenStats(sessionId);
        assertEquals(1_000_000, stats.inputTokens(), "未配定价不应影响 token 记账");
        org.junit.jupiter.api.Assertions.assertNull(stats.cost(),
                "未注入 TokenPricing 时费用应为 null（未知），不能是 0 —— "
                        + "否则「没配价目表」会被读成「没花钱」");
    }
}
