package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.core.TokenTracker;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MICRO 压缩事件的节流语义。
 * <p>
 * 该事件每个会话只提示一次（否则 UI 会每轮弹一次「上下文已压缩」）。但「提示过」
 * 的标记必须只在真正送达之后才置位 —— 否则在还没有回调的那一轮先记下标记，
 * 之后注册的回调就永远收不到这条事件。
 */
class MicroEventThrottleTest {

    private static ChatModel unusedModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("微压缩路径不应调用模型");
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    /** 构造一段含 10 条超长 tool 结果的历史：微压缩保留 6 条，必有可裁剪项。 */
    private static List<Message> bulkyHistory() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        String bulky = "x".repeat(3_000);
        for (int i = 0; i < 10; i++) {
            history.add(new UserMessage("user " + i));
            history.add(new AssistantMessage("assistant " + i));
            history.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call-" + i, "SomeTool", bulky)))
                    .build());
        }
        return history;
    }

    private static AutoCompactManager lowUsageManager() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage(1_000, 10);   // 远低于自动压缩阈值
        return new AutoCompactManager(unusedModel(), tracker);
    }

    private static long microEvents(List<CompactionResult> results) {
        return results.stream()
                .filter(r -> r.success() && r.layer() == CompactionResult.CompactLayer.MICRO)
                .count();
    }

    @Test
    void microEventSurvivesARoundWithNoCallbackRegistered() {
        AutoCompactManager manager = lowUsageManager();

        // 第一轮：还没有回调（例如会话尚未把请求级回调接上来）
        manager.autoCompactIfNeeded(MicroEventThrottleTest::bulkyHistory, h -> {});

        // 第二轮：回调就位 —— 这一次的 MICRO 必须送达
        List<CompactionResult> received = new ArrayList<>();
        manager.setOnCompactionEvent(received::add);
        manager.autoCompactIfNeeded(MicroEventThrottleTest::bulkyHistory, h -> {});

        assertEquals(1, microEvents(received),
                "无人观测的那一轮不得消耗唯一的提示额度，实际收到：" + received);
    }

    /** 送达之后仍然只提示一次 —— 节流本身不能失效。 */
    @Test
    void microEventIsStillNotifiedOnlyOnce() {
        AutoCompactManager manager = lowUsageManager();
        List<CompactionResult> received = new ArrayList<>();
        manager.setOnCompactionEvent(received::add);

        manager.autoCompactIfNeeded(MicroEventThrottleTest::bulkyHistory, h -> {});
        manager.autoCompactIfNeeded(MicroEventThrottleTest::bulkyHistory, h -> {});

        assertEquals(1, microEvents(received), "MICRO 事件应节流为一次：" + received);
    }

    /** 未注册回调时压缩照常执行，只是没人收到事件。 */
    @Test
    void compactionStillRunsWithoutCallback() {
        AutoCompactManager manager = lowUsageManager();

        CompactionResult result = manager.autoCompactIfNeeded(
                MicroEventThrottleTest::bulkyHistory, h -> {});

        assertTrue(result != null && result.success(), "微压缩本身不应依赖回调");
    }
}
