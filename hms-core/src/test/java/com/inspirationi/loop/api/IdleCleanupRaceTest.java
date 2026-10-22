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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 空闲清理的「先检查后执行」竞态。
 * <p>
 * {@code cleanupIdleSessions} 先读 {@code isExecuting()} / {@code idleSeconds()}，再无条件
 * {@code remove} 并销毁。两者之间进来的请求（touch 刷新时间、beginRequest 置为执行中）
 * 会被连带回收，调用方拿到截断结果。判断与移除必须在同一次原子操作里重新校验。
 */
class IdleCleanupRaceTest {

    private static ChatModel stubChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("stub"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    private static DefaultHmsSessionManager newManager() {
        return DefaultHmsSessionManager.builder(
                        stubChatModel(), new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .build();
    }

    /** 阈值传 -1，任何会话在时间上都算「空闲」，把重点放在执行状态上。 */
    @Test
    void doesNotRemoveSessionThatBecameExecutingBeforeRemoval() {
        try (DefaultHmsSessionManager manager = newManager()) {
            String id = manager.createSession("prompt");
            LoopSession session = manager.getSessionInternal(id);
            assertNotNull(session);

            // 清理线程已经判定「空闲」，此时请求进入执行 —— 不得回收
            session.beginRequest();

            assertFalse(manager.tryRemoveIdleSession(id, session, -1),
                    "执行中的会话不得被移除");
            assertNotNull(manager.getSessionInternal(id), "会话必须还在映射里");

            session.endRequest();

            assertTrue(manager.tryRemoveIdleSession(id, session, -1),
                    "真正空闲后应能被移除");
            assertNull(manager.getSessionInternal(id));
        }
    }

    /** 会话已被替换成新实例时，旧快照不得删掉新会话。 */
    @Test
    void doesNotRemoveWhenSessionInstanceChanged() {
        try (DefaultHmsSessionManager manager = newManager()) {
            String id = manager.createSession("prompt");
            LoopSession stale = manager.getSessionInternal(id);
            assertNotNull(stale);

            assertFalse(manager.tryRemoveIdleSession(id, new LoopSession(
                    id, stale.getAgentLoop(), stale.getTokenTracker(),
                    stale.getMetricsCollector(), null), -1),
                    "实例不匹配时不得删除");

            assertNotNull(manager.getSessionInternal(id));
        }
    }
}
