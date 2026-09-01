package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.core.TaskManager.TaskInfo;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;
import java.util.Optional;

/**
 * TaskStop 工具 —— 停止正在运行的后台任务，通过 TaskManager.cancelTask() 取消任务执行。
 */
public class TaskStopTool implements Tool {

    /** ToolContext 中 TaskManager 的存储键 */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    /**
     * 返回工具名称（"TaskStop"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "TaskStop";
    }

    /**
     * 返回工具描述，说明按 ID 停止运行中后台任务的用途与限制。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            按 ID 停止正在运行的后台任务。当你需要终止一个不再需要或似乎卡住的长时运行任务时使用。\
            返回成功或失败状态。处于终态（COMPLETED/FAILED/CANCELLED）的任务无法停止。
            """);
    }

    /**
     * 返回输入 JSON Schema，定义 task_id（必填）参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "task_id": {
                  "type": "string",
                  "description": "要停止的任务 ID"
                }
              },
              "required": ["task_id"]
            }""");
    }

    /**
     * 停止指定后台任务：先确认任务存在，再调用 TaskManager.cancelTask 取消。
     * 已处于终态（COMPLETED/FAILED/CANCELLED）的任务无法停止。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String taskId = (String) input.get("task_id");

        if (taskId == null || taskId.isBlank()) {
            return "Error: 'task_id' is required";
        }

        TaskManager taskManager = context.getOrDefault(TASK_MANAGER_KEY, null);
        if (taskManager == null) {
            return "Error: TaskManager is not available";
        }

        // Check if task exists first
        Optional<TaskInfo> taskOpt = taskManager.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return "Error: Task not found: " + taskId;
        }

        TaskInfo task = taskOpt.get();
        String previousStatus = task.status().name();

        boolean cancelled = taskManager.cancelTask(taskId);
        if (cancelled) {
            return String.format("""
                {"status": "stopped", "task_id": "%s", "previous_status": "%s", \
                "description": "%s"}""",
                    taskId, previousStatus, escapeJson(task.description()));
        } else {
            return String.format(
                    "Error: Cannot stop task %s — it is already in terminal state: %s",
                    taskId, previousStatus);
        }
    }

    /**
     * 生成用于界面展示的执行摘要，标明要停止的任务 ID。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🛑 Stopping task " + input.getOrDefault("task_id", "...");
    }

    /**
     * 转义字符串中的 JSON 特殊字符（反斜杠、引号、换行）。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
