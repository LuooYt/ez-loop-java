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
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 每轮请求必须携带配置的模型名。
 * <p>
 * 传给 {@code call()} / {@code stream()} 的 ChatOptions <b>整体覆盖</b> ChatModel
 * 自带的默认选项，而不是与之合并。{@code executeLoop} 原先只设了
 * {@code toolCallbacks}，于是 model 落空，Spring AI 回落到
 * {@code AnthropicChatOptions.DEFAULT_MODEL}（2.0.1 为 {@code claude-haiku-4-5}）。
 * <p>
 * 后果极难定位：yml 里明明配了 {@code us.anthropic.claude-opus-5}，服务端却回
 * 「model=claude-haiku-4-5 不存在」，且整个回落过程没有任何日志。
 */
class ModelNamePropagationTest {

    /** 记录每次调用实际收到的 ChatOptions，供断言模型名是否带上。 */
    private static class OptionsCapturingModel implements ChatModel {
        final List<ChatOptions> received = new ArrayList<>();
        private final ChatOptions configured;

        OptionsCapturingModel(String configuredModel) {
            // 用 ToolCallingChatOptions 而非通用 ChatOptions：真实 provider 的
            // options（AnthropicChatOptions / OpenAiChatOptions）都实现了它，
            // mutate() 返回的 builder 才支持追加 toolCallbacks。用通用类型会让
            // buildRequestOptions 走不到那条分支，测不到真实路径。
            this.configured = configuredModel == null
                    ? null
                    : ToolCallingChatOptions.builder().model(configuredModel).build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            received.add(prompt.getOptions());
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("ok"), ChatGenerationMetadata.NULL)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return configured;
        }
    }

    private static AgentLoop newLoop(ChatModel model) {
        return new AgentLoop(model, new ToolRegistry(), ToolContext.defaultContext(),
                "sys", new TokenTracker());
    }

    /**
     * 阻塞模式下请求必须带上配置的模型名。
     */
    @Test
    void blockingRequestCarriesConfiguredModel() {
        OptionsCapturingModel model = new OptionsCapturingModel("us.anthropic.claude-opus-5");
        newLoop(model).run("hi");

        assertEquals(1, model.received.size(), "应发起一次调用");
        ChatOptions sent = model.received.getFirst();
        assertNotNull(sent, "请求应携带 ChatOptions");
        assertEquals("us.anthropic.claude-opus-5", sent.getModel(),
                "请求必须带上配置的模型名 —— 不带会让 Spring AI 回落到它自己的"
                        + "默认模型（claude-haiku-4-5），使 yml 配置形同虚设");
    }

    /** 流式模式同理 —— 两条路径都要覆盖。 */
    @Test
    void streamingRequestCarriesConfiguredModel() {
        OptionsCapturingModel model = new OptionsCapturingModel("us.anthropic.claude-opus-5");
        newLoop(model).runStreaming("hi", token -> { });

        assertEquals("us.anthropic.claude-opus-5", model.received.getFirst().getModel(),
                "流式请求同样必须带上配置的模型名");
    }

    /**
     * ChatModel 未报告模型名时不得中断请求 —— 只是不带该项，交回 provider 决定。
     * <p>
     * 自定义 ChatModel 未覆写 {@code getOptions()} 时接口默认实现返回空 options，
     * 这种情况必须容忍，否则集成方的简单实现会直接不可用。
     */
    @Test
    void missingModelDoesNotBreakTheRequest() {
        OptionsCapturingModel model = new OptionsCapturingModel(null);
        String result = newLoop(model).run("hi");

        assertEquals(1, model.received.size(), "仍应正常发起调用");
        assertNull(model.received.getFirst().getModel(),
                "解析不到模型名时应留空，由 provider 自行决定，而非塞一个猜测值");
        assertEquals("ok", result, "请求不应因此失败");
    }

    /**
     * <b>核心断言</b>：ChatModel 上的其他配置项不得被工具定义挤掉。
     * <p>
     * 这是本缺陷的本质 —— 原实现新建一个只含 toolCallbacks 的 options，Spring AI
     * 见其类型不匹配便整份换成空对象，于是 model / maxTokens / temperature 全部失效。
     * 只断言 model 不够：那样改成「只补 model」也能过，而 maxTokens 等仍在丢。
     */
    @Test
    void otherConfiguredOptionsSurviveAlongsideTools() {
        ChatOptions rich = ToolCallingChatOptions.builder()
                .model("us.anthropic.claude-opus-5")
                .maxTokens(8096)
                .temperature(0.7)
                .build();
        OptionsCapturingModel model = new OptionsCapturingModel(null) {
            @Override
            public ChatOptions getOptions() {
                return rich;
            }
        };

        new AgentLoop(model, new ToolRegistry(), ToolContext.defaultContext(),
                "sys", new TokenTracker()).run("hi");

        ChatOptions sent = model.received.getFirst();
        assertEquals("us.anthropic.claude-opus-5", sent.getModel(), "模型名必须保留");
        assertEquals(8096, sent.getMaxTokens(), "maxTokens 必须保留 —— 丢了会让长回答被截断");
        assertEquals(0.7, sent.getTemperature(), 0.001, "temperature 必须保留");
    }

    /**
     * 连续多次 run 的每一轮都要带模型名 —— options 在 {@code executeLoop} 里每轮
     * 重建，不能只有首次调用正确。
     */
    @Test
    void everyRunCarriesTheModel() {
        OptionsCapturingModel model = new OptionsCapturingModel("test-model");
        AgentLoop loop = newLoop(model);

        loop.run("first");
        loop.run("second");
        loop.runStreaming("third", token -> { });

        assertEquals(3, model.received.size(), "应发起三次调用");
        for (ChatOptions sent : model.received) {
            assertEquals("test-model", sent.getModel(), "每一轮请求都必须带模型名");
        }
    }
}
