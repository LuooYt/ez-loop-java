package com.inspirationi.loop.core.compact;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DialogRenderer} 的渲染契约。
 * <p>
 * 摘要提示词是按 {@code [User] / [Assistant] / [Tool Call] / [Tool Result]} 这套
 * 标记编写的，两种压缩层共用同一套格式；差异只在截断策略。这些测试同时锁定
 * 「格式一致」与「截断策略确有区别」两件事。
 */
class DialogRendererTest {

    @Test
    void rendersEachMessageTypeWithItsMarker() {
        List<Message> messages = List.of(
                new UserMessage("帮我查一下"),
                AssistantMessage.builder()
                        .content("好的")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "c1", "function", "WebSearch", "{}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "c1", "WebSearch", "搜索结果")))
                        .build()
        );

        String out = DialogRenderer.forSessionMemory().render(messages);

        assertTrue(out.contains("[User] 帮我查一下"), "用户消息应带 [User] 标记");
        assertTrue(out.contains("[Assistant] 好的"), "助手文本应带 [Assistant] 标记");
        assertTrue(out.contains("[Tool Call] WebSearch"), "工具调用应带 [Tool Call] 标记");
        assertTrue(out.contains("[Tool Result: WebSearch]"), "工具结果应带 [Tool Result] 标记");
    }

    @Test
    void systemMessagesAreExcluded() {
        // 系统提示词在压缩后会原样保留，塞进待摘要文本只会让模型把指令误读为对话
        String out = DialogRenderer.forSessionMemory().render(List.of(
                new SystemMessage("你是一个助手"),
                new UserMessage("你好")
        ));

        assertFalse(out.contains("你是一个助手"), "系统消息不应参与摘要");
        assertTrue(out.contains("[User] 你好"));
    }

    @Test
    void fullCompactDropsToolResultContentButKeepsName() {
        String out = DialogRenderer.forFullCompact().render(List.of(
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "c1", "ReadFile", "文件的全部内容非常长".repeat(50))))
                        .build()
        ));

        assertEquals("[Tool Result: ReadFile]\n", out,
                "全量压缩只保留工具名 —— 此时上下文已接近上限，内容必须整体丢弃");
    }

    @Test
    void sessionMemoryKeepsTruncatedToolResultContent() {
        String data = "x".repeat(500);
        String out = DialogRenderer.forSessionMemory().render(List.of(
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "c1", "ReadFile", data)))
                        .build()
        ));

        assertTrue(out.contains("[Tool Result: ReadFile] " + "x".repeat(200) + "..."),
                "Session Memory 保留前 200 字符并加省略号，以支撑「最近做过什么」类追问");
    }

    @Test
    void assistantTextIsTruncatedAtDifferentLimitsPerLayer() {
        String longText = "文".repeat(1000);
        List<Message> messages = List.of(AssistantMessage.builder().content(longText).build());

        String full = DialogRenderer.forFullCompact().render(messages);
        String session = DialogRenderer.forSessionMemory().render(messages);

        // "[Assistant] " + 600/800 字符 + "..." + "\n"
        assertEquals("[Assistant] " + "文".repeat(600) + "...\n", full,
                "全量压缩截到 600 字符");
        assertEquals("[Assistant] " + "文".repeat(800) + "...\n", session,
                "Session Memory 截到 800 字符");
        assertTrue(full.length() < session.length(), "全量压缩必须比 Session Memory 更激进");
    }

    @Test
    void shortTextIsNotTruncated() {
        String out = DialogRenderer.forFullCompact().render(
                List.of(AssistantMessage.builder().content("简短回答").build()));

        assertEquals("[Assistant] 简短回答\n", out, "未超限的文本不应被加省略号");
    }

    @Test
    void blankAssistantTextIsSkippedButToolCallsSurvive() {
        // 助手消息常见形态：只有工具调用、没有文本
        String out = DialogRenderer.forSessionMemory().render(List.of(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "c1", "function", "Bash", "{}")))
                        .build()
        ));

        assertEquals("[Tool Call] Bash\n", out,
                "空文本不应渲染出空的 [Assistant] 行，但工具调用必须保留");
    }

    @Test
    void emptyInputRendersEmptyString() {
        assertEquals("", DialogRenderer.forFullCompact().render(List.of()),
                "无消息时返回空串 —— 调用方据此判断是否值得发起摘要请求");
    }
}
