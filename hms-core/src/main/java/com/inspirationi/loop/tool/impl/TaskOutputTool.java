package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.core.TaskManager.TaskInfo;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;
import java.util.Optional;

/**
 * TaskOutput 工具 —— 获取任务的执行输出/结果。当任务完成后，可以通过此工具读取其结果。
 * 对于正在运行的任务，返回当前状态信息。
 */
public class TaskOutputTool implements Tool {

    /** ToolContext 中 TaskManager 的存储键 */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    /**
     * 返回工具名称（"TaskOutput"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "TaskOutput";
    }

    /**
     * 返回工具描述，说明获取任务输出/结果、检查运行状态等用途。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            获取任务的输出/结果。用于获取已完成任务的结果，或检查正在运行任务的当前状态。\
            对于已完成任务，返回完整执行结果；对于运行中任务，返回当前状态。
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
                  "description": "要获取输出的任务 ID"
                }
              },
              "required": ["task_id"]
            }""");
    }

    /**
     * 该工具仅读取任务输出、不修改任何状态，标记为只读。
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 按 task_id 获取任务输出：根据任务状态返回对应 JSON，
     * 完成返回结果、失败返回错误信息、运行中/待执行返回状态提示。
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

        Optional<TaskInfo> taskOpt = taskManager.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return "Error: Task not found: " + taskId;
        }

        TaskInfo task = taskOpt.get();

        return switch (task.status()) {
            case COMPLETED -> {
                String result = task.result();
                yield String.format("""
                    {"task_id": "%s", "status": "COMPLETED", "description": "%s", "result": "%s"}""",
                        taskId, escapeJson(task.description()),
                        escapeJson(result != null ? result : "(no output)"));
            }
            case FAILED -> {
                String error = task.result();
                yield String.format("""
                    {"task_id": "%s", "status": "FAILED", "description": "%s", "error": "%s"}""",
                        taskId, escapeJson(task.description()),
                        escapeJson(error != null ? error : "(unknown error)"));
            }
            case CANCELLED -> String.format("""
                {"task_id": "%s", "status": "CANCELLED", "description": "%s"}""",
                    taskId, escapeJson(task.description()));
            case RUNNING -> String.format("""
                {"task_id": "%s", "status": "RUNNING", "description": "%s", \
                "message": "Task is still running. Check back later."}""",
                    taskId, escapeJson(task.description()));
            case PENDING -> String.format("""
                {"task_id": "%s", "status": "PENDING", "description": "%s", \
                "message": "Task has not started yet."}""",
                    taskId, escapeJson(task.description()));
        };
    }

    /**
     * 生成用于界面展示的执行摘要，标明要读取输出的任务 ID。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        return "📋 Getting output of task " + input.getOrDefault("task_id", "...");
    }

    /**
     * 转义字符串中的 JSON 特殊字符（反斜杠、引号、换行）。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
