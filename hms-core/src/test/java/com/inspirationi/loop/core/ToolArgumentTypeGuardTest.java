package com.inspirationi.loop.core;

import com.inspirationi.loop.permission.DenialTracker;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolCallbackAdapter;
import com.inspirationi.loop.tool.ToolContext;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具参数类型在入口处被拦截，而不是留给工具实现去抛 {@code ClassCastException}。
 * <p>
 * 关键点不只是「错误文本好看」：这条路径上有多处直接强转（权限确认里的
 * {@code activityDescription}、工具里的 {@code (String) input.get(...)}），
 * 而强转抛出的异常不在工具调用的 try/catch 之内 —— 它会中止整轮 Agent，
 * 模型连「换个类型重发」的机会都没有。所以断言必须落在
 * <b>工具有没有被执行</b> 上，而不只在返回值文本上。
 */
class ToolArgumentTypeGuardTest {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": { "type": "string" },
                "count": { "type": "integer" },
                "dry_run": { "type": "boolean" },
                "tags": { "type": "array" }
              },
              "required": ["command"]
            }""";

    /** 记录是否真的被执行，并把收到的参数存下来。 */
    private static final class RecordingTool implements Tool {
        private final AtomicBoolean executed = new AtomicBoolean();
        private final AtomicReference<Map<String, Object>> received = new AtomicReference<>();

        @Override
        public String name() {
            return "Recording";
        }

        @Override
        public String description() {
            return "records invocation";
        }

        @Override
        public String inputSchema() {
            return SCHEMA;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public String execute(Map<String, Object> input, ToolContext context) {
            executed.set(true);
            received.set(input);
            return "ok: " + input.get("command");
        }
    }

    private static ToolResponseMessage execute(RecordingTool tool, String argumentsJson) {
        ToolCallbackAdapter adapter = new ToolCallbackAdapter(tool, ToolContext.defaultContext());
        AgentToolExecutor executor = new AgentToolExecutor(
                new HookManager(), ToolContext.defaultContext(), new DenialTracker());
        List<ToolCallback> callbacks = List.of(adapter);
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", tool.name(), argumentsJson);
        return executor.executeToolCalls(List.of(call), callbacks, () -> false);
    }

    private static String resultOf(ToolResponseMessage message) {
        return String.valueOf(message.getResponses().get(0).responseData());
    }

    @Test
    void stringParameterReceivingANumberIsRejectedBeforeExecution() {
        RecordingTool tool = new RecordingTool();

        String result = resultOf(execute(tool, "{\"command\": 42}"));

        assertFalse(tool.executed.get(), "类型不对时工具不得执行，否则强转异常会中止整轮 Agent");
        assertTrue(result.startsWith("Error:"), "实际：" + result);
        assertTrue(result.contains("'command'") && result.contains("must be a string"),
                "错误文本应点名参数与期望类型，实际：" + result);
        assertTrue(result.contains("Integer"), "应说明实际收到的类型，实际：" + result);
    }

    /** 数值/布尔允许字符串形式 —— 这是模型常见的写法，各工具也一直容忍。 */
    @Test
    void numericAndBooleanStringsAreStillAccepted() {
        RecordingTool tool = new RecordingTool();

        String result = resultOf(execute(tool, "{\"command\":\"ls\",\"count\":\"5\",\"dry_run\":\"false\"}"));

        assertTrue(tool.executed.get(), "字符串形式的数值/布尔不应被拦，实际返回：" + result);
        assertEquals("ok: ls", result);
    }

    @Test
    void arrayParameterReceivingAStringIsRejected() {
        RecordingTool tool = new RecordingTool();

        String result = resultOf(execute(tool, "{\"command\":\"ls\",\"tags\":\"a,b\"}"));

        assertFalse(tool.executed.get());
        assertTrue(result.contains("'tags'") && result.contains("must be an array"), "实际：" + result);
    }

    /** 未在 schema 里声明的参数不拦 —— 工具可能有意接受扩展参数。 */
    @Test
    void undeclaredParametersAreLeftAlone() {
        RecordingTool tool = new RecordingTool();

        String result = resultOf(execute(tool, "{\"command\":\"ls\",\"extra\":{\"nested\":1}}"));

        assertTrue(tool.executed.get(), "未声明的参数不应触发类型检查，实际：" + result);
        assertEquals("ok: ls", result);
    }

    /** 缺参仍由工具自己报告 —— 它比通用校验更清楚该怎么补。 */
    @Test
    void missingParameterIsNotReportedByTheTypeGuard() {
        RecordingTool tool = new RecordingTool();

        resultOf(execute(tool, "{}"));

        assertTrue(tool.executed.get(), "缺参不在类型检查的职责范围内");
    }
}
