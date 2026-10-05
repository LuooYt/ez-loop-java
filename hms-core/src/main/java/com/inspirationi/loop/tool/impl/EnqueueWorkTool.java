package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.agent.AgentWorkQueue;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;

/**
 * 工作入队工具 —— 让主动 Agent 自主安排「下一轮心跳该做什么」。
 * <p>
 * 这是主动性的自主续作闭环：一次心跳里发现「还差一步」「明天要复查」，与其把这段
 * 意图留在会话历史里指望下次想起来，不如显式排进工作队列 —— 队列非空时，下一轮
 * 心跳会优先取出它执行，而不是走空转自省。
 * <p>
 * 被动 Agent 的队列无人消费，入队只会积压；因此本工具在被动 Agent 上不注册。
 * 队列容量由集成方把控（无硬上限），低风险自动放行 —— 但它是<b>有限资源</b>，
 * 提示词里明确要求只排「确实该做」的事，而不是把待办清单一股脑塞进去。
 */
public class EnqueueWorkTool implements Tool {

    @Override
    public String name() {
        return "EnqueueWork";
    }

    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            把一项后续工作排进自己的待办队列，由下一次心跳周期自动取出执行。

            适用场景：
            - 本轮任务的后续步骤需要等一段时间（复查、轮询、跟进）
            - 你已经决定「这件事还需要再做一次」，且不依赖用户的下一条消息

            不适用场景：
            - 现在就能做完的事（直接做，别排队）
            - 需要用户先给信息才能进行的（排了也做不成）
            - 一次排入多项——请合并成一项，或只排最紧要的那项

            排入的任务在下一轮心跳时才会开始执行；本轮回复不会等它。
            """);
    }

    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "prompt": {
                  "type": "string",
                  "description": "下次要执行的任务，写成自包含的完整指令（不要写「继续上面的」这类指代，那时上下文可能已不在）"
                },
                "source": {
                  "type": "string",
                  "description": "可选来源标识，便于追溯（如 follow-up、reminder）"
                }
              },
              "required": ["prompt"]
            }""");
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    /** 低风险：只写内存队列、不触发任何副作用，默认模式下自动放行。 */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        AgentWorkQueue queue = context.get(AgentWorkQueue.CONTEXT_KEY);
        if (queue == null) {
            return "当前智能体没有工作队列（非主动智能体或未配置），无法排入后续工作。";
        }
        String prompt = input.get("prompt") instanceof String s ? s : null;
        if (prompt == null || prompt.isBlank()) {
            return "Error: 'prompt' is required";
        }
        String source = input.get("source") instanceof String s && !s.isBlank() ? s : "agent";
        AgentWorkQueue.WorkItem item = queue.enqueue(prompt, source);
        return "已排入待办队列（id=" + item.id() + "，队列长度 " + queue.size() + "），"
                + "将在下一次心跳时执行。";
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "正在安排后续工作...";
    }
}
