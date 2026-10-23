package com.inspirationi.loop.config;

import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.*;
import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.mcp.McpManager;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.sandbox.SandboxManager;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 全局工具注册配置 —— SDK 核心工具集（全局级别）。
 * <p>
 * 会话创建时通过 {@link com.inspirationi.loop.api.ToolManager} 从全局工具复制。
 * 不再从文件/MCP 配置读取，全部通过编程式 API 管理。
 * <p>
 * 依赖 {@link AppConfig} 产出的 TaskManager / ToolContext / PermissionSettings，
 * 故声明在其之后装配。
 */
@AutoConfiguration(after = AppConfig.class)
public class ToolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolConfiguration.class);

    /**
     * 全局工具注册表 Bean —— 装配并注册 SDK 全局核心工具集。
     * <p>
     * 同时向 ToolContext 注入 TASK_MANAGER、MCP_MANAGER、PERMISSION_SETTINGS、
     * Agent 工厂与 TOOL_REGISTRY 等全局共享对象。会话级上下文以此为父级
     * （见 {@link com.inspirationi.loop.tool.ToolContext#childOf}），因此这里注册一次即全局可见。
     */
    @Bean
    public ToolRegistry toolRegistry(TaskManager taskManager,
                                     ToolContext toolContext,
                                     McpManager mcpManager,
                                     PermissionSettings permissionSettings,
                                     PermissionRuleEngine permissionRuleEngine,
                                     SandboxProperties sandboxProperties,
                                     SandboxManager sandboxManager) {
        toolContext.set("TASK_MANAGER", taskManager);
        toolContext.set("PERMISSION_SETTINGS", permissionSettings);
        // ListMcpResources / ReadMcpResource / McpToolBridge 都从上下文读这个键，
        // 不注入则 MCP 工具恒返回「manager not registered」。
        toolContext.set(McpToolBridge.MCP_MANAGER_KEY, mcpManager);
        // 通过 permissionRuleEngine.addCommandExtractor(...) 注入相应的映射即可。
        // 注册 Agent 工厂 —— 子 Agent 通过相同的 ChatModel + ToolRegistry 创建独立 AgentLoop
        toolContext.set(AgentTool.AGENT_FACTORY_KEY,
                (java.util.function.Function<String, String>) prompt -> {
                    log.warn("Agent factory not yet wired with ChatModel — sub-agent unavailable at global level");
                    return "Error: Sub-agent not available until session is created with ChatModel.";
                });

        // 创建工具注册表并批量注册全部核心工具
        ToolRegistry registry = new ToolRegistry();
        registerCoreTools(registry);

        // === 沙箱文件工具 === 未启用沙箱时不注册（没有可用的根目录，工具必然失败）；
        // Notification 与文件系统无关，始终注册。
        registerSandboxTools(registry, sandboxManager, sandboxProperties, permissionRuleEngine);
        registry.register(new NotificationTool(sandboxProperties.getNotification()));

        toolContext.set("TOOL_REGISTRY", registry);
        log.info("Global ToolRegistry created with {} tools", registry.size());
        return registry;
    }

    /**
     * 注册与沙箱无关的核心工具。
     * <p>
     * 抽成静态方法是为了能在单测里直接断言注册结果，不必启动整个 Spring 上下文 ——
     * 「某个工具该不该出现在默认工具集里」是个产品决定，值得被测试锁住。
     * <p>
     * <b>SendMessage 不在其中</b>：它只把消息写进 ToolContext 的一个键，全仓库没有
     * 消费方，模型却会收到「已投递」。宁可不提供，也不要提供一个会撒谎的工具。
     * 详见 {@link com.inspirationi.loop.tool.impl.SendMessageTool} 的说明。
     */
    static void registerCoreTools(ToolRegistry registry) {
        registry.registerAll(
                // === 互联网检索 ===
                new WebFetchTool(),
                new WebSearchTool(),

                // === 编排工具 ===
                new AgentTool(),
                new TaskCreateTool(),
                new TaskGetTool(),
                new TaskListTool(),
                new TaskUpdateTool(),
                new TaskStopTool(),
                new TaskOutputTool(),
                new TodoWriteTool(),

                // === MCP 工具 ===
                new ListMcpResourcesTool(),
                new ReadMcpResourceTool(),

                // === Skill 工具 ===
                new SkillTool(),

                // === 基础框架工具 ===
                new ConfigTool(),
                new SleepTool(),
                new AskUserQuestionTool(),
                new ToolSearchTool(),
                new EnterPlanModeTool(),
                new ExitPlanModeTool()
        );
    }

    /**
     * 注册依赖会话沙箱的路径类工具。
     * <p>
     * 沙箱不可用（未配置、根目录建不出来）时直接跳过：注册了也只会让模型拿到
     * 一串必然失败的调用。LSP 工具额外要求至少配置了一个语言服务器 —— 没有服务器
     * 时它连「查什么」都做不到。
     * <p>
     * Bash 的命令提取器在这里接线：权限引擎据此把 {@code command} 的首个词
     * 交给现有的危险命令检测与规则匹配，不必为 Bash 另写一套判定。
     */
    static void registerSandboxTools(ToolRegistry registry, SandboxManager sandboxManager,
                                     SandboxProperties props,
                                     PermissionRuleEngine permissionRuleEngine) {
        // 判定依据是管理器的真实就绪状态（根目录实际建得出来），而不是配置本身：
        // 配置写对了但目录建不出来时，注册出去的工具只会一律返回「沙箱未配置」。
        if (props == null || !props.isUsable()
                || sandboxManager == null || !sandboxManager.enabled()) {
            log.warn("Sandbox not usable: skipping file tools");
            return;
        }
        long maxBytes = props.getMaxFileBytes();
        registry.registerAll(
                new FileReadTool(maxBytes),
                new FileWriteTool(maxBytes),
                new FileEditTool(maxBytes),
                new GlobTool(),
                new GrepTool(maxBytes),
                new ListFilesTool(),
                new BashTool(props.getBash()),
                new NotebookEditTool(maxBytes),
                new EnterWorktreeTool(),
                new ExitWorktreeTool()
        );
        if (props.getLsp() != null && !props.getLsp().getServers().isEmpty()) {
            registry.register(new LSPTool());
        }
        permissionRuleEngine.addCommandExtractor("Bash", "command");
    }
}
