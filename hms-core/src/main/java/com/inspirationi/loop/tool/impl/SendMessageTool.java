package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;

/**
 * SendMessage 工具 —— <b>当前构建中未实现</b>，保留本类只为二进制兼容。
 * <p>
 * <b>为什么不在默认工具集里</b>：旧实现把消息写进 {@code ToolContext} 的
 * {@value #PENDING_MESSAGES_KEY} 键，而全仓库没有任何代码读取它 —— 也就是说
 * 消息被丢进了一个没人看的抽屉，工具却向模型返回「Message queued」。
 * 模型据此认为「我已经把后续指令交给 worker 了」，继续基于这个虚假事实推理，
 * 最终交付一个根本没被任何人执行的任务。
 * <p>
 * <b>为什么不顺手接上真正的投递</b>：本工具描述的目标是 {@code TaskManager} 里的
 * 后台任务，而它们的任务体是一个普通的 {@link java.util.concurrent.Callable}，
 * 跑完即结束，根本没有「收件箱」这种东西可投。{@code AgentWorkQueue} 是心跳
 * Agent 的调度队列，语义不同 —— 把它硬接进来只会造出第二条「看起来成功、
 * 实际没投递」的路径。
 * <p>
 * 因此 {@link #execute} 直接返回明确错误，并把可行的替代做法告诉模型：
 * 取消用 {@code TaskStop}，补充上下文就重新建一个任务。
 *
 * @deprecated 消息投递尚未实现；请使用 {@code TaskStop} 取消任务，或新建任务携带
 *             额外上下文。本类将在下一个主版本移除。
 */
@Deprecated(since = "1.1", forRemoval = true)
public class SendMessageTool implements Tool {

    /** 工具名称常量："SendMessage" */
    public static final String TOOL_NAME = "SendMessage";

    /** 旧实现使用的上下文键，保留仅为兼容既有引用；不再有写入方。 */
    @Deprecated(since = "1.1", forRemoval = true)
    public static final String PENDING_MESSAGES_KEY = "__pending_messages__";

    /**
     * 未实现时的统一回复。
     * <p>
     * 措辞要点：先否认「投递成功」，再解释原因，最后给出可执行的下一步 ——
     * 只说「不支持」会让模型换个说法反复重试。
     */
    static final String NOT_IMPLEMENTED =
            "Error: SendMessage is not implemented — nothing was delivered. "
            + "Background tasks run a plain work body with no inbox, so there is no channel "
            + "to deliver a message to. Use TaskStop to cancel a task, or create a new task "
            + "that includes the extra context.";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            [不可用] 向后台任务发送消息的能力尚未实现：调用本工具不会投递任何消息。

            要取消任务请用 TaskStop；需要补充上下文时，新建一个任务并把上下文写进它的描述。

            保留本工具仅为兼容已有引用，请勿依赖其返回值判断消息是否送达。
            """);
    }

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
     * 参数校验后返回 {@link #NOT_IMPLEMENTED}。
     * <p>
     * 仍然先校验参数：这样模型得到的是「你少传了 to」这类可自我纠正的反馈，
     * 而不是把参数错误和「功能缺失」混为一谈。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        Object to = input == null ? null : input.get("to");
        if (!(to instanceof String toText) || toText.isBlank()) {
            return "Error: 'to' is required — specify a task ID, agent name, or '*' for broadcast";
        }
        Object message = input.get("message");
        if (!(message instanceof String messageText) || messageText.isBlank()) {
            return "Error: 'message' is required";
        }
        return NOT_IMPLEMENTED;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        Object to = input == null ? null : input.get("to");
        return "📨 SendMessage to " + (to != null ? to : "?") + " (unavailable)";
    }
}
