package com.inspirationi.loop.api;

import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.AgentTool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子 Agent 的工具事件必须接进父请求的事件流。
 * <p>
 * 子 Agent 是在 {@code Agent} 这一次工具调用内部同步跑完的：父循环只发「Agent 开始」、
 * 「Agent 结束」两条事件，中间可能是几十次工具调用、好几分钟，而 UI 上只有一个转圈。
 * 用户看到的「卡死」，有相当一部分就是这段时间没有任何输出。
 */
class SubAgentEventSinkTest {

    /**
     * 按调用序号返回固定回复，模拟「父 → 子 → 子收尾 → 父收尾」的确定性时序：
     * <ol>
     *   <li>父循环：调用 Agent 工具</li>
     *   <li>子循环：调用一个不存在的工具（只为产生工具事件）</li>
     *   <li>子循环：给出最终文本，结束</li>
     *   <li>父循环：给出最终文本，结束</li>
     * </ol>
     */
    private static class ScriptedChatModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            int n = calls.incrementAndGet();
            AssistantMessage msg = switch (n) {
                case 1 -> toolCall("Agent", "{\"prompt\":\"去调研一下\"}");
                case 2 -> toolCall("Ghost", "{}");
                default -> new AssistantMessage("done-" + n);
            };
            return new ChatResponse(List.of(new Generation(msg, ChatGenerationMetadata.NULL)));
        }

        private static AssistantMessage toolCall(String name, String args) {
            return AssistantMessage.builder()
                    .content("thinking")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-" + name, "function", name, args)))
                    .build();
        }

        @Override
        public ChatOptions getOptions() {
            return null;
        }
    }

    @Test
    void subAgentToolEventsReachTheParentEventStream() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new AgentTool());

        try (DefaultHmsSessionManager manager = DefaultHmsSessionManager.builder(
                        new ScriptedChatModel(), registry, new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(10)
                .build()) {

            String sessionId = manager.createSession("s");
            List<String> events = new CopyOnWriteArrayList<>();

            // Agent 工具不是只读的，会走权限确认；这里直接放行（本测试不关心权限）
            HmsCallbacks callbacks = new HmsCallbacks() {
                @Override
                public void onToolUse(String toolName, String phase, String input, String result) {
                    events.add(toolName + ":" + phase);
                }

                @Override
                public CompletableFuture<String> onPermissionRequestAsync(String toolName,
                                                                        String description) {
                    return CompletableFuture.completedFuture("allow");
                }
            };

            manager.send(sessionId, "做个复杂任务", callbacks);

            assertTrue(events.contains("Agent:START"), "父循环应发出 Agent 工具的开始事件，实际=" + events);
            assertTrue(events.contains("Ghost:START"),
                    "子 Agent 内部的工具事件必须透传到父请求，实际=" + events);
            assertTrue(events.contains("Ghost:END"),
                    "子 Agent 的工具结束事件同样要透传，实际=" + events);
            assertTrue(events.contains("Agent:END"), "父循环应发出 Agent 工具的结束事件，实际=" + events);
        }
    }
}
