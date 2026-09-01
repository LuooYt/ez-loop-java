package com.inspirationi.loop.api;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空闲清理不得回收<b>正在执行</b>的会话。
 * <p>
 * 曾经的缺陷：{@code lastAccessTime} 只在请求进入时（{@code requireSession} 里的
 * {@code touch()}）刷新一次，之后整个请求期间都不再更新。于是任何运行时长超过
 * 空闲阈值的请求 —— 长工具链、深度压缩、等待用户回答（默认上限就有 300 秒）——
 * 在执行途中就会被清理线程判定为「已空闲」，进而 {@code destroy()}：
 * AgentLoop 被取消、会话被移出映射，而调用方的 {@code send} 仍在阻塞，
 * 最终拿到一个被截断的结果，且之后对该 sessionId 的所有操作都报「会话不存在」。
 */
class SessionIdleCleanupTest {

    /** 卡在 {@code call()} 里的模型 —— 制造一个确定的「执行中」窗口。 */
    private static class BlockingChatModel implements ChatModel {
        final CountDownLatch inCall = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);

        @Override
        public ChatResponse call(Prompt prompt) {
            inCall.countDown();
            try {
                // 有上限，避免断言失败时挂住整个测试套件
                proceed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("done"), ChatGenerationMetadata.NULL)));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().model("claude-sonnet-4-20250514").build();
        }
    }

    private static ChatModel instantChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("done"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return ChatOptions.builder().model("claude-sonnet-4-20250514").build();
            }
        };
    }

    /**
     * 让「所有会话都算空闲」的阈值。
     * <p>
     * 判定是 {@code idleSeconds() > idleTimeoutSeconds}，而 {@code idleSeconds()}
     * 取整到秒 —— 刚创建的会话是 0，传 0 时 {@code 0 > 0} 为假，会一个都不清理。
     * 那样连破损实现也能通过「不该清理」的断言（假通过）。取 -1 使条件恒真，
     * 于是清理与否完全取决于执行中豁免，无需 sleep 拖慢测试。
     */
    private static final long ALL_IDLE = -1;

    /** 清理间隔取足够大的值 —— 测试直接手工调用 cleanupIdleSessions，不依赖调度。 */
    private static DefaultHmsSessionManager newManager(ChatModel model) {
        return DefaultHmsSessionManager.builder(
                        model, new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(10)
                .build();
    }

    @Test
    void inFlightSessionSurvivesIdleCleanup() throws Exception {
        BlockingChatModel model = new BlockingChatModel();

        try (DefaultHmsSessionManager manager = newManager(model)) {
            String sessionId = manager.createSession("s");

            Thread worker = Thread.ofVirtual().start(() -> manager.send(sessionId, "go"));
            assertTrue(model.inCall.await(5, TimeUnit.SECONDS), "模型应已被调用");

            // 除执行中豁免外，一切会话都该被判定为空闲
            int cleaned = manager.cleanupIdleSessions(ALL_IDLE);

            // 无论断言结果如何都要放行，避免卡住套件
            model.proceed.countDown();

            assertEquals(0, cleaned, "执行中的会话不应被清理");
            assertTrue(manager.sessionExists(sessionId),
                    "执行中的会话被清理会让调用方的 send 拿到截断结果");

            worker.join(15_000);
        }
    }

    @Test
    void idleSessionIsStillCleanedAfterRequestCompletes() {
        try (DefaultHmsSessionManager manager = newManager(instantChatModel())) {
            String sessionId = manager.createSession("s");
            manager.send(sessionId, "go");

            // 请求已结束 → 豁免解除，照常按空闲回收
            assertEquals(1, manager.cleanupIdleSessions(ALL_IDLE),
                    "请求结束后的会话应能正常被清理（豁免不得泄漏成永久保留）");
            assertTrue(!manager.sessionExists(sessionId));
        }
    }

    @Test
    void neverUsedSessionIsCleanedNormally() {
        try (DefaultHmsSessionManager manager = newManager(instantChatModel())) {
            manager.createSession("a");
            manager.createSession("b");

            assertEquals(2, manager.cleanupIdleSessions(ALL_IDLE),
                    "从未执行过请求的空闲会话应被清理");
            assertEquals(0, manager.listSessions().size());
        }
    }
}
