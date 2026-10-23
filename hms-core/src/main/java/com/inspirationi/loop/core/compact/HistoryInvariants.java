package com.inspirationi.loop.core.compact;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩历史的结构不变量 —— 压缩层「不许产出」什么形状的历史。
 * <p>
 * <b>为什么需要它</b>：上下文压缩（尤其是全量压缩）是在「删消息」的刀口上作业，
 * 很容易切在 {@code tool_use} / {@code tool_result} 之间。一旦切开，下一次请求就会
 * 带着孤立的 tool 响应发给上游，被服务端以 400 拒绝 —— 表现为「聊到一定长度后
 * 会话突然发不出去」，而日志里只有一次 HTTP 400，看不出是压缩造成的。
 * <p>
 * 本类因此提供两件事：
 * <ul>
 *   <li>{@link #alignKeepStart} —— 压缩前把「保留段起点」向前对齐到配对边界，从源头避免切错；</li>
 *   <li>{@link #violations} / {@link #verify} —— 压缩后校验结果，非法历史绝不交给调用方。</li>
 * </ul>
 * 纯函数、不依赖 Spring 上下文，便于单测。
 */
public final class HistoryInvariants {

    private HistoryInvariants() {
    }

    /**
     * 把「保留段起点」向前对齐，使其不会落在一条工具响应上。
     * <p>
     * 若 {@code proposedStart} 指向 {@link ToolResponseMessage}，说明保留段会以
     * tool 响应开头、而携带 {@code tool_calls} 的助手消息被切进了摘要区 ——
     * 这正是上游 400 的成因。向前回退到该助手消息即可闭合配对；
     * 连续多条工具响应时继续回退。
     * <p>
     * 下界固定为 {@code 1}：第 0 条是系统提示，压缩产物会单独写入一条系统消息，
     * 保留段若从 0 开始会把系统提示重复一遍。
     *
     * @param history       原始历史
     * @param proposedStart 调用方按 token 预算算出的起点
     * @return 对齐后的起点，保证 {@code history.get(result)} 不是工具响应
     */
    public static int alignKeepStart(List<Message> history, int proposedStart) {
        int start = Math.max(1, proposedStart);
        if (start >= history.size()) {
            return history.isEmpty() ? 0 : history.size() - 1;
        }
        while (start > 1 && history.get(start) instanceof ToolResponseMessage) {
            start--;
        }
        return start;
    }

    /**
     * 校验历史是否满足消息协议的不变量，返回全部违规项（空列表表示合法）。
     * <p>
     * 校验规则：
     * <ol>
     *   <li>历史非空；</li>
     *   <li>不存在既无文本、也无工具调用、也无媒体的「空助手消息」；</li>
     *   <li>每条工具响应都能对上前面某条助手消息里的工具调用（不能是孤儿）；</li>
     *   <li>一条助手消息的工具调用，在被下一条消息打断前必须全部收到响应；</li>
     *   <li>例外：允许<b>最后一条</b>助手消息留下的工具调用暂时没有响应 —— 那是
     *       AgentLoop 刚拿到模型回复、工具还没执行的「在途」状态，此时压缩是合法的，
     *       工具结果会在下一次迭代写入。</li>
     * </ol>
     * <b>刻意不校验「首条必须是系统提示」</b>：压缩层的职责是保住配对关系，而不是
     * 修正调用方给的输入形状。真实链路里 AgentLoop 的历史第 0 条恒为系统提示，
     * 但把这条规则塞进通用校验只会让「输入本身没有系统提示」的历史压缩永久失败。
     * 返回列表而非直接抛异常，是为了让调用方既能拒绝应用、又能把具体索引写进日志。
     *
     * @param history 待校验的历史
     * @return 违规描述；合法时为空列表
     */
    public static List<String> violations(List<Message> history) {
        List<String> problems = new ArrayList<>();
        if (history == null || history.isEmpty()) {
            problems.add("历史为空");
            return problems;
        }
        // 尚未收到响应的工具调用。key 用调用 id；模型偶尔不给 id 时退化为
        // 「call#序号」，与响应侧的空 id 按顺序配对（见 matchPending）。
        List<String> pending = new ArrayList<>();
        // pending 的归属：最后一条产生工具调用的助手消息下标，用于区分
        // 「在途（合法）」与「被下一条消息打断（非法）」。
        int pendingOwner = -1;

        for (int i = 0; i < history.size(); i++) {
            Message msg = history.get(i);

            if (msg instanceof AssistantMessage assistant) {
                if (pendingOwner >= 0 && !pending.isEmpty()) {
                    problems.add("第 " + pendingOwner + " 条助手消息的工具调用未收到响应，"
                            + "却被第 " + i + " 条消息打断");
                }
                pending.clear();
                pendingOwner = -1;

                boolean hasText = assistant.getText() != null && !assistant.getText().isBlank();
                boolean hasToolCalls = assistant.hasToolCalls();
                boolean hasMedia = assistant.getMedia() != null && !assistant.getMedia().isEmpty();
                if (!hasText && !hasToolCalls && !hasMedia) {
                    problems.add("第 " + i + " 条是空助手消息（无文本、无工具调用、无媒体）");
                }
                if (hasToolCalls) {
                    for (int c = 0; c < assistant.getToolCalls().size(); c++) {
                        AssistantMessage.ToolCall call = assistant.getToolCalls().get(c);
                        pending.add(callKey(call, c));
                    }
                    pendingOwner = i;
                }
            } else if (msg instanceof ToolResponseMessage responses) {
                for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
                    if (!matchPending(pending, response.id())) {
                        problems.add("第 " + i + " 条是孤儿工具响应（id="
                                + response.id() + "），前面没有对应的工具调用");
                    }
                }
            } else if (!pending.isEmpty()) {
                problems.add("第 " + pendingOwner + " 条助手消息的工具调用未收到响应，"
                        + "却被第 " + i + " 条消息打断");
                pending.clear();
                pendingOwner = -1;
            }
        }

        // 走到这里 pending 仍非空 = 最后一条助手消息的工具调用尚未执行，合法（在途）。
        return problems;
    }

    /**
     * 严格校验：存在任何违规即抛 {@link IllegalStateException}。
     *
     * @throws IllegalStateException 历史违反消息协议不变量
     */
    public static void verify(List<Message> history) {
        List<String> problems = violations(history);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
    }

    /** 工具调用的配对键：优先用服务端 id，缺失时退化为位置序号。 */
    private static String callKey(AssistantMessage.ToolCall call, int index) {
        String id = call.id();
        return id != null && !id.isBlank() ? id : "call#" + index;
    }

    /**
     * 在 pending 中查找并移除一条匹配的调用。
     * <p>
     * 响应 id 缺失时无法做 id 匹配，按「调整后的调用顺序」与响应顺序一致这一协议
     * 约定，移除最早的一条未匹配项 —— 宁可放过缺失 id 的模型，也不要误报。
     */
    private static boolean matchPending(List<String> pending, String responseId) {
        if (pending.isEmpty()) {
            return false;
        }
        if (responseId != null && !responseId.isBlank()) {
            int index = pending.indexOf(responseId);
            if (index >= 0) {
                pending.remove(index);
                return true;
            }
            return false;
        }
        pending.removeFirst();
        return true;
    }
}
