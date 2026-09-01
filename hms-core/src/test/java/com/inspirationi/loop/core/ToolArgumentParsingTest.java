package com.inspirationi.loop.core;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具参数解析的边界语义。
 * <p>
 * 参数只应被解析一次（权限评估与工具执行共用同一份结果），且必须能区分两种情况：
 * <ul>
 *   <li>{@code "{}"} —— 合法的「无参数」，工具应正常执行</li>
 *   <li>坏 JSON —— 模型输出有问题，<b>不得</b>用空参数硬跑，否则工具在缺输入的
 *       情况下产出一个看似正常的结果，而模型收不到任何纠错信号</li>
 * </ul>
 */
class ToolArgumentParsingTest {

    /** 记录自己收到的参数与执行次数的工具。 */
    private static class RecordingTool implements Tool {
        final AtomicInteger executions = new AtomicInteger();
        final AtomicReference<Map<String, Object>> lastInput = new AtomicReference<>();

        @Override
        public String name() {
            return "Recorder";
        }

        @Override
        public String description() {
            return "records its input";
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.READ_ONLY;   // 避免触发权限确认，聚焦参数解析
        }

        @Override
        public String execute(Map<String, Object> input, ToolContext context) {
            executions.incrementAndGet();
            lastInput.set(input);
            return "recorded";
        }
    }

    /** 第一轮发起一次工具调用，第二轮结束循环。 */
    private static ChatModel modelCallingToolWith(String arguments) {
        AtomicInteger calls = new AtomicInteger();
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                AssistantMessage msg = calls.getAndIncrement() == 0
                        ? AssistantMessage.builder()
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "call-1", "function", "Recorder", arguments)))
                                .build()
                        : new AssistantMessage("done");
                return new ChatResponse(List.of(new Generation(msg, ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ChatOptions.builder().model("stub").build();
            }
        };
    }

    private static AgentLoop loopWith(RecordingTool tool, String arguments) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return new AgentLoop(modelCallingToolWith(arguments), registry,
                ToolContext.defaultContext(), "sys");
    }

    /** 找到本轮唯一那条工具响应文本。 */
    private static String toolResponseText(AgentLoop loop) {
        for (var msg : loop.copyMessageHistory()) {
            if (msg instanceof ToolResponseMessage trm && !trm.getResponses().isEmpty()) {
                return trm.getResponses().getFirst().responseData();
            }
        }
        return null;
    }

    @Test
    void emptyObjectIsAValidNoArgCall() {
        RecordingTool tool = new RecordingTool();
        AgentLoop loop = loopWith(tool, "{}");

        loop.run("go");

        assertEquals(1, tool.executions.get(), "\"{}\" 是合法的无参调用，工具应执行");
        assertNotNull(tool.lastInput.get());
        assertTrue(tool.lastInput.get().isEmpty(), "参数应为空 Map");
        assertEquals("recorded", toolResponseText(loop));
    }

    @Test
    void malformedJsonDoesNotExecuteTheTool() {
        RecordingTool tool = new RecordingTool();
        AgentLoop loop = loopWith(tool, "{not valid json");

        loop.run("go");

        assertEquals(0, tool.executions.get(),
                "坏 JSON 不得以空参数执行工具 —— 那会产出看似正常却缺输入的结果");
    }

    @Test
    void malformedJsonIsReportedBackToTheModel() {
        RecordingTool tool = new RecordingTool();
        AgentLoop loop = loopWith(tool, "{not valid json");

        loop.run("go");

        String response = toolResponseText(loop);
        assertNotNull(response, "必须回传一条工具响应，否则 tool_use/tool_result 配对断裂");
        assertTrue(response.startsWith("Error:"),
                "响应应以 Error: 开头，让模型知道要重发；实际：" + response);
        assertTrue(response.contains("Recorder"), "响应应指明是哪个工具的参数有问题");
    }

    @Test
    void argumentsAreParsedOnceAndReachTheToolIntact() {
        RecordingTool tool = new RecordingTool();
        AgentLoop loop = loopWith(tool, "{\"path\":\"/tmp/a.txt\",\"lines\":10}");

        loop.run("go");

        Map<String, Object> input = tool.lastInput.get();
        assertNotNull(input);
        assertEquals("/tmp/a.txt", input.get("path"), "字符串参数应原样到达工具");
        assertEquals(10, input.get("lines"), "数值参数应保持类型");
    }
}
