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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话容量上限与管理器关闭行为测试。
 */
class SessionLifecycleTest {

    private static ChatModel stubChatModel() {
        // 用 ChatOptions.builder() 而非 new DefaultChatOptions()：后者在 Spring AI
        // 2.0 GA 起构造器改为 protected 且需 8 个参数，不再供外部直接实例化。
        ChatOptions options = ChatOptions.builder().model("claude-sonnet-4-20250514").build();
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

    private static DefaultHmsSessionManager newManager(int maxSessions) {
        return new DefaultHmsSessionManager(stubChatModel(), new ToolRegistry(), null,
                new DefaultPromptManager(null, "global"),
                3600, 3600, 300, maxSessions);
    }

    @Test
    void createSessionRejectsBeyondLimit() {
        try (DefaultHmsSessionManager manager = newManager(3)) {
            manager.createSession("a");
            manager.createSession("b");
            manager.createSession("c");

            HmsException e = assertThrows(HmsException.class,
                    () -> manager.createSession("d"),
                    "超过上限应抛 HmsException 而非静默耗尽内存");
            assertEquals(HmsErrorCode.SESSION_LIMIT_EXCEEDED, e.getErrorCode());
        }
    }

    @Test
    void rejectedSessionDoesNotLeakIntoTheMap() {
        try (DefaultHmsSessionManager manager = newManager(2)) {
            manager.createSession("a");
            manager.createSession("b");
            assertThrows(HmsException.class, () -> manager.createSession("c"));

            // 被拒的会话必须从 map 中移除 —— 容量检查是「先占位再校验」，
            // 回滚不彻底会让后续创建永久失败。
            assertEquals(2, manager.listSessions().size(),
                    "被拒的会话不应残留在会话表中");
        }
    }

    @Test
    void destroyingSessionFreesCapacity() {
        try (DefaultHmsSessionManager manager = newManager(2)) {
            String first = manager.createSession("a");
            manager.createSession("b");
            assertThrows(HmsException.class, () -> manager.createSession("c"));

            manager.destroySession(first);
            // 腾出位置后应能继续创建
            String replacement = manager.createSession("c");
            assertTrue(manager.sessionExists(replacement));
        }
    }

    @Test
    void closeDestroysAllSessions() {
        DefaultHmsSessionManager manager = newManager(10);
        String first = manager.createSession("a");
        String second = manager.createSession("b");

        manager.close();

        assertFalse(manager.sessionExists(first));
        assertFalse(manager.sessionExists(second));
        assertEquals(0, manager.listSessions().size());
    }

    @Test
    void closeIsIdempotent() {
        DefaultHmsSessionManager manager = newManager(10);
        manager.createSession("a");
        manager.close();
        // 重复关闭不应抛异常（容器可能与手动调用叠加）
        manager.close();
        assertEquals(0, manager.listSessions().size());
    }

    @Test
    void closeStopsTheCleanupScheduler() throws Exception {
        DefaultHmsSessionManager manager = newManager(10);
        manager.close();

        // 关闭后调度线程应已终止 —— 否则每次建容器都漏一个线程
        Thread.sleep(100);
        long alive = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("session-cleanup-"))
                .filter(Thread::isAlive)
                .count();
        assertEquals(0, alive, "close() 后不应残留 session-cleanup 线程");
    }
}
