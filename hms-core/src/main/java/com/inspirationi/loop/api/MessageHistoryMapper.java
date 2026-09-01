package com.inspirationi.loop.api;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Spring AI 的消息历史映射为面向 UI 的扁平视图。
 * <p>
 * Spring AI 的消息模型是<b>面向 API 协议</b>的：工具调用挂在 {@link AssistantMessage}
 * 上，其结果是另一条独立的 {@link ToolResponseMessage}，两者靠 id 关联。这个结构
 * 便于向模型发送，却不便于渲染 —— UI 想展示的是「调用了 X 工具，参数是 Y，结果是 Z」
 * 这样一条完整记录。Spring AI 不提供这种视图，故自行映射。
 * <p>
 * 两条容易被忽略的规则：
 * <ul>
 *   <li><b>空白 assistant 消息不产生记录</b> —— 模型只发起工具调用、不说话时会产生
 *       一条无文本的 AssistantMessage，它是协议上的中转态，渲染成空气泡是噪音。</li>
 *   <li><b>未配对的工具调用仍要输出</b> —— 循环因取消、Hook 中止等原因提前结束时，
 *       调用可能没有对应结果。丢掉它们会让 UI 看不到「这个工具被叫过但没跑完」。</li>
 * </ul>
 */
final class MessageHistoryMapper {

    private MessageHistoryMapper() {
    }

    /**
     * 将消息历史转换为中立 DTO 列表。
     * <p>
     * 工具调用与其结果按 id 配对为一条含 {@code name / arguments / result} 的
     * tool 记录；配不上的调用在末尾以 {@code result = null} 输出。
     *
     * @param history 消息历史（通常来自 {@code AgentLoop.copyMessageHistory()}）
     * @return 不可变的 UI 视图列表
     */
    static List<ChatMessage> toChatMessages(List<Message> history) {
        List<ChatMessage> out = new ArrayList<>();
        // 尚未配对的工具调用：toolCallId -> [name, arguments]
        Map<String, String[]> pendingCalls = new LinkedHashMap<>();

        for (Message m : history) {
            switch (m.getMessageType()) {
                case SYSTEM -> out.add(new ChatMessage("system",
                        ((SystemMessage) m).getText(), null, null, null));
                case USER -> out.add(new ChatMessage("user",
                        ((UserMessage) m).getText(), null, null, null));
                case ASSISTANT -> {
                    AssistantMessage am = (AssistantMessage) m;
                    // 有文本 → 一条 assistant 文本记录（工具中转态的空白 assistant 不产生气泡）
                    if (am.getText() != null && !am.getText().isBlank()) {
                        out.add(new ChatMessage("assistant", am.getText(), null, null, null));
                    }
                    // 工具调用暂存，等待紧随的 ToolResponse 配对
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        pendingCalls.put(tc.id(), new String[]{tc.name(), tc.arguments()});
                    }
                }
                case TOOL -> {
                    ToolResponseMessage trm = (ToolResponseMessage) m;
                    for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                        String[] call = (r.id() != null) ? pendingCalls.remove(r.id()) : null;
                        out.add(new ChatMessage("tool",
                                null,
                                (call != null) ? call[0] : r.name(),
                                (call != null) ? call[1] : null,
                                r.responseData()));
                    }
                }
                default -> {
                    // 其他消息类型（如未来新增的）不参与 UI 渲染
                }
            }
        }
        // 兜底：未捕获结果的 toolCall 原样输出，让 UI 能看出它被发起过
        for (var e : pendingCalls.entrySet()) {
            out.add(new ChatMessage("tool", null, e.getValue()[0], e.getValue()[1], null));
        }
        return List.copyOf(out);
    }
}
