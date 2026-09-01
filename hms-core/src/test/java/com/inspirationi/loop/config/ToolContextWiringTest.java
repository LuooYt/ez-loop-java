package com.inspirationi.loop.config;

import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.mcp.McpManager;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.AgentTool;
import com.inspirationi.loop.tool.impl.McpToolBridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolConfiguration} 必须把全部全局共享对象注册到 ToolContext。
 * <p>
 * 每个键都对应一批只从上下文取依赖的内置工具；漏注册一个，对应工具就在运行时
 * 静默返回「未初始化」错误 —— 编译期无法发现。{@code MCP_MANAGER} 就曾经如此：
 * 整个 main 源码里只有三处读取、没有任何写入点，MCP 三个工具因此从未可用。
 */
class ToolContextWiringTest {

    /** 按 Spring 的方式调用 Bean 方法，返回被填充的上下文。 */
    private static ToolContext wire() {
        ToolContext toolContext = ToolContext.defaultContext();
        PermissionSettings settings = new PermissionSettings();
        ToolRegistry registry = new ToolConfiguration().toolRegistry(
                new TaskManager(), toolContext, new McpManager(),
                settings, new PermissionRuleEngine(settings));
        assertNotNull(registry, "应返回工具注册表");
        return toolContext;
    }

    @Test
    void mcpManagerIsRegistered() {
        // ListMcpResources / ReadMcpResource / McpToolBridge 都只从上下文取它
        assertNotNull(wire().get(McpToolBridge.MCP_MANAGER_KEY),
                "未注册 MCP_MANAGER 时 MCP 工具全部返回 'manager not registered'");
    }

    @Test
    void taskManagerIsRegistered() {
        // TaskCreate / Get / List / Update / Stop / Output 共 6 个工具依赖它
        assertNotNull(wire().get("TASK_MANAGER"),
                "未注册 TASK_MANAGER 时 6 个 Task 工具全部不可用");
    }

    @Test
    void permissionSettingsIsRegistered() {
        // Enter/ExitPlanMode 依赖它
        assertNotNull(wire().get("PERMISSION_SETTINGS"),
                "未注册 PERMISSION_SETTINGS 时 Enter/ExitPlanMode 不可用");
    }

    @Test
    void toolRegistryAndAgentFactoryAreRegistered() {
        ToolContext ctx = wire();
        assertNotNull(ctx.get("TOOL_REGISTRY"));
        assertNotNull(ctx.get(AgentTool.AGENT_FACTORY_KEY));
    }

    @Test
    void registeredInstancesAreTheInjectedBeans() {
        ToolContext toolContext = ToolContext.defaultContext();
        TaskManager taskManager = new TaskManager();
        McpManager mcpManager = new McpManager();
        PermissionSettings settings = new PermissionSettings();

        new ToolConfiguration().toolRegistry(taskManager, toolContext, mcpManager,
                settings, new PermissionRuleEngine(settings));

        // 必须是注入的那些 Bean 本身，而非新建实例 —— 否则运维接口读到的
        // 与工具实际写入的是两份互不相干的状态
        assertSame(taskManager, toolContext.get("TASK_MANAGER"));
        assertSame(mcpManager, toolContext.get(McpToolBridge.MCP_MANAGER_KEY));
        assertSame(settings, toolContext.get("PERMISSION_SETTINGS"));
    }

    @Test
    void everyBuiltInToolIsRegistered() {
        ToolContext toolContext = ToolContext.defaultContext();
        PermissionSettings settings = new PermissionSettings();
        ToolRegistry registry = new ToolConfiguration().toolRegistry(
                new TaskManager(), toolContext, new McpManager(),
                settings, new PermissionRuleEngine(settings));

        // README 承诺 20 个内置工具
        assertTrue(registry.size() >= 20,
                "内置工具数量不应低于 20，实际 " + registry.size());
    }
}
