package com.inspirationi.loop.api;

import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.mcp.McpManager;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.McpToolBridge;
import com.inspirationi.loop.tool.impl.TaskCreateTool;
import com.inspirationi.loop.tool.impl.TaskListTool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级上下文对全局共享对象的可见性。
 * <p>
 * 曾经的缺陷：{@code createSession()} 为每个会话新建一个<b>空</b> ToolContext，
 * 而 TaskManager / McpManager / PermissionSettings 注册在全局 ToolContext 上。
 * 结果是 Task*（6 个）、*McpResource*（2 个）、Enter/ExitPlanMode（2 个）
 * 这 10 个内置工具在 HmsSessionManager 这条主路径上全部返回「未初始化」错误。
 */
class SessionSharedStateTest {

    private static ChatModel stubChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("stub"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };
    }

    /** 构造一个与 ToolConfiguration 等价的全局上下文。 */
    private static ToolContext globalContextWithSharedState(TaskManager taskManager,
                                                           McpManager mcpManager) {
        ToolContext global = ToolContext.defaultContext();
        global.set("TASK_MANAGER", taskManager);
        global.set("PERMISSION_SETTINGS", new PermissionSettings());
        global.set(McpToolBridge.MCP_MANAGER_KEY, mcpManager);
        return global;
    }

    private static DefaultHmsSessionManager newManager(ToolContext globalContext) {
        return DefaultHmsSessionManager.builder(
                        stubChatModel(), new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .globalToolContext(globalContext)
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(100)
                .build();
    }

    @Test
    void taskToolsWorkInsideASession() {
        TaskManager taskManager = new TaskManager();
        ToolContext global = globalContextWithSharedState(taskManager, new McpManager());

        try (DefaultHmsSessionManager manager = newManager(global)) {
            String sessionId = manager.createSession("s");
            ToolContext sessionCtx = manager.getSessionInternal(sessionId)
                    .getAgentLoop().getToolContext();

            String created = new TaskCreateTool()
                    .execute(Map.of("description", "build the thing"), sessionCtx);

            assertFalse(created.contains("TaskManager not initialized"),
                    "会话内的 TaskCreate 必须能拿到全局 TaskManager，实际输出: " + created);
            assertTrue(created.contains("task_id"), "应返回 task_id，实际输出: " + created);

            // 任务确实落到了那个全局 TaskManager 上
            assertSame(taskManager, sessionCtx.get("TASK_MANAGER"));
            assertTrue(taskManager.listTasks().stream()
                            .anyMatch(t -> "build the thing".equals(t.description())),
                    "任务应记录在全局 TaskManager 中");

            String listed = new TaskListTool().execute(Map.of(), sessionCtx);
            assertTrue(listed.contains("build the thing"),
                    "TaskList 应能读回同一个 TaskManager 的数据，实际输出: " + listed);
        }
    }

    @Test
    void mcpManagerIsVisibleInsideASession() {
        McpManager mcpManager = new McpManager();
        ToolContext global = globalContextWithSharedState(new TaskManager(), mcpManager);

        try (DefaultHmsSessionManager manager = newManager(global)) {
            String sessionId = manager.createSession("s");
            ToolContext sessionCtx = manager.getSessionInternal(sessionId)
                    .getAgentLoop().getToolContext();

            // MCP_MANAGER 曾经在整个 main 源码里只有读取、没有任何写入点
            assertSame(mcpManager, sessionCtx.get(McpToolBridge.MCP_MANAGER_KEY),
                    "会话内必须能读到 McpManager，否则 MCP 工具全部不可用");
        }
    }

    @Test
    void permissionSettingsIsVisibleInsideASession() {
        ToolContext global = globalContextWithSharedState(new TaskManager(), new McpManager());

        try (DefaultHmsSessionManager manager = newManager(global)) {
            String sessionId = manager.createSession("s");
            ToolContext sessionCtx = manager.getSessionInternal(sessionId)
                    .getAgentLoop().getToolContext();

            // Enter/ExitPlanMode 依赖此键
            assertNotNull(sessionCtx.get("PERMISSION_SETTINGS"),
                    "会话内必须能读到 PermissionSettings，否则 Enter/ExitPlanMode 不可用");
        }
    }

    @Test
    void sessionsDoNotShareTheirOwnToolRegistry() {
        ToolContext global = globalContextWithSharedState(new TaskManager(), new McpManager());

        try (DefaultHmsSessionManager manager = newManager(global)) {
            ToolContext first = manager.getSessionInternal(manager.createSession("a"))
                    .getAgentLoop().getToolContext();
            ToolContext second = manager.getSessionInternal(manager.createSession("b"))
                    .getAgentLoop().getToolContext();

            // 继承全局共享状态的同时，两级工具隔离必须保持
            assertNotNull(first.get("TOOL_REGISTRY"));
            assertNotNull(second.get("TOOL_REGISTRY"));
            org.junit.jupiter.api.Assertions.assertNotSame(
                    first.get("TOOL_REGISTRY"), second.get("TOOL_REGISTRY"),
                    "每个会话应持有独立的工具注册副本");
        }
    }

    @Test
    void nullGlobalContextStillCreatesUsableSessions() {
        // 不设置 globalToolContext 时不应 NPE —— 会话上下文退化为无父级的独立上下文
        try (DefaultHmsSessionManager manager = DefaultHmsSessionManager.builder(
                        stubChatModel(), new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(100)
                .build()) {
            String sessionId = manager.createSession("s");
            assertTrue(manager.sessionExists(sessionId));
            assertNotNull(manager.getSessionInternal(sessionId)
                    .getAgentLoop().getToolContext().get("TOOL_REGISTRY"));
        }
    }
}
