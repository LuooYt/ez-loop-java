package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.Tool;

import java.util.Map;

/**
 * 进入计划模式工具 —— 将权限切换到 STRICT 只读模式，AI 只能分析不能修改。
 * <p>
 * SDK 场景：不再创建磁盘上的 PLAN.md 文件。
 * 计划模式完全通过 ToolContext + PermissionSettings 管理：
 * 将权限切换到只读模式，AI只能分析代码不能修改。
 */
public class EnterPlanModeTool implements Tool {

    /** ToolContext 中计划模式激活标志的存储键 */
    public static final String PLAN_MODE_KEY = "PLAN_MODE_ACTIVE";

    /** ToolContext 中计划文件（内存路径，非磁盘）的存储键 */
    public static final String PLAN_FILE_PATH_KEY = "PLAN_FILE_PATH";

    /** ToolContext 中计划内容字符串的存储键 */
    public static final String PLAN_CONTENT_KEY = "PLAN_CONTENT";

    /** ToolContext 中进入计划模式前权限模式的存储键 */
    public static final String PRE_PLAN_MODE_KEY = "PRE_PLAN_MODE";

    /**
     * 返回工具名称（"EnterPlanMode"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "EnterPlanMode";
    }

    /**
     * 返回工具描述，说明进入计划模式后只能使用只读工具、不能修改代码。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
                进入计划模式来分析代码库并设计实现方案，而**不做任何修改**。

                使用场景：
                - 用户在实现前要求你"规划"或"考虑"某个改动
                - 用户希望在动手前先了解方案
                - 需要仔细设计的多文件复杂改动

                计划模式下：
                - 你**只能**使用只读工具（Read、Grep、Glob、ListFiles、WebFetch、WebSearch）
                - 计划内容存储在内存中（可通过 ExitPlanMode 获取）
                - 所有其他文件修改和 shell 命令都会被**阻止**
                - 使用 AskUserQuestion 澄清需求
                - 计划完成后调用 ExitPlanMode
                """);
    }

    /**
     * 返回输入 JSON Schema，定义可选的 reason 参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
                {
                  "type": "object",
                  "properties": {
                    "reason": {
                      "type": "string",
                      "description": "进入计划模式的简要原因"
                    }
                  },
                  "required": []
                }
                """);
    }

    /**
     * 进入计划模式：将权限切换为 STRICT 只读、生成虚拟计划文件路径，
     * 在内存中保存计划状态与进入前的权限模式（供 ExitPlanMode 恢复）。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // Check if already in plan mode
        Boolean active = context.getOrDefault(PLAN_MODE_KEY, false);
        if (active) {
            String existingPlan = context.get(PLAN_FILE_PATH_KEY);
            return "Already in plan mode. Plan file: " + existingPlan;
        }

        // Use a virtual plan path (not disk)
        String planTag = "plan-" + System.currentTimeMillis();
        context.set(PLAN_FILE_PATH_KEY, "memory://plans/" + planTag + "/PLAN.md");

        // Save pre-plan mode for restoration
        PermissionSettings permSettings = context.get("PERMISSION_SETTINGS");
        if (permSettings != null) {
            PermissionMode previousMode = permSettings.getCurrentMode();
            context.set(PRE_PLAN_MODE_KEY, previousMode);
            // Switch to PLAN mode
            permSettings.setCurrentMode(PermissionMode.STRICT);
        }

        // Store plan state (in-memory only)
        context.set(PLAN_MODE_KEY, true);
        context.set(PLAN_CONTENT_KEY, "");

        String reason = input != null ? (String) input.get("reason") : null;

        StringBuilder result = new StringBuilder();
        result.append("✅ Entered plan mode.\n\n");
        result.append("📋 Plan stored in-memory (no disk file)\n");
        result.append("📝 Create your plan — write the plan content and call ExitPlanMode when ready.\n");
        result.append("\n");
        result.append("Restrictions active:\n");
        result.append("  • Only read-only tools allowed (Read, Grep, Glob, etc.)\n");
        result.append("  • Shell commands are blocked\n");
        result.append("  • Call ExitPlanMode when your plan is ready\n");

        if (reason != null && !reason.isBlank()) {
            result.append("\nReason: ").append(reason);
        }

        return result.toString();
    }

    /**
     * 该工具仅切换权限状态、不修改任何文件，标记为只读。
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 生成用于界面展示的执行摘要，固定返回"Entering plan mode..."。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        return "Entering plan mode...";
    }
}