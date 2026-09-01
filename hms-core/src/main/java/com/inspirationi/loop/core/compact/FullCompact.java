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
 * 全量压缩 —— AI 摘要全部对话历史，带 PTL（Prompt Too Long）重试。
 * <p>
 * 对应 的 fullCompact。当 SessionMemoryCompact 无法有效压缩时作为兜底。
 * PTL 重试策略：按 API Round（user→assistant→tool_result 为一组）逐步丢弃最旧的组。
 */
public class FullCompact {

    private static final Logger log = LoggerFactory.getLogger(FullCompact.class);

    /** PTL 重试最大次数 */
    private static final int MAX_PTL_RETRIES = 5;

    /** 保留最近 N 条消息（不压缩） */
    private static final int KEEP_RECENT_MESSAGES = 2;

    /** 全量压缩摘要提示词 —— 中文默认文本，经 {@link PromptI18n} 按系统语言取用 */
    public static final String FULL_COMPACT_PROMPT = """
            请将以下对话历史压缩为一份详尽的摘要。要求：
            1. 保留所有关键决策、代码变更与技术细节
            2. 保留文件路径、函数名、类名与具体代码标识符
            3. 保留用户的偏好、需求与约束
            4. 记录当前工作状态：已完成事项、剩余事项与阻塞项
            5. 记录遇到的错误及其解决方案
            6. 保留项目结构与架构的重要背景
            7. 输出控制在 1000 字以内，使用结构化要点
            
            对话历史：
            """;

    /** 语言模型客户端 —— 用于生成全量摘要 */
    private final ChatModel chatModel;

    /**
     * 构造全量压缩器。
     *
     * @param chatModel 用于生成摘要的语言模型
     */
    public FullCompact(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 执行全量压缩。
     *
     * @param history 当前消息历史
     * @return 压缩后的新历史；如果失败返回 null
     */
    public List<Message> compact(List<Message> history) {
        if (history.size() <= KEEP_RECENT_MESSAGES + 2) {
            return null;
        }

        int before = history.size();
        Message systemMsg = history.getFirst();

        // 按 API Round 分组
        List<ApiRound> rounds = groupByRounds(history);

        // PTL 重试循环：逐步丢弃最旧的 round
        int dropCount = 0;
        while (dropCount < rounds.size() - 1 && dropCount < MAX_PTL_RETRIES) {
            List<ApiRound> remaining = rounds.subList(dropCount, rounds.size());

            try {
                String summary = generateFullSummary(remaining);
                if (summary != null && !summary.isBlank()) {
                    // 构建新历史
                    List<Message> newHistory = new ArrayList<>();
                    newHistory.add(systemMsg);
                    newHistory.add(new SystemMessage("[Conversation Summary]\n" + summary));

                    // 保留最后几条消息
                    for (int i = Math.max(1, before - KEEP_RECENT_MESSAGES); i < before; i++) {
                        newHistory.add(history.get(i));
                    }

                    log.info("Full compact succeeded: {} → {} messages (dropped {} rounds)",
                            before, newHistory.size(), dropCount);
                    return newHistory;
                }
            } catch (Exception e) {
                log.warn("Full compact attempt failed (drop={}): {}", dropCount, e.getMessage());
                // 尝试解析 PTL gap 以计算需要丢弃的 round 数
                int gapDrop = parsePtlGap(e, remaining);
                if (gapDrop > 1) {
                    dropCount += gapDrop;
                    log.info("PTL gap parsed: dropping {} additional rounds", gapDrop);
                    continue;
                }
            }

            dropCount++;
        }

        log.error("Full compact failed after {} PTL retries", dropCount);
        return null;
    }

    // ── 内部方法 ──

    /** 按 API Round 分组：一个 round = [UserMessage] + [AssistantMessage + ToolResponseMessages...] */
    private List<ApiRound> groupByRounds(List<Message> history) {
        List<ApiRound> rounds = new ArrayList<>();
        List<Message> currentRound = new ArrayList<>();

        for (int i = 1; i < history.size(); i++) { // 跳过系统消息
            Message msg = history.get(i);
            if (msg instanceof UserMessage && !currentRound.isEmpty()) {
                rounds.add(new ApiRound(List.copyOf(currentRound)));
                currentRound.clear();
            }
            currentRound.add(msg);
        }

        if (!currentRound.isEmpty()) {
            rounds.add(new ApiRound(List.copyOf(currentRound)));
        }

        return rounds;
    }

    /** 生成全量摘要 */
    private String generateFullSummary(List<ApiRound> rounds) {
        StringBuilder dialogText = new StringBuilder();

        for (ApiRound round : rounds) {
            for (Message msg : round.messages()) {
                switch (msg) {
                    case UserMessage um -> dialogText.append("[User] ").append(um.getText()).append("\n");
                    case AssistantMessage am -> {
                        if (am.getText() != null && !am.getText().isBlank()) {
                            String text = am.getText();
                            if (text.length() > 600) text = text.substring(0, 600) + "...";
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
                            dialogText.append("[Tool Result: ").append(resp.name()).append("]\n");
                        }
                    }
                    default -> {}
                }
            }
            dialogText.append("---\n");
        }

        if (dialogText.isEmpty()) return null;

        String promptText = PromptI18n.t(PromptI18n.KEY_FULL_COMPACT_PROMPT, FULL_COMPACT_PROMPT);
        Prompt prompt = new Prompt(List.of(new UserMessage(promptText + dialogText)));
        try {
            ChatResponse response = chatModel.call(prompt);
            if (response != null && response.getResult() != null
                    && response.getResult().getOutput() != null) {
                return response.getResult().getOutput().getText();
            }
            return null;
        } catch (Exception e) {
            log.warn("Full compact summary generation failed: {}", e.getMessage());
            return null;
        }
    }

    /** API Round：一个用户请求 + AI 响应 + 工具调用的完整回合 */
    private record ApiRound(List<Message> messages) {}

    /**
     * 尝试从 PTL 错误中解析 token gap，计算需要丢弃的 round 数。
     * API 错误消息格式类似: "prompt is too long: 250000 tokens > 200000 token limit"
     * 返回建议丢弃的 round 数，如果无法解析返回 0。
     */
    private int parsePtlGap(Exception e, List<ApiRound> rounds) {
        String msg = e.getMessage();
        if (msg == null) return 0;

        // 尝试从错误消息中提取 token 数字
        // 格式: "NNN tokens > NNN token limit" 或类似变体
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*tokens?\\s*>\\s*(\\d+)")
                .matcher(msg);
        if (!m.find()) return 0;

        try {
            long actual = Long.parseLong(m.group(1));
            long limit = Long.parseLong(m.group(2));
            long gap = actual - limit;
            if (gap <= 0) return 0;

            // 估算每个 round 的 token 数（粗略平均）
            long avgTokensPerRound = actual / Math.max(rounds.size(), 1);
            if (avgTokensPerRound <= 0) return 0;

            int roundsToDrop = (int) Math.ceil((double) gap / avgTokensPerRound);
            // 保守：丢弃 ~20% 的 round（与 TS 一致的回退策略）
            int fallbackDrop = Math.max(1, (int) Math.floor(rounds.size() * 0.2));
            return Math.min(roundsToDrop, fallbackDrop);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
