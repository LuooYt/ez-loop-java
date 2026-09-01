package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 待办任务工具 —— 管理 AI 工作过程中的待办事项列表，支持创建、更新、完成和删除任务。
 * 任务存储在内存中（ToolContext 的共享状态中），生命周期与会话一致。
 */
public class TodoWriteTool implements Tool {

    /** ToolContext 中待办任务列表的存储键（内存共享状态） */
    private static final String TODOS_KEY = "__todos__";

    /** 待办创建时间展示格式 */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public String name() {
        return "TodoWrite";
    }

    @Override
    public boolean isReadOnly() {
        return true; // 仅操作内存中的 todo 列表，无文件系统副作用
    }

    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            管理对话过程中的待办事项列表。支持的操作：add（添加）、update（更新）、complete（完成）、delete（删除）、list（列出）。\
            用于跟踪多步骤任务、记录进度和组织工作。

            重要：请主动、频繁地使用本工具跟踪进度。在处理多步骤任务时：
            - 在开始工作**之前**创建待办，将任务拆分为清晰的步骤。
            - 工作时始终保持至少一个任务处于 'in_progress' 状态。
            - 每完成一个任务就**立即**标记为 'done'——不要批量处理。
            - 任务无法继续时使用 'blocked' 状态，并说明原因。

