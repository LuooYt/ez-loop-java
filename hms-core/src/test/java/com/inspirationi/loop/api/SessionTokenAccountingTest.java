package com.inspirationi.loop.api;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link HmsResponse} 的 token 字段必须是<b>本轮</b>用量，而非会话累计。
 * <p>
 * 曾经的缺陷：{@code send} 直接把会话级 {@link com.inspirationi.loop.core.TokenTracker}
 * 的累计总量填进响应，并原样传给 {@code MetricsCollector.recordApiCall}。两处后果：
 * <ul>
 *   <li>{@code HmsResponse.promptTokens()} 与其「本轮消耗」的文档语义不符 ——
 *       按 token 计费的上层拿它做单轮计费会逐轮超收；</li>
 *   <li>指标里的会话总量随轮数呈<b>平方级</b>膨胀：每轮 100 token 连发 3 轮，
 *       记录的是 100+200+300=600 而非 300。</li>
 * </ul>
 */
class SessionTokenAccountingTest {

    /** 每次调用都报告固定用量的模型 —— 便于精确断言增量。 */
    private static ChatModel fixedUsageChatModel(long promptTokens, long completionTokens) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                // DefaultUsage(prompt, completion) —— 顺序即「输入, 输出」
                ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                        .usage(new DefaultUsage((int) promptTokens, (int) completionTokens))
                        .build();
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok"), ChatGenerationMetadata.NULL)),
                        metadata);
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ChatOptions.builder().model("claude-sonnet-4-20250514").build();
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

    @Test
    void responseReportsPerTurnTokensNotSessionTotal() {
        try (DefaultHmsSessionManager manager = newManager(fixedUsageChatModel(100, 20))) {
            String sessionId = manager.createSession("s");

            HmsResponse first = manager.send(sessionId, "one");
            assertEquals(100, first.promptTokens(), "首轮输入 token");
            assertEquals(20, first.completionTokens(), "首轮输出 token");

            // 第二轮的响应仍应只报告本轮的 100/20，而不是累计的 200/40
            HmsResponse second = manager.send(sessionId, "two");
            assertEquals(100, second.promptTokens(),
                    "第二轮应报告本轮用量，累计值会让按 token 计费的上层超收");
            assertEquals(20, second.completionTokens(), "第二轮输出 token");

            HmsResponse third = manager.send(sessionId, "three");
            assertEquals(100, third.promptTokens(), "第三轮应报告本轮用量");
        }
    }

    @Test
    void sessionTrackerStillAccumulatesAcrossTurns() {
        try (DefaultHmsSessionManager manager = newManager(fixedUsageChatModel(100, 20))) {
            String sessionId = manager.createSession("s");
            manager.send(sessionId, "one");
            manager.send(sessionId, "two");

            // 单轮增量化不应削弱会话级累计 —— 两者是不同用途
            TokenStats stats = manager.getSessionTokenStats(sessionId);
            assertEquals(200, stats.inputTokens(), "会话级仍应累计输入 token");
            assertEquals(40, stats.outputTokens(), "会话级仍应累计输出 token");
        }
    }

    @Test
    void metricsTotalMatchesSumOfTurnsNotSquared() {
        try (DefaultHmsSessionManager manager = newManager(fixedUsageChatModel(100, 20))) {
            String sessionId = manager.createSession("s");
            manager.send(sessionId, "one");
            manager.send(sessionId, "two");
            manager.send(sessionId, "three");

            var metrics = manager.getSessionMetrics(sessionId).toMap();
            // 破损版本会记成 100+200+300=600（平方级膨胀）
            assertEquals(300L, metrics.get("input_tokens"),
                    "指标应等于各轮之和；累计值逐轮再累加会造成平方级膨胀");
            assertEquals(60L, metrics.get("output_tokens"), "输出 token 同理");
        }
    }
}
