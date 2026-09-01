package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.core.compact.CompactionResult.CompactLayer;
import com.inspirationi.loop.i18n.PromptI18n;
import org.springframework.ai.chat.messages.*;

import java.time.Instant;
import java.util.IllegalFormatException;
import java.util.List;

/**
 * 微压缩 —— 在每次 API 调用后执行，裁剪旧的 tool_result 内容。
 * <p>
 *  的 microCompact。不需要额外 API 调用，纯本地操作。
 * 策略：
 * <ul>
 *   <li>保留最近 N 轮的 tool 结果，更早的只保留摘要行</li>
 *   <li>时间感知：空闲超过 gapThresholdMinutes 后主动清理</li>
 * </ul>
 */
public class MicroCompact {

    /** 保留最近 N 条 ToolResponseMessage 的完整内容 */
    private static final int KEEP_RECENT_TOOL_RESULTS = 6;

    /** 截断阈值：超过此长度的旧 tool result 才会被截断 */
    private static final int TRUNCATE_THRESHOLD = 200;

    /** 截断后的占位文本 —— 中文默认文本（含 %d 占位符），经 {@link PromptI18n} 按系统语言取用 */
    public static final String TRUNCATED_MARKER = "[工具结果已截断 — 省略 %d 个字符]";

    /** 时间感知：空闲超过此分钟数后减少保留数量 */
    private static final int GAP_THRESHOLD_MINUTES = 10;

    /** 空闲时保留的 tool result 数量（更激进的清理） */
    private static final int KEEP_RECENT_AFTER_GAP = 2;

    /** 上次活跃时间 */
    private Instant lastActivityTime = Instant.now();

    /** 更新活跃时间（每次 API 调用后调用） */
    public void recordActivity() {
        lastActivityTime = Instant.now();
    }

    /**
     * 对消息历史执行微压缩。
     * 使用 {@link List#set(int, Object)} 原地替换需截断的消息。
     *
     * @param history 消息列表（直接修改，需支持 set 操作）
     * @return 压缩结果
     */
    public CompactionResult compact(List<Message> history) {
        int totalToolResponses = 0;
        int truncated = 0;

        // 时间感知：空闲超时后使用更激进的保留策略
        long minutesSinceLastActivity = java.time.Duration.between(lastActivityTime, Instant.now()).toMinutes();
        int keepRecent = minutesSinceLastActivity >= GAP_THRESHOLD_MINUTES
                ? KEEP_RECENT_AFTER_GAP
                : KEEP_RECENT_TOOL_RESULTS;

        // 倒序扫描，找到所有 ToolResponseMessage 的位置
        int recentCount = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof ToolResponseMessage) {
                totalToolResponses++;
                recentCount++;
                if (recentCount > keepRecent) {
                    // 需要截断
                    ToolResponseMessage trm = (ToolResponseMessage) history.get(i);
                    if (shouldTruncate(trm)) {
                        history.set(i, truncateToolResponse(trm));
                        truncated++;
                    }
                }
            }
        }

        if (truncated == 0) {
            return CompactionResult.noAction(CompactLayer.MICRO, "No tool results to truncate");
        }

        return CompactionResult.success(CompactLayer.MICRO, totalToolResponses,
                totalToolResponses - truncated, null);
    }

    /** 判断 ToolResponseMessage 是否需要截断 */
    private boolean shouldTruncate(ToolResponseMessage trm) {
        var responses = trm.getResponses();
        if (responses == null || responses.isEmpty()) return false;
        for (var resp : responses) {
            if (resp.responseData() != null && resp.responseData().toString().length() > TRUNCATE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** 创建截断后的 ToolResponseMessage */
    private ToolResponseMessage truncateToolResponse(ToolResponseMessage original) {
        var responses = original.getResponses();
        if (responses == null || responses.isEmpty()) return original;

        var truncatedResponses = responses.stream().map(resp -> {
            String data = resp.responseData() != null ? resp.responseData().toString() : "";
            if (data.length() > TRUNCATE_THRESHOLD) {
                String marker = truncatedMarker(data.length());
                return new ToolResponseMessage.ToolResponse(resp.id(), resp.name(), marker);
            }
            return resp;
        }).toList();

        return ToolResponseMessage.builder()
                .responses(truncatedResponses)
                .build();
    }

    /** 取当前语言下的截断占位文本（翻译结果丢失 %d 占位符时回退英文原文）。 */
    private static String truncatedMarker(int omittedChars) {
        String template = PromptI18n.t(PromptI18n.KEY_MICRO_COMPACT_TRUNCATE_MARKER, TRUNCATED_MARKER);
        try {
            return String.format(template, omittedChars);
        } catch (IllegalFormatException e) {
            // 大模型翻译可能改写掉 %d（如替换为具体数字），回退英文原文避免格式异常
            return String.format(TRUNCATED_MARKER, omittedChars);
        }
    }
}
