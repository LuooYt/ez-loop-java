package com.inspirationi.loop.config;

import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.SendMessageTool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SendMessage 不得出现在默认工具集里，且被调用时必须明确否认已投递。
 * <p>
 * 旧实现把消息写进 {@code ToolContext} 的一个键，全仓库没有消费方，却向模型返回
 * 「Message queued」。模型据此认为后续指令已经交给 worker，继续基于这个虚假事实
 * 推理 —— 比「工具不存在」糟糕得多。
 */
class SendMessageToolRegistrationTest {

    @Test
    void sendMessageIsNotInDefaultToolSet() {
        ToolRegistry registry = new ToolRegistry();
        ToolConfiguration.registerCoreTools(registry);

        assertFalse(registry.getToolNames().contains(SendMessageTool.TOOL_NAME),
                "消息投递尚未实现，不得把会撒谎的工具暴露给模型；实际工具集：" + registry.getToolNames());
    }

    @Test
    void callingUnimplementedToolDeniesDelivery() {
        @SuppressWarnings("deprecation")
        SendMessageTool tool = new SendMessageTool();

        String result = tool.execute(
                Map.of("to", "task-1", "message", "继续"), ToolContext.defaultContext());

        assertTrue(result.startsWith("Error:"), "必须明确报错而不是假装成功：" + result);
        assertTrue(result.toLowerCase().contains("not implemented"),
                "错误文本应说明功能未实现：" + result);
        assertTrue(result.contains("TaskStop"),
                "应给出可执行的替代做法，否则模型会反复重试：" + result);
    }

    @Test
    void missingArgumentsAreReportedAsArgumentErrors() {
        @SuppressWarnings("deprecation")
        SendMessageTool tool = new SendMessageTool();

        assertTrue(tool.execute(Map.of("message", "hi"), ToolContext.defaultContext())
                        .contains("'to'"),
                "参数错误应与「功能缺失」区分开，便于模型自我纠正");
        assertTrue(tool.execute(Map.of("to", "task-1"), ToolContext.defaultContext())
                        .contains("'message'"));
    }

    @Test
    void nonStringArgumentsDoNotThrow() {
        @SuppressWarnings("deprecation")
        SendMessageTool tool = new SendMessageTool();

        // 模型偶尔给错类型：不能抛 ClassCastException 中断整个工具轮次
        assertTrue(tool.execute(Map.of("to", 123, "message", "hi"), ToolContext.defaultContext())
                .startsWith("Error:"));
    }
}
