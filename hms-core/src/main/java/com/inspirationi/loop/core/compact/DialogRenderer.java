package com.inspirationi.loop.core.compact;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * 把消息历史渲染成供大模型摘要的纯文本。
 * <p>
 * {@link FullCompact} 与 {@link SessionMemoryCompact} 都需要它，此前各写了一份
 * 近乎相同的 switch。两者的差异是有意的、也是这里唯一需要参数化的部分：
 * <ul>
 *   <li><b>助手文本截断长度</b> —— 全量压缩更激进（丢得更多才能腾出空间）</li>
 *   <li><b>是否保留工具结果内容</b> —— 全量压缩只留工具名，Session Memory 保留
 *       前若干字符，因为它要支撑「最近做过什么」这类追问</li>
 * </ul>
 * 渲染格式本身必须一致：摘要提示词是按 {@code [User] / [Assistant] / [Tool Call] /
 * [Tool Result]} 这套标记写的，两处若各自演化，其中一处的摘要质量会静默下降。
 */
final class DialogRenderer {

    /** 助手文本超过该长度即截断（0 表示不截断）。 */
    private final int assistantTextLimit;
    /** 工具结果内容保留的最大长度；{@code 0} 表示只渲染工具名、不带内容。 */
    private final int toolResultLimit;

    private DialogRenderer(int assistantTextLimit, int toolResultLimit) {
        this.assistantTextLimit = assistantTextLimit;
        this.toolResultLimit = toolResultLimit;
    }

    /** 全量压缩用：助手文本截到 600 字符，工具结果只留名称。 */
    static DialogRenderer forFullCompact() {
        return new DialogRenderer(600, 0);
    }

    /** Session Memory 用：助手文本截到 800 字符，工具结果保留前 200 字符。 */
    static DialogRenderer forSessionMemory() {
        return new DialogRenderer(800, 200);
    }

    /**
     * 渲染一段消息为对话文本。
     *
     * @return 渲染结果；无可渲染内容时返回空字符串
     */
    String render(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (Message msg : messages) {
            appendMessage(out, msg);
        }
        return out.toString();
    }

    private void appendMessage(StringBuilder out, Message msg) {
        switch (msg) {
            case UserMessage um -> out.append("[User] ").append(um.getText()).append("\n");
            case AssistantMessage am -> {
                String text = am.getText();
                if (text != null && !text.isBlank()) {
                    out.append("[Assistant] ").append(truncate(text, assistantTextLimit)).append("\n");
                }
                if (am.hasToolCalls()) {
                    for (var tc : am.getToolCalls()) {
                        out.append("[Tool Call] ").append(tc.name()).append("\n");
                    }
                }
            }
            case ToolResponseMessage trm -> {
                for (var resp : trm.getResponses()) {
                    out.append("[Tool Result: ").append(resp.name()).append("]");
                    if (toolResultLimit > 0) {
                        String data = resp.responseData() != null ? resp.responseData().toString() : "";
                        out.append(" ").append(truncate(data, toolResultLimit));
                    }
                    out.append("\n");
                }
            }
            default -> {
                // SystemMessage 等不参与摘要：系统提示词在压缩后会原样保留，
                // 把它塞进待摘要文本只会让模型把指令误当成对话内容。
            }
        }
    }

    private static String truncate(String text, int limit) {
        if (limit <= 0 || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "...";
    }
}
