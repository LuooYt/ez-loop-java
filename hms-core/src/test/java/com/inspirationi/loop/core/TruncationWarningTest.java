package com.inspirationi.loop.core;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「达到迭代上限」警告只应出现在真正被截断时。
 * <p>
 * 该警告原先由 {@code iteration >= maxIterations} 反推，而正常结束时 iteration 同样
 * 等于上限 —— 于是模型恰好在末轮给出完整答案也会被贴上截断警告。另一个方向上，末轮
 * 只调工具、未产出文本时 {@code lastAssistantText} 停留在更早轮次的中间输出，警告被
 * 追加其后，读起来像「一段已完成的回答 + 一句提示」。
 */
class TruncationWarningTest {

    /** 警告文本的判别片段（两个警告常量共有）。 */
    private static final String WARN = "已达到最大循环迭代次数";

    private static ToolRegistry noopRegistry() {
        ToolRegistry r = new ToolRegistry();
        r.register(new Tool() {
            @Override public String name() { return "Noop"; }
            @Override public String description() { return "does nothing"; }
            @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public String execute(Map<String, Object> in, ToolContext c) { return "ok"; }
        });
        return r;
    }

    private static AssistantMessage toolCall(String text, int n) {
        return AssistantMessage.builder().content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "c" + n, "function", "Noop", "{}")))
                .build();
    }

    /** 按脚本逐次返回；脚本耗尽后继续返回工具调用，以便测试撞上限的情形。 */
    private static ChatModel scripted(List<AssistantMessage> script) {
        AtomicInteger idx = new AtomicInteger();
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                int i = idx.getAndIncrement();
                AssistantMessage m = i < script.size() ? script.get(i) : toolCall("", i);
                return new ChatResponse(List.of(new Generation(m, ChatGenerationMetadata.NULL)));
            }
            @Override public ChatOptions getOptions() { return null; }
        };
    }

    private static AgentLoop loopWith(ChatModel model, int maxIterations) {
        return new AgentLoop(model, noopRegistry(), ToolContext.defaultContext(),
                "SYSTEM", new TokenTracker(), maxIterations);
    }

    /**
     * 模型恰好在第 maxIterations 轮给出最终答案 —— 属正常结束，不得贴截断警告。
     */
    @Test
    void finalAnswerOnLastIterationIsNotMarkedTruncated() {
        AgentLoop loop = loopWith(scripted(List.of(
                toolCall("step1", 1),
                toolCall("step2", 2),
                new AssistantMessage("完整的最终答案"))), 3);

        String result = loop.run("go");

        assertEquals("完整的最终答案", result,
                "第 maxIterations 轮正常给出的答案不应被追加截断警告");
        assertFalse(result.contains(WARN), "正常结束却出现截断警告");
    }

    /** 提前结束（未用满上限）时同样不该有警告 —— 保护既有行为。 */
    @Test
    void earlyFinishIsNotMarkedTruncated() {
        AgentLoop loop = loopWith(scripted(List.of(
                toolCall("step1", 1),
                new AssistantMessage("早早就答完了"))), 20);

        String result = loop.run("go");

        assertEquals("早早就答完了", result);
        assertFalse(result.contains(WARN), "未撞上限却出现截断警告");
    }

    /** 真正撞上限、且末轮有文本：保留该文本并追加警告。 */
    @Test
    void truncatedWithTextKeepsTextAndAppendsWarning() {
        // 每轮都带文本 + 工具调用，脚本足够长，必然被上限截断
        AgentLoop loop = loopWith(scripted(List.of(
                toolCall("轮1", 1), toolCall("轮2", 2), toolCall("末轮文本", 3))), 3);

        String result = loop.run("go");

        assertTrue(result.contains(WARN), "撞上限应给出警告");
        assertTrue(result.startsWith("末轮文本"),
                "末轮有文本时应保留它并在其后追加警告，实际: " + result);
    }

    /**
     * 撞上限、且末轮只调工具无文本：不得把更早轮次的中间输出当作答案返回。
     */
    @Test
    void truncatedWithoutTextDoesNotReturnStaleText() {
        AgentLoop loop = loopWith(scripted(List.of(
                toolCall("早期中间结论", 1),
                toolCall("", 2),
                toolCall("", 3))), 3);

        String result = loop.run("go");

        assertTrue(result.contains(WARN), "撞上限应给出警告");
        assertFalse(result.contains("早期中间结论"),
                "末轮无文本时不应把更早轮次的中间输出当成最终答案返回，实际: " + result);
    }

    /** 用户取消时只标中断，不叠加截断警告。 */
    @Test
    void cancellationIsNotMarkedTruncated() {
        AtomicInteger calls = new AtomicInteger();
        AgentLoop loop = loopWith(new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        toolCall("working", calls.incrementAndGet()),
                        ChatGenerationMetadata.NULL)));
            }
            @Override public ChatOptions getOptions() { return null; }
        }, 3);

        loop.setOnToolEvent(e -> loop.cancel());
        String result = loop.run("go");

        assertFalse(result.contains(WARN),
                "取消导致的提前结束不应被标为达到迭代上限，实际: " + result);
    }
}
