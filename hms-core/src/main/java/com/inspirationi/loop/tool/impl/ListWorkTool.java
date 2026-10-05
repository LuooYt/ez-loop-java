package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.agent.AgentWorkQueue;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.List;
import java.util.Map;

/**
 * 待办查询工具 —— 查看自己的工作队列里还排着什么。
 * <p>
 * 存在的理由是可预期性：模型排了一项工作之后，如果完全看不到队列状态，
 * 它会在下一轮里重复排同一件事（「我记得好像安排过，但不确认」）。让它能查，
 * 重复入队才会自然消失。同时这也让「我这个主动 Agent 现在积压了多少」对模型可见。
 * <p>
 * 只读，且仅对本 Agent 自己可见 —— 队列是私有的，不存在跨 Agent 窥视。
 */
public class ListWorkTool implements Tool {

    /** 清单里最多列出多少项（按入队顺序，从前到后）。 */
    private static final int MAX_LISTED = 20;

    @Override
    public String name() {
        return "ListWork";
    }

    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            查看自己待办队列里还没执行的工作项。

            适用场景：
            - 准备用 EnqueueWork 排新任务之前，先确认是否已经排过（避免重复）
            - 确认某项跟进工作是否仍在队列中

            返回各工作项的 id、来源、入队时间与任务描述。队列为空表示下一次心跳
            将走自省流程。
            """);
    }

    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {},
              "required": []
            }""");
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        AgentWorkQueue queue = context.get(AgentWorkQueue.CONTEXT_KEY);
        if (queue == null) {
            return "当前智能体没有工作队列（非主动智能体或未配置）。";
        }
        List<AgentWorkQueue.WorkItem> items = queue.snapshot();
        if (items.isEmpty()) {
            return "待办队列为空 —— 下一次心跳将使用自省模板。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("待办队列共 ").append(items.size()).append(" 项：\n");
        int shown = 0;
        for (AgentWorkQueue.WorkItem item : items) {
            if (shown++ >= MAX_LISTED) {
                sb.append("… 其余 ").append(items.size() - MAX_LISTED).append(" 项未列出\n");
                break;
            }
            sb.append("- [").append(item.id()).append("] (")
              .append(item.source()).append(", ").append(item.createdAt()).append(") ")
              .append(item.prompt()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "正在查看待办队列...";
    }
}