            状态流转：pending → in_progress → done（或 blocked）
            优先级：high（优先做）、medium（默认）、low（最后做）
            """);
    }

    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "enum": ["add", "update", "complete", "delete", "list"],
                  "description": "要执行的操作"
                },
                "id": {
                  "type": "string",
                  "description": "任务 ID（update/complete/delete 操作必填）"
                },
                "title": {
                  "type": "string",
                  "description": "任务标题（add 操作必填）"
                },
                "description": {
                  "type": "string",
                  "description": "任务描述（可选）"
                },
                "status": {
                  "type": "string",
                  "enum": ["pending", "in_progress", "done", "blocked"],
                  "description": "任务状态（用于 update）"
                },
                "priority": {
                  "type": "string",
                  "enum": ["high", "medium", "low"],
                  "description": "任务优先级（默认：medium）"
                }
              },
              "required": ["operation"]
            }""");
    }

    /**
     * 执行待办操作：从 ToolContext 获取或初始化待办列表，
     * 按 operation 参数分发到 add/update/complete/delete/list 五种操作。
     */
    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> input, ToolContext context) {
        String operation = (String) input.get("operation");
        if (operation == null) {
            return "Error: 'operation' is required";
        }

        // 从 ToolContext 获取或初始化 todo 列表
        Map<String, TodoItem> todos = context.getOrDefault(TODOS_KEY, null);
        if (todos == null) {
            todos = new ConcurrentHashMap<>();
            context.set(TODOS_KEY, todos);
        }

        return switch (operation) {
            case "add" -> addTodo(input, todos);
            case "update" -> updateTodo(input, todos);
            case "complete" -> completeTodo(input, todos);
            case "delete" -> deleteTodo(input, todos);
            case "list" -> listTodos(todos);
            default -> "Error: Unknown operation '" + operation + "'. Use: add, update, complete, delete, list";
        };
    }

    /** 添加新的待办任务（状态 pending，默认优先级 medium），并返回格式化结果。 */
    private String addTodo(Map<String, Object> input, Map<String, TodoItem> todos) {
        String title = (String) input.get("title");
        if (title == null || title.isBlank()) {
            return "Error: 'title' is required for add operation";
        }

        String id = generateId();
        String description = (String) input.getOrDefault("description", "");
        String priority = (String) input.getOrDefault("priority", "medium");

        TodoItem item = new TodoItem(id, title, description, "pending", priority, LocalDateTime.now());
        todos.put(id, item);

        return "✅ Task added:\n" + formatItem(item);
    }

    /** 更新指定待办任务的标题/描述/状态/优先级字段，并返回格式化结果。 */
    private String updateTodo(Map<String, Object> input, Map<String, TodoItem> todos) {
        String id = (String) input.get("id");
        if (id == null) {
            return "Error: 'id' is required for update operation";
        }

        TodoItem item = todos.get(id);
        if (item == null) {
            return "Error: Task not found: " + id;
        }

        // 更新字段
        String title = (String) input.getOrDefault("title", item.title());
        String description = (String) input.getOrDefault("description", item.description());
        String status = (String) input.getOrDefault("status", item.status());
        String priority = (String) input.getOrDefault("priority", item.priority());

        TodoItem updated = new TodoItem(id, title, description, status, priority, item.createdAt());
        todos.put(id, updated);

        return "✏️ Task updated:\n" + formatItem(updated);
    }

    /** 将指定待办任务标记为完成（done），并返回完成提示。 */
    private String completeTodo(Map<String, Object> input, Map<String, TodoItem> todos) {
        String id = (String) input.get("id");
        if (id == null) {
            return "Error: 'id' is required for complete operation";
        }

        TodoItem item = todos.get(id);
        if (item == null) {
            return "Error: Task not found: " + id;
        }

        TodoItem completed = new TodoItem(id, item.title(), item.description(), "done", item.priority(), item.createdAt());
        todos.put(id, completed);

        return "✅ Task completed: " + item.title();
    }

    /** 删除指定待办任务，并返回删除提示。 */
    private String deleteTodo(Map<String, Object> input, Map<String, TodoItem> todos) {
        String id = (String) input.get("id");
        if (id == null) {
            return "Error: 'id' is required for delete operation";
        }

        TodoItem removed = todos.remove(id);
        if (removed == null) {
            return "Error: Task not found: " + id;
        }

        return "🗑️ Task deleted: " + removed.title();
    }

    /**
     * 列出所有待办任务：按状态分组、组内按优先级排序，
     * 以带状态图标的分组列表形式返回。
     */
    private String listTodos(Map<String, TodoItem> todos) {
        if (todos.isEmpty()) {
            return "📋 No tasks. Use 'add' operation to create one.";
        }

        // 按状态分组，优先级排序
        Map<String, List<TodoItem>> byStatus = todos.values().stream()
                .sorted(Comparator.comparingInt(this::priorityOrder))
                .collect(Collectors.groupingBy(TodoItem::status, LinkedHashMap::new, Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("📋 Task List (").append(todos.size()).append(" tasks)\n");
        sb.append("━".repeat(40)).append("\n");

        for (Map.Entry<String, List<TodoItem>> entry : byStatus.entrySet()) {
            String statusIcon = statusIcon(entry.getKey());
            sb.append("\n").append(statusIcon).append(" ").append(entry.getKey().toUpperCase()).append(":\n");
            for (TodoItem item : entry.getValue()) {
                sb.append(formatItem(item)).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    /**
     * 将单个待办任务格式化为带优先级图标的一行文本（含 ID、标题、描述与创建时间）。
     */
    private String formatItem(TodoItem item) {
        String priorityIcon = switch (item.priority()) {
            case "high" -> "🔴";
            case "medium" -> "🟡";
            case "low" -> "🟢";
            default -> "⚪";
        };
        return String.format("  %s [%s] %s - %s (%s)",
                priorityIcon, item.id(), item.title(),
                item.description().isEmpty() ? "(no description)" : item.description(),
                item.createdAt().format(FMT));
    }

    /** 返回优先级的排序权重（high=0、medium=1、low=2、其他=3）。 */
    private int priorityOrder(TodoItem item) {
        return switch (item.priority()) {
            case "high" -> 0;
            case "medium" -> 1;
            case "low" -> 2;
            default -> 3;
        };
    }

    /** 返回任务状态对应的展示图标。 */
    private String statusIcon(String status) {
        return switch (status) {
            case "pending" -> "⏳";
            case "in_progress" -> "🔄";
            case "done" -> "✅";
            case "blocked" -> "🚫";
            default -> "❓";
        };
    }

    /** 生成短 ID（4 位十六进制） */
    private String generateId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 不可变任务数据记录 */
    record TodoItem(String id, String title, String description, String status,
                    String priority, LocalDateTime createdAt) {
    }

    /**
     * 生成用于界面展示的执行摘要，标明当前执行的待办操作。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        String op = (String) input.getOrDefault("operation", "managing");
        return "📋 Todo: " + op;
    }
}
