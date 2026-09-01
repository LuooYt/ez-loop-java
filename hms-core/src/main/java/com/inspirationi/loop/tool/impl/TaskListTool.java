package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.core.TaskManager.TaskInfo;
import com.inspirationi.loop.core.TaskManager.TaskStatus;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.List;
import java.util.Map;

/**
 * TaskList 工具 —— 列出所有任务，支持按状态过滤。
 * <p>
 * 返回任务列表的 JSON 数组，
 * 每个元素包含任务 ID、描述和当前状态。
 * </p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li><b>status</b>（可选）—— 状态过滤器：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED</li>
 * </ul>
 *
 * <h3>返回</h3>
 * <p>JSON 格式的任务列表。</p>
 */
public class TaskListTool implements Tool {

    /** ToolContext 中 TaskManager 的存储键 */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    /**
     * 返回工具名称（"TaskList"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "TaskList";
    }

    /**
     * 返回工具描述，说明列出所有任务、支持按状态过滤的用途。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()),
                "列出所有任务，可按状态过滤");
    }

    /**
     * 返回输入 JSON Schema，定义可选的 status 过滤参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
                {
                  "type": "object",
                  "properties": {
                    "status": {
                      "type": "string",
                      "description": "按状态过滤：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED",
                      "enum": ["PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
                    }
                  },
                  "required": []
                }""");
    }

    /**
     * 该工具仅读取任务列表、不修改任何状态，标记为只读。
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 列出所有任务：可选按状态过滤，非法状态值返回错误信息，
     * 最终将任务列表序列化为 JSON 响应。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // 获取 TaskManager 实例
        TaskManager manager = context.get(TASK_MANAGER_KEY);
        if (manager == null) {
            return errorJson("TaskManager not initialized, check context configuration");
        }

        // 解析可选参数: status
        TaskStatus statusFilter = null;
        String statusStr = (String) input.get("status");
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                statusFilter = TaskStatus.valueOf(statusStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return errorJson("Invalid status value: '" + statusStr
                        + "'. Valid values: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED");
            }
        }

        // 查询任务列表
        List<TaskInfo> taskList = manager.listTasks(statusFilter);

        // 构建 JSON 响应
        return buildListJson(taskList, statusFilter);
    }

    /**
     * 生成用于界面展示的执行摘要，标明是否带状态过滤。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        String status = (String) input.get("status");
        if (status != null && !status.isBlank()) {
            return "📋 Listing tasks [" + status + "]";
        }
        return "📋 Listing all tasks";
    }

    /* ------------------------------------------------------------------ */
    /*  辅助方法                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 将任务列表构建为 JSON 响应。
     *
     * @param taskList     任务列表
     * @param statusFilter 当前使用的过滤条件（用于信息展示），可为 null
     * @return JSON 字符串
     */
    private String buildListJson(List<TaskInfo> taskList, TaskStatus statusFilter) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"total\": ").append(taskList.size()).append(",\n");

        if (statusFilter != null) {
            sb.append("  \"filter\": \"").append(statusFilter.name()).append("\",\n");
        }

        sb.append("  \"tasks\": [");

        if (taskList.isEmpty()) {
            sb.append("]\n}");
            return sb.toString();
        }

        sb.append('\n');
        for (int i = 0; i < taskList.size(); i++) {
            TaskInfo task = taskList.get(i);
            sb.append("    {\n");
            sb.append("      \"task_id\": \"").append(escapeJson(task.id())).append("\",\n");
            sb.append("      \"description\": \"").append(escapeJson(task.description())).append("\",\n");
            sb.append("      \"status\": \"").append(task.status().name()).append("\",\n");

            if (task.result() != null) {
                sb.append("      \"result\": \"").append(escapeJson(task.result())).append("\",\n");
            } else {
                sb.append("      \"result\": null,\n");
            }

            sb.append("      \"created_at\": \"").append(task.createdAt()).append("\",\n");
            sb.append("      \"updated_at\": \"").append(task.updatedAt()).append("\"");

            // 输出元数据
            if (task.metadata() != null && !task.metadata().isEmpty()) {
                sb.append(",\n      \"metadata\": {");
                boolean first = true;
                for (Map.Entry<String, String> entry : task.metadata().entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\n        \"").append(escapeJson(entry.getKey()))
                            .append("\": \"").append(escapeJson(entry.getValue())).append("\"");
                    first = false;
                }
                sb.append("\n      }");
            }

            sb.append("\n    }");
            if (i < taskList.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }

        sb.append("  ]\n}");
        return sb.toString();
    }

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
