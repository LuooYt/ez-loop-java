package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.tool.ToolContext;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskUserQuestionTool} 的回调回退链。
 * <p>
 * 该工具设计了两级回调：结构化回调（{@code ASK_USER_STRUCTURED_CALLBACK}）优先，
 * 失败时回退到简单文本回调（{@code USER_INPUT_CALLBACK}）。
 * {@code DefaultHmsSessionManager.resolveAskUser} 的契约是「无人应答时返回 null，
 * 回退到 ToolContext 链」—— 因此结构化回调返回 null 必须触发回退，而非直接终止。
 */
class AskUserFallbackTest {

    private final AskUserQuestionTool tool = new AskUserQuestionTool();

    private String ask(ToolContext context) {
        return tool.execute(Map.of("question", "继续吗？"), context);
    }

    @Test
    void structuredCallbackAnswerIsUsedDirectly() {
        ToolContext context = ToolContext.defaultContext();
        context.set(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                (BiFunction<String, List<String>, String>) (q, o) -> "结构化答案");

        assertTrue(ask(context).contains("结构化答案"));
    }

    @Test
    void nullFromStructuredCallbackFallsBackToTextCallback() {
        ToolContext context = ToolContext.defaultContext();
        AtomicBoolean textCallbackUsed = new AtomicBoolean(false);

        // 结构化回调「弃权」（返回 null）—— 这正是 resolveAskUser 超时后的行为
        context.set(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                (BiFunction<String, List<String>, String>) (q, o) -> null);
        context.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                (Function<String, String>) prompt -> {
                    textCallbackUsed.set(true);
                    return "文本回调答案";
                });

        String result = ask(context);

        assertTrue(textCallbackUsed.get(),
                "结构化回调返回 null 时应回退到文本回调，实际未调用。"
                        + "resolveAskUser 的契约是返回 null 表示弃权并回退。");
        assertTrue(result.contains("文本回调答案"),
                "应采用回退回调的答案，实际：" + result);
    }

    @Test
    void blankFromStructuredCallbackAlsoFallsBack() {
        ToolContext context = ToolContext.defaultContext();
        AtomicBoolean textCallbackUsed = new AtomicBoolean(false);

        context.set(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                (BiFunction<String, List<String>, String>) (q, o) -> "   ");
        context.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                (Function<String, String>) prompt -> {
                    textCallbackUsed.set(true);
                    return "文本回调答案";
                });

        ask(context);
        assertTrue(textCallbackUsed.get(), "空白回答也应视为弃权并回退");
    }

    @Test
    void exceptionFromStructuredCallbackFallsBack() {
        ToolContext context = ToolContext.defaultContext();
        AtomicBoolean textCallbackUsed = new AtomicBoolean(false);

        context.set(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                (BiFunction<String, List<String>, String>) (q, o) -> {
                    throw new IllegalStateException("callback exploded");
                });
        context.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                (Function<String, String>) prompt -> {
                    textCallbackUsed.set(true);
                    return "文本回调答案";
                });

        ask(context);
        assertTrue(textCallbackUsed.get(), "结构化回调抛异常时应回退");
    }

    @Test
    void noResponseWhenBothCallbacksDecline() {
        ToolContext context = ToolContext.defaultContext();
        context.set(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                (BiFunction<String, List<String>, String>) (q, o) -> null);
        context.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                (Function<String, String>) prompt -> null);

        // 两级都弃权 → 明确告知模型没有回答，而不是抛异常或空串
        String result = ask(context);
        assertTrue(result.contains("no response") || result.contains("not available"),
                "两级回调都弃权时应返回明确的无回答说明，实际：" + result);
    }

    @Test
    void missingStructuredCallbackUsesTextCallback() {
        ToolContext context = ToolContext.defaultContext();
        context.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                (Function<String, String>) prompt -> "仅文本回调");

        assertTrue(ask(context).contains("仅文本回调"));
    }

    @Test
    void noCallbackAtAllReportsUnavailable() {
        String result = ask(ToolContext.defaultContext());
        assertTrue(result.startsWith("Error:"),
                "无任何回调时应返回错误说明，实际：" + result);
        assertFalse(result.contains("User response:"),
                "不得伪造用户回答");
    }
}
