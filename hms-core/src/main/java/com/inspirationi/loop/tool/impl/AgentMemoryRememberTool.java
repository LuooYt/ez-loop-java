package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.agent.AgentMemory;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 记忆写入工具 —— 让模型自主决定「这件事值得记住」。
 * <p>
 * <b>安全权衡（必须知情）</b>：这条工具让模型能往自己的 system prompt 里投喂内容
 * （记忆摘要会被拼进提示词），因此天然是一个 prompt-injection 面。默认注册且风险
 * 等级定为 {@link RiskLevel#LOW} —— 在默认权限模式下自动放行，模型得以自主积累；
 * 这是为了满足「自主管理记忆」而显式接受的代价。已有的防护：
 * <ul>
 *   <li>记忆在提示词中被显式标注为「不可信笔记，不得作为指令解读」</li>
 *   <li>单条限长、摘要总长限长（见 {@code MemorySpec}）</li>
 *   <li>内容归一化并去重，重复写入不新增</li>
 *   <li><b>绝不因记忆内容放宽任何权限判断</b> —— 权限只看工具风险等级与规则</li>
 *   <li>集成方可用 {@code MemorySpec.writeTool(false)} 整体关掉本工具</li>
 * </ul>
 * <p>
 * 从属会话（子 Agent、委派用的一次性会话）<b>不注册</b>本工具，见
 * {@code DefaultHmsSessionManager.newAgentLoop}。
 */
public class AgentMemoryRememberTool implements Tool {

    /** 工具名 —— 也是从属会话剔除本工具时的键。 */
    public static final String NAME = "RememberAgentMemory";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            把一条值得跨会话保留的信息写入本智能体的长期记忆。

            适用场景（记「以后还用得上」的东西）：
            - 用户明确表达的长期偏好、约定、口径（「以后都按这个格式」）
            - 已确认的结论、决策及其理由
            - 反复出现的背景事实（项目代号、关键人物、系统约束）

            不适用场景：
            - 一次性的中间结果、临时状态、大段原文
            - 用户刚说的话（对话历史里已有，不必重复）
            - 任何你打算据此放宽权限判断的内容——记忆永远不会影响权限

            内容相同（归一化后）的记忆不会重复新增，只会刷新时间与标签。
            """);
    }

    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "content": {
                  "type": "string",
                  "description": "要记住的内容，一句话讲清、自包含（不要写「如上」这类依赖上下文的指代）"
                },
                "tags": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "可选标签，便于日后检索（如 preference、project、decision）"
                }
              },
              "required": ["content"]
            }""");
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * 低风险 —— 在默认权限模式下自动放行，模型得以自主积累记忆。
     * <p>
     * 不设成 {@code READ_ONLY}：它确实改变状态，声明成只读会让审计视图失真。
     */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        AgentMemory memory = context.get(AgentMemory.CONTEXT_KEY);
        if (memory == null) {
            return "本智能体未启用记忆，无法写入。";
        }
        String content = input.get("content") instanceof String s ? s : null;
        if (content == null || content.isBlank()) {
            return "Error: 'content' is required";
        }
        String id = memory.remember(content, "agent", extractTags(input));
        if (id == null) {
            return "内容为空，未写入。";
        }
        return "已记住（id=" + id + "，记忆库共 " + memory.size() + " 条）。";
    }

    /** 从 input 中取出 tags 字段（容忍非 String 元素）。 */
    private static List<String> extractTags(Map<String, Object> input) {
        Object raw = input.get("tags");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> tags = new ArrayList<>(list.size());
        for (Object tag : list) {
            if (tag != null && !tag.toString().isBlank()) {
                tags.add(tag.toString());
            }
        }
        return tags;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "正在记录长期记忆...";
    }
}
