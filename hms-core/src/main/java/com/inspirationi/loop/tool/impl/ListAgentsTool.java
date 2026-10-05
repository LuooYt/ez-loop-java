package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.agent.AgentRegistry;
import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;

/**
 * 智能体发现工具 —— 让模型知道「有哪些 Agent 可以把活交出去」。
 * <p>
 * 这是 AI 自主能力的入口：没有它，模型只能被固定地安排给某个 Agent；
 * 有了它，模型可以在接到任务后自己判断该由谁来处理，再用 {@code Agent} 工具委派。
 * <p>
 * <b>只读、且不暴露任何创建入口</b> —— 模型能发现和选择已有 Agent，但不能改变
 * 这个集合。Agent 的增删只走集成方的 API，见 {@link AgentRegistry}。
 */
public class ListAgentsTool implements Tool {

    /** ToolContext 中存放 {@link AgentRegistry} 的键名 —— 由自动配置写入全局上下文。 */
    public static final String AGENT_REGISTRY_KEY = "AGENT_REGISTRY";

    /** ToolContext 中存放「当前会话所属 Agent id」的键名。 */
    public static final String AGENT_ID_KEY = "AGENT_ID";

    @Override
    public String name() {
        return "ListAgents";
    }

    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            列出当前可用的智能体，以及各自擅长的领域。

            适用场景：
            - 决定把子任务交给谁之前，先看看有哪些智能体可用
            - 确认某个智能体是否存在、是否允许被委派

            返回每个智能体的 id、名称、用途说明，以及能否被委派。用 Agent 工具委派时，
            把 id 填进 agent 参数。
            """);
    }

    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {},
              "required": []
            }""");
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        AgentRegistry registry = context.get(AGENT_REGISTRY_KEY);
        if (registry == null) {
            return "Error: 未配置智能体注册表（多智能体能力不可用）。";
        }
        String self = context.get(AGENT_ID_KEY);

        StringBuilder sb = new StringBuilder("可用智能体：\n");
        for (AgentRuntime runtime : registry.list()) {
            var definition = runtime.definition();
            sb.append("- ").append(definition.id());
            if (!definition.id().equals(definition.name())) {
                sb.append("（").append(definition.name()).append("）");
            }
            if (definition.id().equals(self)) {
                sb.append(" [当前]");
            }
            if (!definition.delegatable()) {
                sb.append(" [不可委派]");
            }
            sb.append(definition.isProactive() ? " [主动]" : " [被动]");
            String description = definition.description();
            if (description != null && !description.isBlank()) {
                sb.append("：").append(description);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "正在查询可用智能体...";
    }
}
