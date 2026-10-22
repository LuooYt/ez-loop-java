package com.inspirationi.loop.api;

import com.inspirationi.loop.sandbox.SandboxManager;
import com.inspirationi.loop.sandbox.SandboxProperties;
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

/**
 * 会话创建失败时的沙箱回滚。
 * <p>
 * 沙箱目录在装配 AgentLoop <b>之前</b>创建，装配中途抛异常时没有任何会话持有它：
 * 目录留在沙箱根下无人认领，也永远不会被空闲清理回收。
 */
class SandboxRollbackTest {

    @TempDir Path temp;

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

    @Test
    void removesSandboxWhenSessionAssemblyFails() throws IOException {
        SandboxProperties props = new SandboxProperties();
        props.setEnabled(true);
        props.setRoot(temp.resolve("sandbox-root"));
        SandboxManager sandboxManager = new SandboxManager(props);
        assertTrue(sandboxManager.enabled(), "测试前提：沙箱根目录可用");

        try (DefaultHmsSessionManager manager = DefaultHmsSessionManager.builder(
                        stubChatModel(), new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .sandboxManager(sandboxManager)
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                // 装配中途失败（提示词供给方抛异常）
                .agentBasePrompt(() -> {
                    throw new IllegalStateException("boom");
                })
                .build()) {

            assertThrows(IllegalStateException.class, () -> manager.createSession("s"));

            try (var entries = Files.list(props.getRoot())) {
                assertEquals(0, entries.count(),
                        "会话装配失败后不得残留无人认领的沙箱目录");
            }
        }
    }
}
