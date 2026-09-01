package com.inspirationi.loop.core;

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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extended thinking 内容与正文的分流。
 * <p>
 * Anthropic 把思考过程作为<b>独立的流式 chunk</b> 发出，只在消息 metadata 上打
 * {@code thinking=TRUE} 标记（见 {@code AnthropicChatModel} 处理
 * {@code delta.isThinking()} 的分支）。因此：
 * <ul>
 *   <li>思考分片<b>不得</b>进入正文缓冲与 onToken 回调，否则用户看到的回复里
 *       会混进思考过程；</li>
 *   <li>思考分片<b>必须</b>送到 onThinkingContent，否则该回调永远静默 ——
 *       早先的实现靠判断 {@code ChatResponseMetadata instanceof Map} 来提取，
 *       而它从不实现 {@code Map}，那段分支恒不成立。</li>
 * </ul>
 */
class AgentLoopThinkingTest {

    /** 按给定分片序列输出的流式 ChatModel。 */
    private static ChatModel streamingModel(List<AssistantMessage> chunks) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                // 降级路径：把最后一个分片当作完整响应
                AssistantMessage last = chunks.get(chunks.size() - 1);
                return new ChatResponse(List.of(new Generation(last, ChatGenerationMetadata.NULL)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.fromIterable(chunks)
                        .map(msg -> new ChatResponse(
                                List.of(new Generation(msg, ChatGenerationMetadata.NULL))));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ChatOptions.builder().model("stub").build();
            }
        };
    }

    /** 构造一个思考分片 —— 带 thinking 标记的消息。 */
    private static AssistantMessage thinkingChunk(String text) {
        return AssistantMessage.builder()
                .content(text)
                .properties(Map.of("thinking", Boolean.TRUE))
                .build();
    }

    private static AgentLoop newLoop(ChatModel model) {
        return new AgentLoop(model, new ToolRegistry(), ToolContext.defaultContext(), "sys");
    }

    @Test
    void thinkingChunksDoNotLeakIntoAssistantText() {
        // 思考分片夹在正文分片之间 —— 最容易暴露「混流」的排列
        AgentLoop loop = newLoop(streamingModel(List.of(
                thinkingChunk("我需要先算一下 2+2"),
                new AssistantMessage("答案"),
                thinkingChunk("再确认一遍"),
                new AssistantMessage("是 4")
        )));

        List<String> tokens = new ArrayList<>();
        String result = loop.runStreaming("2+2 等于几", tokens::add);

        assertEquals("答案是 4", result,
                "正文分片应连续累积，思考分片不得插入其中");
        assertEquals(List.of("答案", "是 4"), tokens,
                "onToken 只应收到正文分片");
    }

    @Test
    void thinkingChunksAreDeliveredToThinkingCallback() {
        AgentLoop loop = newLoop(streamingModel(List.of(
                thinkingChunk("第一段思考"),
                thinkingChunk("第二段思考"),
                new AssistantMessage("结论")
        )));

        List<String> thinking = new ArrayList<>();
        var callbacks = new AgentLoop.RequestCallbacks(null, thinking::add, null, null);
        String result = loop.runStreaming("问题", t -> { }, callbacks);

        assertEquals("结论", result);
        assertEquals(List.of("第一段思考", "第二段思考"), thinking,
                "所有思考分片都应按序送到 onThinkingContent");
    }

    @Test
    void thinkingCallbackFallsBackToPersistentSetter() {
        AgentLoop loop = newLoop(streamingModel(List.of(
                thinkingChunk("思考中"),
                new AssistantMessage("好了")
        )));

        List<String> thinking = new ArrayList<>();
        loop.setOnThinkingContent(thinking::add);
        // 不传 RequestCallbacks —— 应回退到持久回调
        loop.runStreaming("问题", t -> { });

        assertEquals(List.of("思考中"), thinking,
                "未提供请求级回调时应回退到 setOnThinkingContent 注册的回调");
    }

    @Test
    void assistantHistoryExcludesThinkingContent() {
        AgentLoop loop = newLoop(streamingModel(List.of(
                thinkingChunk("内部推理不该入库"),
                new AssistantMessage("对外回答")
        )));

        loop.runStreaming("问题", t -> { });

        String assistantTexts = loop.copyMessageHistory().stream()
                .filter(m -> m instanceof AssistantMessage)
                .map(m -> m.getText() == null ? "" : m.getText())
                .reduce("", String::concat);

        assertFalse(assistantTexts.contains("内部推理"),
                "思考内容不得写入消息历史 —— 它会随下一轮请求一起回传并消耗上下文");
        assertTrue(assistantTexts.contains("对外回答"));
    }

    @Test
    void streamWithoutThinkingStillWorks() {
        // 未开启 extended thinking 时（绝大多数情况）行为不应有任何变化
        AgentLoop loop = newLoop(streamingModel(List.of(
                new AssistantMessage("你"),
                new AssistantMessage("好")
        )));

        List<String> tokens = new ArrayList<>();
        String result = loop.runStreaming("hi", tokens::add);

        assertEquals("你好", result, "流式分片应累积为完整回复");
        assertEquals(List.of("你", "好"), tokens, "普通文本分片应全部经 onToken 输出");
    }
}
