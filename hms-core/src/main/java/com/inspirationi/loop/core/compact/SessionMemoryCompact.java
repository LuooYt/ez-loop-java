package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.core.compact.CompactionResult.CompactLayer;
import com.inspirationi.loop.i18n.PromptI18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Session Memory 压缩 —— 保留近期消息段，用 AI 摘要旧消息。
 * <p>
 * 对应 的 sessionMemoryCompact。这是主要的自动压缩方式。
 * 算法：
 * <ol>
 *   <li>找到上次压缩的边界（通过检测 [Conversation Summary] 标记）</li>
 *   <li>计算需要保留的近期消息段（至少保留 MIN_KEEP_TOKENS token 估算量 + MIN_KEEP_TEXT_MSGS 条文本消息）</li>
 *   <li>将边界之后、保留段之前的消息通过 AI 生成摘要</li>
 *   <li>用 [系统提示] + [历史摘要] + [新摘要] + [保留段] 替换历史</li>
 * </ol>
 */
public class SessionMemoryCompact {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryCompact.class);

    /** 最少保留的文本消息数（用户 + 助手） */
    private static final int MIN_KEEP_TEXT_MSGS = 5;

    /** 估算最少保留的 token 数 */
    private static final int MIN_KEEP_TOKENS = 10_000;

    /** 估算最多保留的 token 数 */
    private static final int MAX_KEEP_TOKENS = 40_000;

    /** 每字符估算的 token 数（粗略近似） */
    private static final double CHARS_PER_TOKEN = 4.0;

    /** token 估算安全系数（偏保守，对应 TS 的 4/3 乘数） */
    private static final double ESTIMATION_SAFETY_FACTOR = 4.0 / 3.0;

    /** 会话摘要提示词 —— 中文默认文本，经 {@link PromptI18n} 按系统语言取用 */
    public static final String SUMMARY_PROMPT = """
            请简明但完整地总结以下对话片段。
            保留：
            - 所有关键技术决策及其理由
            - 文件路径、函数名、类名与具体代码标识符
            - 用户需求与偏好
            - 当前工作状态（已完成事项、剩余事项）
            - 遇到的错误及其解决方案
            
            摘要控制在 800 字以内，使用要点形式。
            
            待总结的对话片段：
            """;

    /** 语言模型客户端 —— 用于生成历史摘要 */
    private final ChatModel chatModel;

    /**
     * 构造 Session Memory 压缩器。
     *
     * @param chatModel 用于生成摘要的语言模型
     */
    public SessionMemoryCompact(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 执行 Session Memory 压缩，返回压缩后的新历史。
     * <p>
     * 保留段的起始位置由 {@link #findKeepStart} 按 token 估算并回退到
     * tool_use / tool_result 的配对边界 —— 拆开配对会让下一次请求被服务端拒绝。
     *
     * @param history 当前消息历史（不修改入参）
     * @return 压缩后的新历史；消息太少、可压缩区间不足、摘要生成失败时返回
     *         {@code null}，由调用方决定是否升级到全量压缩
     */
    public List<Message> getCompactedHistory(List<Message> history) {
        if (history.size() <= MIN_KEEP_TEXT_MSGS + 2) return null;

        Message systemMsg = history.getFirst();
        int lastSummaryIndex = findLastSummaryIndex(history);
        int compressibleStart = lastSummaryIndex + 1;
        int keepStart = findKeepStart(history, compressibleStart);

        if (keepStart - compressibleStart < 4) return null;

        List<Message> toCompress = history.subList(compressibleStart, keepStart);
        String summary;
        try {
            summary = generateSummary(toCompress);
        } catch (Exception e) {
            return null;
        }
        if (summary == null || summary.isBlank()) return null;

        return buildCompactedHistory(history, systemMsg, lastSummaryIndex, keepStart, summary);
    }

    /**
     * 构建压缩后的消息历史：{@code [系统提示] + [旧摘要 + 新摘要] + [保留段]}。
     */
    private List<Message> buildCompactedHistory(List<Message> history, Message systemMsg,
                                                 int lastSummaryIndex, int keepStart, String summary) {
        List<Message> newHistory = new ArrayList<>();
        newHistory.add(systemMsg);

        String previousSummary = extractPreviousSummary(history, lastSummaryIndex);
        if (previousSummary != null) {
            summary = "=== Earlier Context ===\n" + previousSummary + "\n\n=== Recent Activity ===\n" + summary;
        }

        newHistory.add(new SystemMessage("[Conversation Summary]\n" + summary));
        for (int i = keepStart; i < history.size(); i++) {
            newHistory.add(history.get(i));
        }

        return newHistory;
    }

    // ── 内部方法 ──

    /** 找到历史中最后一个 [Conversation Summary] 系统消息的索引 */
    private int findLastSummaryIndex(List<Message> history) {
        for (int i = history.size() - 1; i >= 1; i--) {
            if (history.get(i) instanceof SystemMessage sm
                    && sm.getText() != null
                    && sm.getText().startsWith("[Conversation Summary]")) {
                return i;
            }
        }
        return 0; // 没有摘要，从系统提示之后开始
    }

    /** 从末尾向前找保留段的起始位置 */
    private int findKeepStart(List<Message> history, int minStart) {
        int textMsgCount = 0;
        long estimatedTokens = 0;

        for (int i = history.size() - 1; i >= minStart; i--) {
            Message msg = history.get(i);

            // 估算 token 量
            long msgTokens = estimateTokens(msg);
            estimatedTokens += msgTokens;

            if (msg instanceof UserMessage || msg instanceof AssistantMessage) {
                textMsgCount++;
            }

            // 确保不会拆分 tool_use / tool_result 对
            // 如果当前是 ToolResponseMessage，它的 AssistantMessage（含 tool_calls）应在前面
            if (msg instanceof ToolResponseMessage && i > minStart) {
                continue; // 继续往前包含对应的 AssistantMessage
            }

            // 满足最小保留条件，且已达到上限则停止
            if (textMsgCount >= MIN_KEEP_TEXT_MSGS && estimatedTokens >= MIN_KEEP_TOKENS) {
                // 检查是否达到 token 上限
                if (estimatedTokens >= MAX_KEEP_TOKENS) {
                    return i;
                }
            }
        }

        // 如果从 minStart 开始全部都在保留范围内，返回 minStart
        // 说明消息不够多，不需要压缩
        return minStart;
    }

    /** 估算消息的 token 数 */
    private long estimateTokens(Message msg) {
        String text = switch (msg) {
            case UserMessage um -> um.getText();
            case AssistantMessage am -> am.getText();
            case SystemMessage sm -> sm.getText();
            case ToolResponseMessage trm -> {
                StringBuilder sb = new StringBuilder();
                for (var resp : trm.getResponses()) {
                    if (resp.responseData() != null) {
                        sb.append(resp.responseData().toString());
                    }
                }
                yield sb.toString();
            }
            default -> "";
        };
        if (text == null || text.isEmpty()) return 10; // 最小估算
        return (long) (text.length() / CHARS_PER_TOKEN * ESTIMATION_SAFETY_FACTOR);
    }

    /** 提取上一次的摘要文本 */
    private String extractPreviousSummary(List<Message> history, int summaryIndex) {
        if (summaryIndex <= 0) return null;
        Message msg = history.get(summaryIndex);
        if (msg instanceof SystemMessage sm && sm.getText() != null) {
            String text = sm.getText();
            if (text.startsWith("[Conversation Summary]\n")) {
                return text.substring("[Conversation Summary]\n".length());
            }
            if (text.startsWith("[Conversation Summary] ")) {
                return text.substring("[Conversation Summary] ".length());
            }
        }
        return null;
    }

    /** 调用 AI 生成对话段摘要 */
    private String generateSummary(List<Message> segment) {
        StringBuilder dialogText = new StringBuilder();
        for (Message msg : segment) {
            switch (msg) {
                case UserMessage um -> dialogText.append("[User] ").append(um.getText()).append("\n");
                case AssistantMessage am -> {
                    if (am.getText() != null && !am.getText().isBlank()) {
                        String text = am.getText();
                        if (text.length() > 800) text = text.substring(0, 800) + "...";
                        dialogText.append("[Assistant] ").append(text).append("\n");
                    }
                    if (am.hasToolCalls()) {
                        for (var tc : am.getToolCalls()) {
                            dialogText.append("[Tool Call] ").append(tc.name()).append("\n");
                        }
                    }
                }
                case ToolResponseMessage trm -> {
                    for (var resp : trm.getResponses()) {
                        String data = resp.responseData() != null ? resp.responseData().toString() : "";
                        if (data.length() > 200) data = data.substring(0, 200) + "...";
                        dialogText.append("[Tool Result: ").append(resp.name()).append("] ")
                                .append(data).append("\n");
                    }
                }
                default -> {}
            }
        }

        if (dialogText.isEmpty()) return null;

        String promptText = PromptI18n.t(PromptI18n.KEY_SESSION_COMPACT_PROMPT, SUMMARY_PROMPT);
        Prompt prompt = new Prompt(List.of(new UserMessage(promptText + dialogText)));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }
}
