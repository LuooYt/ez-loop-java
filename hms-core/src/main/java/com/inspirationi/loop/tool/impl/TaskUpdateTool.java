package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.core.TaskManager.TaskInfo;
import com.inspirationi.loop.core.TaskManager.TaskStatus;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;
import java.util.Optional;

/**
 * TaskUpdate 工具 —— 更新指定任务的状态和结果。
 * <p>
 * 用于推动手动管理任务的状态流转，
 * 例如从 PENDING → RUNNING → COMPLETED。
 * </p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li><b>task_id</b>（必填）—— 要更新的任务 ID</li>
 *   <li><b>status</b>（必填）—— 新状态：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED</li>
 *   <li><b>result</b>（可选）—— 任务执行结果或附加信息</li>
 * </ul>
 *
 * <h3>返回</h3>
 * <p>JSON 格式的更新确认，包含更新后的任务信息。</p>
 *
 * <h3>状态约束</h3>
 * <p>已处于终态（COMPLETED / FAILED / CANCELLED）的任务不允许再次更新。</p>
 */
public class TaskUpdateTool implements Tool {

    /** ToolContext 中 TaskManager 的存储键 */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    /**
     * 返回工具名称（"TaskUpdate"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "TaskUpdate";
    }

    /**
     * 返回工具描述，说明更新任务状态与结果的用途及状态流转规则。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            更新任务的状态和可选结果。

            状态流转：
             - PENDING → RUNNING：开始处理任务时设置。
             - RUNNING → COMPLETED：任务已完整完成并验证时设置。
             - RUNNING → FAILED：任务无法完成时设置（请在 result 中说明原因）。
             - RUNNING → CANCELLED：任务不再需要时设置。
             - 处于终态（COMPLETED/FAILED/CANCELLED）的任务不能继续更新。

            完成或失败任务时，请始终在 'result' 中提供有意义的描述，\
            以便用户了解完成了什么或出了什么问题。
            """);
    }

    /**
     * 返回输入 JSON Schema，定义 task_id、status（必填）与 result（可选）参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
                {
                  "type": "object",
                  "properties": {
                    "task_id": {
                      "type": "string",
                      "description": "要更新的任务 ID"
                    },
                    "status": {
                      "type": "string",
                      "description": "新状态：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED",
                      "enum": ["PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
                    },
                    "result": {
                      "type": "string",
                      "description": "任务执行结果或附加信息（可选）"
                    }
                  },
                  "required": ["task_id", "status"]
                }""");
    }

    /**
     * 该工具会修改任务状态，标记为非只读。
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * 更新任务状态：校验 task_id/status 参数、记录旧状态并委托 TaskManager.updateTask。
     * 已处于终态的任务拒绝更新，成功后返回包含前后状态的确认 JSON。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // 获取 TaskManager 实例
        TaskManager manager = context.get(TASK_MANAGER_KEY);
        if (manager == null) {
            return errorJson("TaskManager not initialized, check context configuration");
        }

        // 解析必填参数: task_id
        String taskId = (String) input.get("task_id");
        if (taskId == null || taskId.isBlank()) {
            return errorJson("Parameter 'task_id' is required and cannot be empty");
        }

        // 解析必填参数: status
        String statusStr = (String) input.get("status");
        if (statusStr == null || statusStr.isBlank()) {
            return errorJson("Parameter 'status' is required and cannot be empty");
        }

        TaskStatus newStatus;
        try {
            newStatus = TaskStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return errorJson("Invalid status value: '" + statusStr
                    + "'. Valid values: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED");
        }

        // 解析可选参数: result
        String result = (String) input.get("result");

        // 在更新前先获取旧状态（用于返回信息）
        Optional<TaskInfo> beforeOpt = manager.getTask(taskId);
        if (beforeOpt.isEmpty()) {
            return errorJson("Task with ID '" + taskId + "' not found");
        }

        TaskInfo before = beforeOpt.get();
        String oldStatus = before.status().name();

        // 执行更新
        boolean success = manager.updateTask(taskId, newStatus, result);
        if (!success) {
            return errorJson("Update failed: task '" + taskId + "' current status is "
                    + oldStatus + ", may be in terminal state and cannot be updated");
        }

        // 获取更新后的任务信息
        Optional<TaskInfo> afterOpt = manager.getTask(taskId);
        if (afterOpt.isEmpty()) {
            // 理论上不会出现，防御性编程
            return errorJson("Failed to get task info after update");
        }

        TaskInfo after = afterOpt.get();

        // 返回更新确认 JSON
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"success\": true,\n");
        sb.append("  \"task_id\": \"").append(escapeJson(after.id())).append("\",\n");
        sb.append("  \"previous_status\": \"").append(oldStatus).append("\",\n");
        sb.append("  \"current_status\": \"").append(after.status().name()).append("\",\n");

        if (after.result() != null) {
            sb.append("  \"result\": \"").append(escapeJson(after.result())).append("\",\n");
        } else {
            sb.append("  \"result\": null,\n");
        }

        sb.append("  \"updated_at\": \"").append(after.updatedAt()).append("\",\n");
        sb.append("  \"message\": \"Task status updated from ").append(oldStatus)
                .append(" to ").append(after.status().name()).append("\"\n");
        sb.append("}");

        return sb.toString();
    }

    /**
     * 生成用于界面展示的执行摘要，标明任务 ID 与目标状态。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        String taskId = (String) input.getOrDefault("task_id", "unknown");
        String status = (String) input.getOrDefault("status", "?");
        return "✏️ Updating task " + taskId + " → " + status;
    }

    /* ------------------------------------------------------------------ */
    /*  辅助方法                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 转义 JSON 特殊字符。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 构建错误 JSON 响应。
     */
    private String errorJson(String message) {
        return """
                {
                  "error": true,
                  "message": "%s"
                }""".formatted(escapeJson(message));
    }
}
