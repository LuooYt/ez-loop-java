package com.inspirationi.loop.config;

import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.*;
import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
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
     * 同时向 ToolContext 注入 TASK_MANAGER、PERMISSION_SETTINGS、
     * Agent 工厂与 TOOL_REGISTRY 等全局共享对象。
     */
    @Bean
    public ToolRegistry toolRegistry(TaskManager taskManager,
                                     ToolContext toolContext,
                                     PermissionSettings permissionSettings,
                                     PermissionRuleEngine permissionRuleEngine) {
        toolContext.set("TASK_MANAGER", taskManager);
        toolContext.set("PERMISSION_SETTINGS", permissionSettings);
        // 通过 permissionRuleEngine.addCommandExtractor(...) 注入相应的映射即可。
        // 注册 Agent 工厂 —— 子 Agent 通过相同的 ChatModel + ToolRegistry 创建独立 AgentLoop
        toolContext.set(AgentTool.AGENT_FACTORY_KEY,
                (java.util.function.Function<String, String>) prompt -> {
                    log.warn("Agent factory not yet wired with ChatModel — sub-agent unavailable at global level");
                    return "Error: Sub-agent not available until session is created with ChatModel.";
                });

        // 创建工具注册表并批量注册全部核心工具
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(
                // === 互联网检索 ===
                new WebFetchTool(),
                new WebSearchTool(),

                // === 编排工具 ===
                new AgentTool(),
                new SendMessageTool(),
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

        toolContext.set("TOOL_REGISTRY", registry);
        log.info("Global ToolRegistry created with {} tools", registry.size());
        return registry;
    }
}
