package com.inspirationi.loop.api;

import com.inspirationi.loop.core.TokenTracker;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文窗口与预留 token 必须能经配置注入，并真正影响压缩阈值。
 * <p>
 * 这两个值原先只能读环境变量 {@code HMS_CORE_CONTEXT_WINDOW}（{@code System.getenv}，
 * 因此只在 JVM 启动时可设），预留 token 更是硬编码 20_000 —— 集成方无法在
 * application.yml 里声明自己模型的真实窗口，只能改启动脚本。现在两者都经
 * {@code hms-core.context-window} / {@code hms-core.reserved-tokens} 注入。
 * <p>
 * 测试落在「配置值 → 压缩阈值」这条链路上，而不只是断言 getter 回读：后者即便
 * 配置没接进会话的 TokenTracker 也能通过。
 */
class ContextWindowConfigTest {

    private static ChatModel stubChatModel() {
        ChatOptions options = ChatOptions.builder().model("claude-sonnet-4-20250514").build();
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

    private static DefaultHmsSessionManager.Builder baseBuilder() {
        return DefaultHmsSessionManager.builder(
                        stubChatModel(), new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600);
    }

    /** 配置的窗口与预留值必须落到会话自己的 TokenTracker 上。 */
    @Test
    void configuredWindowAndReserveReachTheSessionTracker() {
        DefaultHmsSessionManager manager = baseBuilder()
                .contextWindow(60_000)
                .reservedTokens(10_000)
                .build();

        TokenTracker tracker = manager.getSessionInternal(manager.createSession("t"))
                .getTokenTracker();

        assertEquals(60_000, tracker.getContextWindowSize(),
                "yml 配置的 context-window 应传递到会话的 TokenTracker");
        assertEquals(10_000, tracker.getReservedTokens(),
                "yml 配置的 reserved-tokens 应传递到会话的 TokenTracker");
        assertEquals(50_000, tracker.getEffectiveWindow(),
                "有效窗口 = 窗口 - 预留");
    }

    /**
     * 配置真的改变了压缩行为，而不只是改了个可回读的数字。
     * <p>
     * 同一个 prompt token 数（40_000）在小窗口下越过阈值、在默认窗口下远未触及。
     */
    @Test
    void configuredWindowActuallyShiftsTheCompactionThreshold() {
        // 小窗口：有效窗口 50_000，阈值 46_500 —— 40_000 未及阈值
        TokenTracker small = new TokenTracker(60_000, 10_000);
        small.recordUsage(40_000, 10);
        assertFalse(small.shouldAutoCompact(),
                "40_000 < 阈值 46_500，不该触发压缩");

        // 更小的窗口：有效窗口 30_000，阈值 27_900 —— 40_000 已越阈值
        TokenTracker tiny = new TokenTracker(40_000, 10_000);
        tiny.recordUsage(40_000, 10);
        assertTrue(tiny.shouldAutoCompact(),
                "40_000 > 阈值 27_900，应触发压缩 —— 若失败说明窗口配置没影响阈值计算");

        // 默认 200K 窗口：阈值 167_400 —— 同样的用量远未触及
        TokenTracker large = new TokenTracker(200_000, 20_000);
        large.recordUsage(40_000, 10);
        assertFalse(large.shouldAutoCompact(),
                "默认窗口下 40_000 远未触及阈值 167_400");
    }

    /**
     * 非法配置回退到默认值，而不是让压缩逻辑失效。
     * <p>
     * 预留值 ≥ 窗口会让有效窗口归零或为负，占用率恒为 0 —— 症状是「上下文涨到
     * 超限却从不压缩」，静默接受这种配置极难定位。
     */
    @Test
    void invalidConfigFallsBackInsteadOfBreakingCompaction() {
        // 预留 ≥ 窗口
        TokenTracker absurd = new TokenTracker(10_000, 50_000);
        assertEquals(TokenTracker.DEFAULT_RESERVED_TOKENS, absurd.getReservedTokens(),
                "预留 ≥ 窗口时应回退到默认预留，否则有效窗口归零、压缩永不触发");
        assertTrue(absurd.getEffectiveWindow() != 0,
                "有效窗口不能为 0 —— 那会让占用率恒为 0");

        // 非正数窗口
        TokenTracker zero = new TokenTracker(0, 0);
        assertEquals(TokenTracker.DEFAULT_CONTEXT_WINDOW, zero.getContextWindowSize(),
                "窗口 <= 0 应回退到默认窗口，否则占用率计算会除零");
        assertEquals(TokenTracker.DEFAULT_RESERVED_TOKENS, zero.getReservedTokens());
    }

    /** 不配置时保持既有默认值 —— 这条改动不能让现有部署的行为发生位移。 */
    @Test
    void defaultsAreUnchanged() {
        TokenTracker tracker = new TokenTracker(
                TokenTracker.DEFAULT_CONTEXT_WINDOW, TokenTracker.DEFAULT_RESERVED_TOKENS);
        assertEquals(200_000, tracker.getContextWindowSize());
        assertEquals(20_000, tracker.getReservedTokens());
        assertEquals(180_000, tracker.getEffectiveWindow());
    }
}
