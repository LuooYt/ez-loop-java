package com.inspirationi.loop.core;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentLoop} 的消息历史一致性。
 * <p>
 * 每个 {@code AssistantMessage.ToolCall} 必须有一条 id 相同的
 * {@code ToolResponseMessage.ToolResponse} 与之配对 —— 这是 Anthropic / OpenAI
 * 两家 API 的硬性要求，缺失或多余都会让下一轮请求被服务端拒绝（400）。
 * 循环因取消、Hook 中止、未知工具等原因提前结束时最容易破坏该配对。
 */
class AgentLoopHistoryTest {

    /** 按脚本返回响应的 ChatModel：第 N 次调用返回 responses[N]。 */
    private static class ScriptedChatModel implements ChatModel {
        private final List<AssistantMessage> responses;
        private final AtomicInteger callCount = new AtomicInteger();

        ScriptedChatModel(List<AssistantMessage> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int idx = callCount.getAndIncrement();
            AssistantMessage msg = idx < responses.size()
                    ? responses.get(idx)
                    : new AssistantMessage("done");
            return new ChatResponse(List.of(new Generation(msg, ChatGenerationMetadata.NULL)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }

        int calls() {
            return callCount.get();
        }
    }

    private static AssistantMessage withToolCalls(String text, String... toolNames) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < toolNames.length; i++) {
            calls.add(new AssistantMessage.ToolCall(
                    "call-" + toolNames[i] + "-" + i, "function", toolNames[i], "{}"));
        }
        return AssistantMessage.builder().content(text).toolCalls(calls).build();
    }

