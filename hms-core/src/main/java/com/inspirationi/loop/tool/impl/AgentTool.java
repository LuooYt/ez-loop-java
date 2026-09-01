package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 子 Agent 工具 —— 创建独立的子 Agent 处理复杂子任务。
 * <p>
 * 创建一个独立的子 Agent 来处理复杂的子任务。子 Agent 拥有独立的消息历史，
 * 但共享工具集和上下文环境。适用于：
 * <ul>
 *   <li>需要独立上下文的子任务（如分析另一个文件）</li>
 *   <li>并行处理多个任务</li>
 *   <li>隔离风险操作</li>
 * </ul>
 * <p>
 * 注意：子 Agent 使用主 Agent 的 ChatModel 和工具集，
 * 通过 ToolContext 中的 "agentLoop.factory" 获取 AgentLoop 工厂方法。
 */
public class AgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    /** ToolContext 中存储 AgentLoop 工厂的键名 */
    public static final String AGENT_FACTORY_KEY = "__agent_factory__";

    /** 子 Agent 完整系统提示词（默认中文，翻译服务会按系统语言翻译）。 */
    public static final String DEFAULT_SUBAGENT_SYSTEM_PROMPT = """
            你是一个 AI 子 Agent。请根据用户分配的任务，使用可用的工具来完成它。要完整地完成任务——\
            不要过度设计，但也不要草草了事。任务完成后，返回一份简洁的报告，说明做了什么以及关键发现——\
            调用方会将其转达给用户，因此只需核心要点。

            注意事项：
            - 在最终回复中包含与任务相关的标识信息（ID、路径、引用）。\
            仅在文本内容本身至关重要时才包含详细内容。
            - 沟通中避免使用表情符号。
            - 调用工具之前不要使用冒号。

            """;

    /**
     * 返回工具名称（"Agent"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "Agent";
    }

    /**
     * 返回工具描述，说明子 Agent 的适用与不适用场景，供 LLM 决定是否使用。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            启动一个子 Agent 来自主处理复杂的多步骤任务。子 Agent 拥有独立的对话上下文，但共享工具集与运行环境。

            适用场景：
            - 需要专注处理或多个步骤的复杂任务
            - 并行开展相互独立的调研（可同时启动多个 Agent）
            - 保护主上下文免受大量工具输出（搜索结果、日志等）的干扰
            - 需要隔离上下文的任务

            不适用场景：
            - 简单、单步的操作（请直接调用相应工具）
            - 需要立即在当前上下文中获得结果的任务
            - 仅是为了转包一次单一工具调用

            重要：
            - 提供完整、自包含的提示词——子 Agent 没有你的对话历史
            - 不要重复子 Agent 已经在做的工作
            - 子 Agent 会返回简洁的结果；它无法追问澄清问题
            """);
    }

    /**
     * 返回输入 JSON Schema，定义 prompt（必填）与 context（可选）两个参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "prompt": {
                  "type": "string",
                  "description": "子 Agent 的任务描述/提示词"
                },
                "context": {
                  "type": "string",
                  "description": "附加上下文或指令（可选）"
                }
              },
              "required": ["prompt"]
            }""");
    }

    /**
     * 执行子 Agent：校验 prompt 参数，从 ToolContext 获取 AgentLoop 工厂，
     * 构建完整提示词并调用工厂创建子 Agent 执行任务，返回其结果。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String prompt = (String) input.get("prompt");
        String additionalContext = (String) input.getOrDefault("context", "");

        if (prompt == null || prompt.isBlank()) {
            return "Error: 'prompt' is required";
        }

        // 从 ToolContext 获取 AgentLoop 工厂方法
        @SuppressWarnings("unchecked")
        java.util.function.Function<String, String> agentFactory =
                context.getOrDefault(AGENT_FACTORY_KEY, null);

        if (agentFactory == null) {
            log.warn("AgentTool: Agent factory not configured, cannot create sub-agent");
            return "Error: Sub-agent capability is not configured. "
                   + "The Agent tool requires an agent factory to be registered in the ToolContext.";
        }

        // 构建完整的子 Agent 提示
        String fullPrompt = buildSubAgentPrompt(prompt, additionalContext);

        log.info("Starting sub-agent, task: {}", truncate(prompt, 80));

        try {
            String result = agentFactory.apply(fullPrompt);
            log.info("Sub-agent completed, result length: {} chars", result.length());
            return result;
        } catch (Exception e) {
            log.debug("Sub-agent execution failed", e);
            return "Error: Sub-agent failed: " + e.getMessage();
        }
    }

    /**
     * 构建子 Agent 的完整提示词。
     * <p>
     * 子 Agent 是一个通用的 AI Agent，不绑定特定领域（非 CLI 编码助手专用）。
     * 调用方可通过 ToolContext 注入自定义的 System Prompt。
     */
    private String buildSubAgentPrompt(String prompt, String additionalContext) {
        StringBuilder sb = new StringBuilder();
        // 使用当前语言下的子 Agent 系统提示词（中文系统 → 中文；非中文系统 → 翻译后的版本）
        sb.append(PromptI18n.t(PromptI18n.KEY_SUBAGENT_PROMPT, DEFAULT_SUBAGENT_SYSTEM_PROMPT));

        sb.append("## 任务\n");
        sb.append(prompt);

        if (additionalContext != null && !additionalContext.isBlank()) {
            sb.append("\n\n## 附加上下文\n");
            sb.append(additionalContext);
        }

        return sb.toString();
    }

    /**
     * 将文本截断到指定长度，超出部分以省略号结尾（用于日志与摘要展示）。
     */
    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    /**
     * 生成用于界面展示的执行摘要，截断 prompt 后拼接"Sub-agent:"前缀。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        String prompt = (String) input.getOrDefault("prompt", "");
        if (prompt.length() > 40) {
            prompt = prompt.substring(0, 37) + "...";
        }
        return "🤖 Sub-agent: " + prompt;
    }
}
