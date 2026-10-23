package com.inspirationi.loop.core.compact;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩不得切裂 {@code tool_use} / {@code tool_result} 配对。
 * <p>
 * 压缩触发点在「助手刚产出文本」之后，此时历史尾部通常是
 * {@code [Assistant(toolCalls), ToolResponse, Assistant(text)]}。
 * 全量压缩无条件保留最后 2 条，正好把携带 tool_calls 的助手消息切进摘要区，
 * 留下一条孤立的 tool_result —— 下一次请求被上游 400 拒绝，会话就此卡死。
 */
class FullCompactPairingTest {

    /** 返回固定摘要的模型桩 —— 压缩只关心历史形状，不关心摘要内容。 */
    private static ChatModel summaryModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("SUMMARY"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    private static AssistantMessage toolCall(String text, String id, String name) {
        return AssistantMessage.builder()
                .content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}")))
                .build();
    }

    private static ToolResponseMessage toolResult(String id, String name) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, "ok")))
                .build();
    }

    /** 尾部形状正是线上会触发 400 的那一种。 */
    private static List<Message> historyEndingWithToolRound() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        history.add(new UserMessage("第一轮提问"));
        history.add(new AssistantMessage("第一轮回答"));
        history.add(new UserMessage("帮我读文件"));
        history.add(toolCall(null, "t1", "Read"));
        history.add(toolResult("t1", "Read"));
        history.add(new AssistantMessage("文件内容如下……"));
        return history;
    }

    @Test
    void tailStartingWithToolResponseMovesBoundaryBack() {
        List<Message> history = historyEndingWithToolRound();

        List<Message> compacted = new FullCompact(summaryModel()).compact(history);

        assertNotNull(compacted, "历史足够长时压缩应当成功");
        assertTrue(HistoryInvariants.violations(compacted).isEmpty(),
                "压缩后出现孤立 tool 响应：" + HistoryInvariants.violations(compacted));

        boolean keptCall = compacted.stream()
                .anyMatch(m -> m instanceof AssistantMessage am && am.hasToolCalls());
        boolean keptResult = compacted.stream()
                .anyMatch(m -> m instanceof ToolResponseMessage);
        assertTrue(keptCall && keptResult,
                "保留段必须同时包含 tool_calls 与 tool_result，实际：" + compacted);
    }

    @Test
    void parallelToolCallsKeepAssistantAndResponsesTogether() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        history.add(new UserMessage("第一轮"));
        history.add(new AssistantMessage("第一轮回答"));
        history.add(new UserMessage("并行读两个文件"));
        history.add(AssistantMessage.builder()
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("a1", "function", "Read", "{}"),
                        new AssistantMessage.ToolCall("a2", "function", "Read", "{}")))
                .build());
        history.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("a1", "Read", "one"),
                new ToolResponseMessage.ToolResponse("a2", "Read", "two"))).build());
        history.add(new AssistantMessage("两个文件都读完了"));

        List<Message> compacted = new FullCompact(summaryModel()).compact(history);

        assertNotNull(compacted);
        assertTrue(HistoryInvariants.violations(compacted).isEmpty(),
                "并行工具调用被切裂：" + HistoryInvariants.violations(compacted));
    }

    @Test
    void inFlightFinalToolCallIsAllowed() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        history.add(new UserMessage("读文件"));
        history.add(toolCall(null, "t1", "Read"));

        assertTrue(HistoryInvariants.violations(history).isEmpty(),
                "最后一条助手消息的工具调用尚未执行，压缩时合法（在途）");
    }

    @Test
    void orphanToolResponseIsRejected() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        history.add(new AssistantMessage("我读一下"));
        history.add(toolResult("t1", "Read"));

        List<String> problems = HistoryInvariants.violations(history);
        assertTrue(problems.stream().anyMatch(p -> p.contains("孤儿")),
                "孤立工具响应必须被识别，实际：" + problems);
    }

    @Test
    void missingResponsesBeforeNextMessageAreRejected() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        history.add(AssistantMessage.builder()
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("a1", "function", "Read", "{}"),
                        new AssistantMessage.ToolCall("a2", "function", "Read", "{}")))
                .build());
        history.add(toolResult("a1", "Read"));
        history.add(new AssistantMessage("只读了一个"));

        List<String> problems = HistoryInvariants.violations(history);
        assertTrue(problems.stream().anyMatch(p -> p.contains("未收到响应")),
                "遗漏的工具响应必须被识别，实际：" + problems);
    }

    @Test
    void emptyAssistantMessageIsRejected() {
        List<Message> history = List.of(new SystemMessage("system"), new AssistantMessage(""));

        assertTrue(HistoryInvariants.violations(history).stream()
                        .anyMatch(p -> p.contains("空助手消息")),
                "空助手消息应被视为违规");
    }

    @Test
    void alignKeepStartNeverLandsOnToolResponse() {
        List<Message> history = historyEndingWithToolRound();

        for (int i = 0; i < history.size(); i++) {
            int aligned = HistoryInvariants.alignKeepStart(history, i);
            assertTrue(aligned >= 1, "保留段不能包含系统提示：" + aligned);
            assertTrue(!(history.get(aligned) instanceof ToolResponseMessage),
                    "起点 " + i + " 对齐到 " + aligned + " 仍是工具响应");
        }
    }

    @Test
    void verifyThrowsOnInvalidHistory() {
        List<Message> history = List.of(new SystemMessage("system"), toolResult("t1", "Read"));

        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> HistoryInvariants.verify(history));
        assertTrue(e.getMessage().contains("孤儿"),
                "异常信息应指出问题所在，实际：" + e.getMessage());
    }

    @Test
    void historyWithoutSystemMessageIsNotRejected() {
        // 压缩层只守配对，不修正输入形状：没有系统提示的历史同样应当能通过校验
        List<Message> history = List.of(new UserMessage("直接开始"), new AssistantMessage("好的"));

        assertTrue(HistoryInvariants.violations(history).isEmpty(),
                "缺少系统提示不应被当作压缩错误：" + HistoryInvariants.violations(history));
    }
}