    private static Tool echoTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "echo";
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\"}";
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public String execute(Map<String, Object> input, ToolContext context) {
                return "result of " + name;
            }
        };
    }

    /** 校验历史中每个 toolCall 都有同 id 的 toolResponse 配对。 */
    private static void assertToolCallsArePaired(List<Message> history) {
        List<String> callIds = new ArrayList<>();
        List<String> responseIds = new ArrayList<>();
        for (Message m : history) {
            if (m.getMessageType() == MessageType.ASSISTANT) {
                for (var tc : ((AssistantMessage) m).getToolCalls()) {
                    callIds.add(tc.id());
                }
            } else if (m.getMessageType() == MessageType.TOOL) {
                for (var r : ((ToolResponseMessage) m).getResponses()) {
                    responseIds.add(r.id());
                }
            }
        }
        assertEquals(callIds, responseIds,
                "每个 toolCall 必须有 id 相同的 toolResponse 配对（API 硬性要求），"
                        + "\n  calls    = " + callIds
                        + "\n  responses= " + responseIds);
    }

    private static AgentLoop newLoop(ChatModel model, Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(tools);
        ToolContext context = ToolContext.defaultContext();
        return new AgentLoop(model, registry, context, "system prompt");
    }

    @Test
    void toolCallsArePairedInNormalFlow() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                withToolCalls("using tools", "Alpha", "Beta"),
                new AssistantMessage("final answer")));

        AgentLoop loop = newLoop(model, echoTool("Alpha"), echoTool("Beta"));
        String result = loop.run("go");

        assertEquals("final answer", result);
        assertToolCallsArePaired(loop.copyMessageHistory());
    }

    @Test
    void unknownToolStillProducesAPairedResponse() {
        // 模型幻觉出一个不存在的工具 —— 仍必须回一条 toolResponse，否则下轮请求被拒
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                withToolCalls("calling ghost", "NonexistentTool"),
                new AssistantMessage("recovered")));

        AgentLoop loop = newLoop(model, echoTool("Alpha"));
        loop.run("go");

        assertToolCallsArePaired(loop.copyMessageHistory());
    }

    @Test
    void cancellationBeforeToolExecutionKeepsHistoryPaired() {
        // 取消发生在工具批次执行前：每个未执行的工具也必须回一条「已取消」响应
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                withToolCalls("using tools", "Alpha", "Beta", "Gamma")));

        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(echoTool("Alpha"), echoTool("Beta"), echoTool("Gamma"));
        AgentLoop loop = new AgentLoop(model, registry, ToolContext.defaultContext(), "sys");

        // 在第一个工具执行时触发取消
        loop.setOnToolEvent(event -> {
            if (event.phase() == AgentLoop.ToolEvent.Phase.START) {
                loop.cancel();
            }
        });

        loop.run("go");
        assertToolCallsArePaired(loop.copyMessageHistory());
    }

    @Test
    void throwingToolDoesNotLeaveHistoryUnpaired() {
        // 工具抛异常时，AgentToolExecutor 会向上抛 —— 此时已写入历史的
        // assistant 消息若没有对应的 toolResponse，会话就永久损坏：
        // 后续每次 send 都会带着这段残缺历史请求 API 并被拒绝。
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                withToolCalls("calling bomb", "Bomb")));

        Tool bomb = new Tool() {
            @Override
            public String name() {
                return "Bomb";
            }

            @Override
            public String description() {
                return "throws";
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\"}";
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public String execute(Map<String, Object> input, ToolContext context) {
                throw new IllegalStateException("tool exploded");
            }
        };

        AgentLoop loop = newLoop(model, bomb);
        try {
            loop.run("go");
        } catch (RuntimeException expected) {
            // 工具异常向上传播是当前设计
        }

        // 无论是否抛出，历史都不能处于「有 toolCall 无 toolResponse」的状态
        assertToolCallsArePaired(loop.copyMessageHistory());
    }

    @Test
    void systemPromptStaysAtIndexZeroAfterUpdate() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new AssistantMessage("hi")));
        AgentLoop loop = newLoop(model, echoTool("Alpha"));
        loop.run("first");

        loop.updateSystemPrompt("new system prompt");
        List<Message> history = loop.copyMessageHistory();

        assertEquals(MessageType.SYSTEM, history.get(0).getMessageType(),
                "更新后系统消息必须仍在索引 0");
        assertTrue(history.get(0).getText().contains("new system prompt"));
        assertTrue(history.size() > 1, "更新提示词不应清空既有对话历史");
    }

    @Test
    void resetKeepsOnlySystemMessage() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                withToolCalls("t", "Alpha"),
                new AssistantMessage("done")));
        AgentLoop loop = newLoop(model, echoTool("Alpha"));
        loop.run("go");
        assertTrue(loop.copyMessageHistory().size() > 1);

        loop.reset();
        List<Message> history = loop.copyMessageHistory();
        assertEquals(1, history.size(), "reset 后应只剩系统消息");
        assertEquals(MessageType.SYSTEM, history.get(0).getMessageType());
    }

    @Test
    void maxIterationsGuardStopsRunawayLoop() {
        // 模型永远返回工具调用 —— 必须被 MAX_ITERATIONS 截断，且历史保持配对
        List<AssistantMessage> alwaysToolCalls = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            alwaysToolCalls.add(withToolCalls("loop " + i, "Alpha"));
        }
        ScriptedChatModel model = new ScriptedChatModel(alwaysToolCalls);

        AgentLoop loop = newLoop(model, echoTool("Alpha"));
        String result = loop.run("go");

        assertTrue(model.calls() <= 50,
                "不应超过 MAX_ITERATIONS 次 API 调用，实际 " + model.calls());
        assertFalse(result.isBlank(), "达到迭代上限应返回带警告的文本");
        assertToolCallsArePaired(loop.copyMessageHistory());
    }

    @Test
    void lastToolCallCountResetsBetweenRuns() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                withToolCalls("t", "Alpha", "Beta"),
                new AssistantMessage("first done"),
                new AssistantMessage("second done")));

        AgentLoop loop = newLoop(model, echoTool("Alpha"), echoTool("Beta"));
        loop.run("first");
        assertEquals(2, loop.getLastToolCallCount());

        // 第二轮没有工具调用，计数必须归零而非累加
        loop.run("second");
        assertEquals(0, loop.getLastToolCallCount(),
                "每轮 run 应重置工具调用计数");
    }
}
