package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.AbstractReadOnlyTool;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Skill 工具 —— 执行预定义的 Skill。
 * <p>
 * Skill 不再从文件系统加载。通过 ToolContext 中两级管理的 Skill 描述列表
 * （key = {@value #SKILLS_KEY}）进行匹配和执行。
 * <p>
 * Skills 由 {@link com.inspirationi.loop.api.PromptManager} 或
 * {@link com.inspirationi.loop.api.ToolManager} 在会话创建时注入 ToolContext。
 */
public class SkillTool extends AbstractReadOnlyTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);

    /** ToolContext key for skills list (List&lt;Map&lt;String, String&gt;&gt;) */
    public static final String SKILLS_KEY = "SKILLS";

    /**
     * 返回工具名称（"Skill"），供 LLM 调用时识别。
     */
    @Override
    public String name() { return "Skill"; }

    /**
     * 返回工具描述，说明按名称执行预定义 skill 的用途。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()),
                "在主对话中执行一个 skill。Skill 提供专门的领域知识。使用 Skill 按名称调用已注册的 skill。");
    }

    /**
     * 返回输入 JSON Schema，定义 skill（必填）与 args（可选）参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
                {
                  "type": "object",
                  "properties": {
                    "skill": {
                      "type": "string",
                      "description": "skill 名称（必填）"
                    },
                    "args": {
                      "type": "string",
                      "description": "传递给 skill 的可选参数"
                    }
                  },
                  "required": ["skill"]
                }
                """);
    }

    /**
     * 执行 Skill：从 ToolContext 获取已注册的 Skill 列表，按名称（忽略大小写）匹配，
     * 命中后返回其指令与参数；未命中则列出所有可用 Skill 供参考。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String skillName = (String) input.getOrDefault("skill", "");
        String args = (String) input.getOrDefault("args", "");

        if (skillName.isBlank()) {
            return "Error: skill name is required";
        }

        // 从 ToolContext 获取两级管理的 Skill 列表
        @SuppressWarnings("unchecked")
        List<Map<String, String>> skills = context.get(SKILLS_KEY);

        if (skills == null || skills.isEmpty()) {
            return "No skills registered. Use the API to register skills for this session.";
        }

        // 按名称查找 Skill
        for (Map<String, String> skill : skills) {
            if (skillName.equalsIgnoreCase(skill.get("name"))) {
                String instruction = skill.getOrDefault("instruction", "");
                String description = skill.getOrDefault("description", "");
                log.info("Skill invoked: {} (desc: {})", skillName, description);
                return "## Skill: " + skillName + "\n\n"
                        + description + "\n\n"
                        + "### Instructions\n\n"
                        + instruction
                        + (args.isBlank() ? "" : "\n\n### Arguments\n" + args);
            }
        }

        // 未找到 — 列出可用 Skills
        StringBuilder sb = new StringBuilder("Skill '")
                .append(skillName).append("' not found. Available skills:\n");
        for (Map<String, String> s : skills) {
            sb.append("- ").append(s.get("name"))
                    .append(": ").append(s.getOrDefault("description", "")).append("\n");
        }
        return sb.toString();
    }
}
