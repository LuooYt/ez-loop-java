package com.inspirationi.loop.core.compact;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MicroCompact} 的时间感知策略。
 * <p>
 * 「空闲超过 10 分钟后保留更少的 tool_result」这条分支过去永远走不到：
 * 活跃时间是在每次压缩检查里记的，而检查由模型响应驱动 —— 记录发生在判断之前，
 * 间隔永远是 0。用例把活跃时间手工拨回过去，直接验证这条分支。
 */
class MicroCompactTest {

    /** 与 MicroCompact 内部一致：正常保留 6 条，空闲时保留 2 条。 */
    private static final int KEEP_NORMAL = 6;
    private static final int KEEP_AFTER_GAP = 2;

    private static List<Message> historyWithBulkyToolResults(int count) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        String bulky = "x".repeat(5_000);
        for (int i = 0; i < count; i++) {
            history.add(new UserMessage("user " + i));
            history.add(new AssistantMessage("assistant " + i));
            history.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call-" + i, "SomeTool", bulky)))
                    .build());
        }
        return history;
    }

    /** 仍保留原文的 tool_result 数量。 */
    private static int bulkyRemaining(List<Message> history) {
        return (int) history.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .filter(m -> m.getResponses().get(0).responseData().toString().length() > 1000)
                .count();
    }

    /** 把「上次活跃时间」拨回指定分钟数之前（该字段是私有的，测试直接改值）。 */
    private static void makeIdleFor(MicroCompact microCompact, int minutes) throws Exception {
        Field field = MicroCompact.class.getDeclaredField("lastActivityTime");
        field.setAccessible(true);
        field.set(microCompact, Instant.now().minus(Duration.ofMinutes(minutes)));
    }

    @Test
    void keepsMoreResultsWhenRequestsArriveBackToBack() {
        MicroCompact microCompact = new MicroCompact();
        List<Message> history = historyWithBulkyToolResults(8);

        CompactionResult result = microCompact.compact(history);

        assertTrue(result.success());
        assertEquals(KEEP_NORMAL, bulkyRemaining(history),
                "活跃会话应保留最近 " + KEEP_NORMAL + " 条完整 tool_result");
    }

    @Test
    void keepsFewerResultsAfterALongIdleGap() throws Exception {
        MicroCompact microCompact = new MicroCompact();
        makeIdleFor(microCompact, 30);
        List<Message> history = historyWithBulkyToolResults(8);

        microCompact.compact(history);

        assertEquals(KEEP_AFTER_GAP, bulkyRemaining(history),
                "空闲 30 分钟后应只保留最近 " + KEEP_AFTER_GAP + " 条完整 tool_result");
    }

    /** {@link MicroCompact#recordActivity()} 会把策略切回「活跃」。 */
    @Test
    void recordingActivityResetsTheIdleStrategy() throws Exception {
        MicroCompact microCompact = new MicroCompact();
        makeIdleFor(microCompact, 30);
        microCompact.recordActivity();

        List<Message> history = historyWithBulkyToolResults(8);
        microCompact.compact(history);

        assertEquals(KEEP_NORMAL, bulkyRemaining(history));
    }

    /** 未超阈值的 tool_result 不受影响，也不该被计为发生了压缩。 */
    @Test
    void smallToolResultsAreNotTouched() {
        MicroCompact microCompact = new MicroCompact();
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        for (int i = 0; i < 10; i++) {
            history.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call-" + i, "SomeTool", "short")))
                    .build());
        }

        CompactionResult result = microCompact.compact(history);

        assertFalse(result.success(), "没有可截断的内容时不应报告成功");
    }
}
