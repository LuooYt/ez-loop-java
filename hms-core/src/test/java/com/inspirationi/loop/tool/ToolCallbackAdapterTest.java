package com.inspirationi.loop.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolCallbackAdapter} 的异常与边界处理。
 * <p>
 * 该适配器是工具执行的最后一道防线：它的返回值会作为 tool_result 回传给模型。
 * 因此返回内容必须<b>对模型有用</b> —— "Error: null" 之类的文本会让模型无从
 * 判断该重试、换参数还是放弃，往往导致它反复调用同一个失败的工具直到迭代耗尽。
 */
class ToolCallbackAdapterTest {

    /** 抛出指定异常的工具。 */
    private static Tool throwingTool(String name, RuntimeException toThrow) {
        return new Tool() {
            @Override
            public String name() {
                return name;
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
                throw toThrow;
            }
        };
    }

    private static String call(Tool tool, String json) {
        return new ToolCallbackAdapter(tool, ToolContext.defaultContext()).call(json);
    }

    @Test
    void exceptionWithMessageIsReported() {
        String result = call(throwingTool("T", new IllegalStateException("disk full")), "{}");
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("disk full"), "应包含原始错误信息，实际：" + result);
    }

    @Test
    void exceptionWithoutMessageStillProducesUsefulText() {
        // NPE 等异常的 getMessage() 常为 null —— 直接拼接会得到 "Error: null"，
        // 模型看到它无法判断该重试、换参数还是放弃。
        String result = call(throwingTool("T", new NullPointerException()), "{}");

        assertNotNull(result);
        assertFalse(result.trim().equals("Error: null"),
                "无 message 的异常不应产出 \"Error: null\" —— 应回退到异常类名，"
                        + "实际：" + result);
        assertTrue(result.contains("NullPointerException"),
                "应至少告知异常类型，实际：" + result);
    }

    @Test
    void invalidJsonInputIsReportedClearly() {
        Tool tool = throwingTool("T", new IllegalStateException("never reached"));
        String result = call(tool, "{not valid json");

        assertTrue(result.startsWith("Error:"));
        assertTrue(result.toLowerCase().contains("json"),
                "应指明是 JSON 解析问题，实际：" + result);
    }

    @Test
    void nullResultFromToolDoesNotBreakAdapter() {
        // 工具返回 null 时适配器不应抛 NPE（日志里对 result 做过 substring）
        Tool nullReturning = new Tool() {
            @Override
            public String name() {
                return "NullTool";
            }

            @Override
            public String description() {
                return "returns null";
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
                return null;
            }
        };

        // 不抛异常即通过；返回 null 由上层转为空 tool_result
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> call(nullReturning, "{}"));
    }

    @Test
    void toolDefinitionMirrorsTheTool() {
        Tool tool = throwingTool("MyTool", new IllegalStateException("x"));
        var adapter = new ToolCallbackAdapter(tool, ToolContext.defaultContext());

        assertTrue(adapter.getToolDefinition().name().equals("MyTool"));
        assertTrue(adapter.getTool() == tool);
    }
}
