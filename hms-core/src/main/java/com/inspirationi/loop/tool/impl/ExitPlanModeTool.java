package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.Tool;

import java.util.Map;

/**
 * 退出计划模式工具 —— 从 ToolContext 内存中获取计划内容，恢复之前的权限模式。
 * <p>
 * SDK 场景：不再读取磁盘上的 PLAN.md 文件。
 * 从 ToolContext 内存中获取计划内容，恢复之前的权限模式。
 */
public class ExitPlanModeTool implements Tool {

    /**
     * 返回工具名称（"ExitPlanMode"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "ExitPlanMode";
    }

    /**
     * 返回工具描述，说明退出计划模式后恢复正常权限并将计划呈现给用户审阅。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
                完成实现计划后退出计划模式。

                使用场景：
                - 你已经写完计划
                - 计划包含：背景、方案、文件路径和验证步骤
                - 你准备好让用户审阅并批准计划

                效果：
                - 恢复正常权限模式（所有工具重新可用）
                - 将计划呈现给用户审阅
                - 用户随后可以让你实施计划

                在计划完成之前**不要**调用本工具。
                """);
    }

    /**
     * 返回输入 JSON Schema，定义必填的 summary 参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
                {
                  "type": "object",
                  "properties": {
                    "summary": {
                      "type": "string",
                      "description": "计划的简要摘要（1-2 句话）"
                    }
                  },
                  "required": ["summary"]
                }
                """);
    }

    /**
     * 退出计划模式：校验是否处于计划模式，从内存读取计划内容，
     * 恢复进入前的权限模式并清除计划状态，返回计划摘要信息。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // Check if in plan mode
        Boolean active = context.getOrDefault(EnterPlanModeTool.PLAN_MODE_KEY, false);
        if (!active) {
            return "⚠️ Not currently in plan mode. Nothing to exit.";
        }

        String planFilePath = context.get(EnterPlanModeTool.PLAN_FILE_PATH_KEY);
        String summary = input != null ? (String) input.get("summary") : null;

        // Get plan content from memory (not disk file)
        String planContent = context.get(EnterPlanModeTool.PLAN_CONTENT_KEY);

        // Restore previous mode
        PermissionSettings permSettings = context.get("PERMISSION_SETTINGS");
        if (permSettings != null) {
            PermissionMode previousMode = context.getOrDefault(
                    EnterPlanModeTool.PRE_PLAN_MODE_KEY, PermissionMode.DEFAULT);
            permSettings.setCurrentMode(previousMode);
        }

        // Clear plan mode state
        context.set(EnterPlanModeTool.PLAN_MODE_KEY, false);

        StringBuilder result = new StringBuilder();
        result.append("✅ Exited plan mode. Normal permissions restored.\n\n");

        if (planContent != null && !planContent.isBlank()) {
            int lines = (int) planContent.lines().count();
            int chars = planContent.length();
            result.append("📋 Plan location: ").append(planFilePath).append("\n");
            result.append("📊 Plan size: ").append(lines).append(" lines, ")
                    .append(chars).append(" characters\n");
        } else {
            result.append("⚠️ Warning: Plan content is empty.\n");
            result.append("   Location: ").append(planFilePath).append("\n");
        }

        if (summary != null && !summary.isBlank()) {
            result.append("\n📝 Summary: ").append(summary).append("\n");
        }

        result.append("\nThe user can now review the plan and ask you to implement it.");

        return result.toString();
    }

    /**
     * 该工具仅恢复权限状态、不修改任何文件，标记为只读。
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 生成用于界面展示的执行摘要，固定返回"Exiting plan mode..."。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        return "Exiting plan mode...";
    }
}