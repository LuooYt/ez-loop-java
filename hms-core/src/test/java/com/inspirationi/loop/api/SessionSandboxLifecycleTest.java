package com.inspirationi.loop.api;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxManager;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionSandboxLifecycleTest {

    @TempDir Path temp;

    private static ChatModel stubChatModel() {
        ChatOptions options = ChatOptions.builder().model("stub-model").build();
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

    private SandboxManager sandboxManager(boolean cleanup) throws IOException {
        SandboxProperties props = new SandboxProperties();
        props.setEnabled(true);
        props.setRoot(Files.createDirectories(temp.resolve("sandbox-root")));
        props.setCleanupOnDestroy(cleanup);
        return new SandboxManager(props);
    }

    private DefaultHmsSessionManager newManager(SandboxManager sandboxManager, int maxSessions) {
        return DefaultHmsSessionManager.builder(
                        stubChatModel(), new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(maxSessions)
                .sandboxManager(sandboxManager)
                .build();
    }

    @Test
    void sessionCreatesSandboxAndExposesItToContext() throws IOException {
        SandboxManager sandboxes = sandboxManager(false);
        try (DefaultHmsSessionManager manager = newManager(sandboxes, 10)) {
            String sessionId = manager.createSession("hello");

            assertTrue(Files.isDirectory(temp.resolve("sandbox-root").resolve(sessionId)),
                    "会话创建时必须建出 root/<sessionId>");

            ToolContext ctx = manager.getSessionInternal(sessionId).getAgentLoop().getToolContext();
            Object sandbox = ctx.get(Sandbox.STATE_KEY);
            assertInstanceOf(Sandbox.class, sandbox, "会话上下文必须能读到沙箱");
            assertEquals(sessionId, ((Sandbox) sandbox).root().getFileName().toString());
        }
    }

    @Test
    void destroyedSessionRemovesRegistryEntryButKeepsDirectory() throws IOException {
        SandboxManager sandboxes = sandboxManager(false);
        try (DefaultHmsSessionManager manager = newManager(sandboxes, 10)) {
            String sessionId = manager.createSession("hello");
            manager.destroySession(sessionId);

            assertTrue(sandboxes.get(sessionId).isEmpty(), "销毁后管理器不应再持有沙箱");
            assertTrue(Files.isDirectory(temp.resolve("sandbox-root").resolve(sessionId)),
                    "cleanup=false 时必须保留会话目录");
        }
    }

    @Test
    void rejectedSessionRollbackDeletesSandboxDirectory() throws IOException {
        SandboxManager sandboxes = sandboxManager(false);
        try (DefaultHmsSessionManager manager = newManager(sandboxes, 1)) {
            String first = manager.createSession("a");
            assertThrows(HmsException.class, () -> manager.createSession("b"));

            // 容量超限时被拒会话的目录必须删掉，且不能误伤已存在的会话目录
            assertEquals(1, sandboxesCount(temp.resolve("sandbox-root")),
                    "回滚后只应剩下已成功会话的目录");
            assertTrue(Files.isDirectory(temp.resolve("sandbox-root").resolve(first)));
        }
    }

    @Test
    void idleCleanupUsesTheSameDestroyPath() throws Exception {
        SandboxManager sandboxes = sandboxManager(true);
        try (DefaultHmsSessionManager manager = newManager(sandboxes, 10)) {
            String sessionId = manager.createSession("idle");
            Thread.sleep(1_100); // idleSeconds 以秒计，确保 > 0

            assertEquals(1, manager.cleanupIdleSessions(0));
            assertFalse(manager.sessionExists(sessionId));
            assertTrue(sandboxes.get(sessionId).isEmpty(), "空闲回收必须走同一销毁路径");
            assertFalse(Files.exists(temp.resolve("sandbox-root").resolve(sessionId)),
                    "cleanup=true 时空闲回收也应删除目录");
        }
    }

    private static long sandboxesCount(Path root) throws IOException {
        try (var stream = Files.list(root)) {
            return stream.count();
        }
    }
}
