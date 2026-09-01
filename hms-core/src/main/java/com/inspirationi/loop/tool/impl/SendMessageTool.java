package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * SendMessage 工具 —— 在 Coordinator 模式下用于向正在运行的 worker agent 发送消息，
 * 支持继续执行、提供反馈或请求停止。
 * <p>
 * 消息类型：
 * <ul>
 *   <li>普通文本 —— 继续指示或额外上下文</li>
 *   <li>shutdown_request —— 请求 worker 优雅退出</li>
 *   <li>broadcast —— 向所有 worker 广播（to="*"）</li>
 * </ul>
 */
public class SendMessageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SendMessageTool.class);

    /** 工具名称常量："SendMessage" */
    public static final String TOOL_NAME = "SendMessage";

    /** ToolContext key for pending messages map: Map<String, List<String>> */
    public static final String PENDING_MESSAGES_KEY = "__pending_messages__";

    /**
     * 返回工具名称（"SendMessage"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return TOOL_NAME;
    }

    /**
     * 返回工具描述，说明向 worker agent 发送消息的用途与消息类型。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            向正在运行的 worker agent（协作者）发送消息。用于：
            - 在 worker 完成任务后，向其发送额外的后续指令
            - 为正在运行的 worker 提供后续上下文或修正
            - 请求 worker 停止（shutdown_request）
            - 向所有 worker 广播消息（to="*"）

            消息将被排队并在 worker 的下一个工具轮次递送。
            如果 worker 已完成，它将使用新消息重新被唤醒。

            重要：
            - Worker 无法看到协调者的对话历史。
            - 请在消息中包含所有必要的上下文。
            - 强制终止 worker 使用 TaskStop；SendMessage 用于友好沟通。
            """);
    }

    /**
     * 返回输入 JSON Schema，定义 to、message（必填）与 summary（可选）参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "to": {
                  "type": "string",
                  "description": "接收者：任务 ID、agent 名称，或 '*' 表示广播"
                },
                "message": {
                  "type": "string",
                  "description": "要发送的消息内容"
                },
                "summary": {
                  "type": "string",
                  "description": "消息的简要概述（5-10 个词）"
                }
              },
              "required": ["to", "message"]
            }""");
    }

    /**
     * 执行消息发送：to 为 "*" 时向所有运行中的 worker 广播，
     * 否则定向发送给指定任务/agent，消息均会排队等待 worker 下一轮接收。
     */
    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> input, ToolContext context) {
        String to = (String) input.get("to");
        String message = (String) input.get("message");
        String summary = (String) input.getOrDefault("summary", "");

        if (to == null || to.isBlank()) {
            return "Error: 'to' is required — specify a task ID, agent name, or '*' for broadcast";
        }
        if (message == null || message.isBlank()) {
            return "Error: 'message' is required";
        }

        TaskManager taskManager = context.getOrDefault("TASK_MANAGER", null);
        if (taskManager == null) {
            return "Error: TaskManager not available";
        }

        // Broadcast to all running workers
        if ("*".equals(to)) {
            return handleBroadcast(message, summary, taskManager, context);
        }

        // Send to specific worker
        return handleDirectMessage(to, message, summary, taskManager, context);
    }

    /**
     * 向指定 worker 发送消息：先按任务 ID 查找，找不到时按描述关键字匹配；
     * 找到后把消息写入待发送队列，并根据任务当前状态返回相应提示。
     */
    private String handleDirectMessage(String to, String message, String summary,
                                        TaskManager taskManager, ToolContext context) {
        var taskOpt = taskManager.getTask(to);
        if (taskOpt.isEmpty()) {
            // Try to find by description/name match
            var allTasks = taskManager.listTasks();
            var matched = allTasks.stream()
                    .filter(t -> t.description().toLowerCase().contains(to.toLowerCase()))
                    .findFirst();
            if (matched.isEmpty()) {
                return "Error: No task found with ID or name matching '" + to + "'";
            }
            taskOpt = matched;
        }

        var task = taskOpt.get();

        // Queue the message for the worker
        queueMessage(task.id(), message, context);

        String statusInfo = switch (task.status()) {
            case RUNNING -> "Message queued for running worker '" + task.description() + "'";
            case COMPLETED -> "Worker '" + task.description() + "' has completed. "
                    + "Message stored but worker will need to be re-spawned to receive it.";
            case PENDING -> "Message queued for pending worker '" + task.description() + "'";
            case FAILED -> "Warning: Worker '" + task.description() + "' has failed. "
                    + "Message stored but worker may need to be re-spawned.";
            case CANCELLED -> "Warning: Worker '" + task.description() + "' was cancelled. "
                    + "Message stored but worker will need to be re-spawned.";
        };

        log.info("SendMessage to {}: {}", task.id(),
                summary.isBlank() ? truncate(message, 50) : summary);

        return statusInfo;
    }

    /**
     * 向所有运行中的 worker 广播同一消息，并返回广播结果摘要。
     */
    private String handleBroadcast(String message, String summary,
                                    TaskManager taskManager, ToolContext context) {
        var runningTasks = taskManager.listTasks(TaskManager.TaskStatus.RUNNING);
        if (runningTasks.isEmpty()) {
            return "No running workers to broadcast to.";
        }

        int count = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Broadcast sent to ").append(runningTasks.size()).append(" worker(s):\n");

        for (var task : runningTasks) {
            queueMessage(task.id(), message, context);
            sb.append("  • ").append(task.id()).append(" (").append(task.description()).append(")\n");
            count++;
        }

        log.info("Broadcast to {} workers: {}",
                count, summary.isBlank() ? truncate(message, 50) : summary);

        return sb.toString().stripTrailing();
    }

    /**
     * 将消息追加到 ToolContext 中该任务的待发送消息队列（线程安全，
     * 队列不存在时先创建并写回上下文）。
     */
    @SuppressWarnings("unchecked")
    private void queueMessage(String taskId, String message, ToolContext context) {
        Map<String, java.util.List<String>> pendingMessages =
                context.getOrDefault(PENDING_MESSAGES_KEY, null);

        if (pendingMessages == null) {
            pendingMessages = new java.util.concurrent.ConcurrentHashMap<>();
            context.set(PENDING_MESSAGES_KEY, pendingMessages);
        }

        pendingMessages.computeIfAbsent(taskId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(message);
    }

    /**
     * 将文本截断到指定长度，超出部分以省略号结尾（用于日志输出）。
     */
    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    /**
     * 生成用于界面展示的执行摘要，标明接收者与消息摘要。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        String to = (String) input.getOrDefault("to", "?");
        String summary = (String) input.getOrDefault("summary", "");
        if (!summary.isBlank()) {
            return "📨 SendMessage to " + to + ": " + summary;
        }
        return "📨 SendMessage to " + to;
    }
}
