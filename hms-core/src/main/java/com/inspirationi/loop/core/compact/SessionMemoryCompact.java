package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.i18n.PromptI18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
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

    /** 待摘要文本的渲染器 —— 保留工具结果前 200 字符，以支撑「最近做过什么」类追问。 */
    private static final DialogRenderer RENDERER = DialogRenderer.forSessionMemory();

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
     * 一次压缩尝试的结果。
     * <p>
     * <b>「没什么可压缩」与「压缩失败」必须分开</b>：前者是正常状态（历史还短，
     * 或上次压缩后新增不多），后者才说明摘要通路出了问题。二者若都用
     * {@code null} 表示，{@link AutoCompactManager} 只能一律记作失败并推向熔断 ——
     * 而熔断是永久的，一段本就没什么可压缩的短历史会因此被永久禁用压缩。
     *
     * @param history 压缩后的新历史；未压缩时为 {@code null}
     * @param outcome 本次尝试的结论
     */
    public record CompactAttempt(List<Message> history, Outcome outcome) {

        /** 压缩尝试的三种结论。 */
        public enum Outcome {
            /** 压缩成功，{@code history} 为新历史。 */
            COMPACTED,
            /** 无可压缩内容 —— 正常状态，不计入熔断预算。 */
            NOTHING_TO_COMPACT,
            /** 摘要生成失败 —— 通路异常，计入熔断预算。 */
            FAILED
        }

        static CompactAttempt compacted(List<Message> history) {
            return new CompactAttempt(history, Outcome.COMPACTED);
        }

        static CompactAttempt nothingToCompact() {
            return new CompactAttempt(null, Outcome.NOTHING_TO_COMPACT);
        }

        static CompactAttempt failed() {
            return new CompactAttempt(null, Outcome.FAILED);
        }

        /** 是否成功压缩（此时 {@code history} 非 null）。 */
        public boolean isCompacted() {
            return outcome == Outcome.COMPACTED;
        }

        /** 是否属于应计入熔断预算的失败。 */
        public boolean isFailure() {
            return outcome == Outcome.FAILED;
        }
    }

    /**
     * 执行 Session Memory 压缩。
     * <p>
     * 保留段的起始位置由 {@link #findKeepStart} 按 token 估算并回退到
     * tool_use / tool_result 的配对边界 —— 拆开配对会让下一次请求被服务端拒绝。
     *
     * @param history 当前消息历史（不修改入参）
     * @return 本次尝试的结果，区分「已压缩」/「无可压缩」/「失败」
     */
    public CompactAttempt tryCompact(List<Message> history) {
        // 以下两种情形只是「还没到能压缩的程度」，不是故障
        if (history.size() <= MIN_KEEP_TEXT_MSGS + 2) {
            return CompactAttempt.nothingToCompact();
        }

        Message systemMsg = history.getFirst();
        int lastSummaryIndex = findLastSummaryIndex(history);
        int compressibleStart = lastSummaryIndex + 1;
        int keepStart = findKeepStart(history, compressibleStart);

        if (keepStart - compressibleStart < 4) {
            return CompactAttempt.nothingToCompact();
        }

        // 以下才是真失败：可压缩区间确实存在，但摘要没拿到
        List<Message> toCompress = history.subList(compressibleStart, keepStart);
        String summary;
        try {
            summary = generateSummary(toCompress);
        } catch (Exception e) {
            // error 而非 warn：这是确凿的失败（可压缩区间存在却没拿到摘要），
            // 会让压缩掉到 FullCompact 兜底。生产环境常把日志级别设为 INFO，
            // 记在 warn 里等于排查时看不见根因。
            log.error("Session memory compact failed: summary generation threw {} ({} messages "
                    + "in the compressible range)", e.getClass().getSimpleName(),
                    toCompress.size(), e);
            return CompactAttempt.failed();
        }
        if (summary == null || summary.isBlank()) {
            log.error("Session memory compact failed: model returned a blank summary "
                    + "({} messages in the compressible range)", toCompress.size());
            return CompactAttempt.failed();
        }

        return CompactAttempt.compacted(
                buildCompactedHistory(history, systemMsg, lastSummaryIndex, keepStart, summary));
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

            // 确保不会拆分 tool_use / tool_result 对：当前是 ToolResponseMessage 时
            // 继续往前，把携带 tool_calls 的 AssistantMessage 一并纳入保留段。
            // 拆开配对会让下一次请求被服务端拒绝（400）。
            if (msg instanceof ToolResponseMessage && i > minStart) {
                continue;
            }

            // 保留段一旦同时满足「至少 MIN_KEEP_TEXT_MSGS 条文本消息」与
            // 「至少 MIN_KEEP_TOKENS」，就在此切断。
            //
            // 此前的实现要求 estimatedTokens >= MAX_KEEP_TOKENS(40K) 才返回，
            // 否则一路扫到 minStart 并返回它 —— 于是 keepStart == compressibleStart，
            // 可压缩区间为 0，被上层 `< 4` 的判断挡掉后返回 null，编排层白白升级到
            // 付费的全量压缩。10K–40K 恰是最常见的会话规模：远超微压缩能腾出的空间，
            // 又没到需要抛弃全部上下文的程度，这一段上 Session Memory 层等于失效。
            if (textMsgCount >= MIN_KEEP_TEXT_MSGS && estimatedTokens >= MIN_KEEP_TOKENS) {
                return i;
            }

            // MAX_KEEP_TOKENS 是硬上限：即使文本消息条数还不够，也不能让保留段
            // 继续膨胀，否则压缩后仍然超限。
            if (estimatedTokens >= MAX_KEEP_TOKENS) {
                return i;
            }
        }

        // 扫到头都没满足最小保留条件 —— 消息本就不多，无需压缩。
        // 上层会看到 keepStart == compressibleStart 并返回 null。
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
        String dialogText = RENDERER.render(segment);
        if (dialogText.isEmpty()) return null;

        String promptText = PromptI18n.t(PromptI18n.KEY_SESSION_COMPACT_PROMPT, SUMMARY_PROMPT);
        Prompt prompt = new Prompt(List.of(new UserMessage(promptText + dialogText)));
        // 与 FullCompact 同样的回退：推理模型的产出在 thinking 里而非正文
        return SummaryText.extract(chatModel.call(prompt), "Session memory");
    }
}
