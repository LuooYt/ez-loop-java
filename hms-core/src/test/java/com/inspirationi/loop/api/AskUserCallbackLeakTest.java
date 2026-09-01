package com.inspirationi.loop.api;

import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.AskUserQuestionTool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 请求级 AskUser 回调不得残留到下一次调用。
 * <p>
 * {@code send(sessionId, msg, callbacks)} 把两个回调键写进 {@code ToolContext}，
 * 而该上下文是<b>会话级</b>的、跨请求存活。若请求结束后不清除，闭包里捕获的
 * {@code HmsCallbacks} 就会一直可达：后续 {@code send(sessionId, msg)}（无回调的
 * 两参版本）里的 AskUser 会打给<b>上一个请求</b>的回调。SSE 场景下那个接收端
 * 早已 complete，提问既送不出去也收不到回答，只能空等到超时才回退。
 */
class AskUserCallbackLeakTest {

    /** 第一轮返回一次 AskUserQuestion 工具调用，之后只回文本，避免无限循环。 */
    private static ChatModel askOnceThenStop(AtomicInteger callCount) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int n = callCount.incrementAndGet();
                AssistantMessage msg = (n == 1)
                        ? AssistantMessage.builder()
                                .content("")
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "call-1", "function", "AskUserQuestion",
                                        "{\"question\":\"pick one\"}")))
                                .build()
                        : new AssistantMessage("done");
                return new ChatResponse(List.of(new Generation(msg, ChatGenerationMetadata.NULL)));
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

    private static DefaultHmsSessionManager newManager(ChatModel model) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new AskUserQuestionTool());
        return DefaultHmsSessionManager.builder(
                        model, registry, new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                // 回调残留时提问会空等到这个超时才回退；设短一点让测试快速收敛。
                .userResponseTimeoutSeconds(1)
                .maxSessions(10)
                .build();
    }

    /**
     * 带回调的请求结束后，两个回调键都不应留在会话上下文里。
     */
    @Test
    void callbackKeysAreClearedAfterRequest() {
        AtomicInteger calls = new AtomicInteger();
        try (DefaultHmsSessionManager manager = newManager(askOnceThenStop(calls))) {
            String sessionId = manager.createSession("s");

            manager.send(sessionId, "hi", new HmsCallbacks() {
                @Override
                public String onAskUser(String question, List<String> options) {
                    return "first-caller";
                }
            });

            ToolContext ctx = manager.getSessionInternal(sessionId)
                    .getAgentLoop().getToolContext();

            assertNull(ctx.get(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK),
                    "结构化 AskUser 回调必须在请求结束后清除，否则会泄漏到下一次调用");
            assertNull(ctx.get(AskUserQuestionTool.USER_INPUT_CALLBACK),
                    "文本 AskUser 回调必须在请求结束后清除，否则会泄漏到下一次调用");
        }
    }

    /**
     * 后续不带回调的 send 不得把提问打给上一个请求的回调。
     * <p>
     * 这是泄漏的实际后果：第一个调用方已经返回、其接收端可能已关闭，却仍在
     * 应答第二次调用的提问。
     */
    @Test
    void laterCallWithoutCallbacksDoesNotReachThePreviousCaller() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger firstCallerAsked = new AtomicInteger();

        try (DefaultHmsSessionManager manager = newManager(askOnceThenStop(calls))) {
            String sessionId = manager.createSession("s");

            manager.send(sessionId, "round one", new HmsCallbacks() {
                @Override
                public String onAskUser(String question, List<String> options) {
                    firstCallerAsked.incrementAndGet();
                    return "first-caller";
                }
            });
            assertEquals(1, firstCallerAsked.get(), "第一轮本就该问到第一个调用方");

            // 第二轮：同一会话（回调就残留在它的上下文里），但这次不提供任何回调
            calls.set(0);
            manager.send(sessionId, "round two");

            assertEquals(1, firstCallerAsked.get(),
                    "第二次调用未提供回调，提问不得再打给上一个请求的回调 —— "
                            + "实际被追加询问了 " + (firstCallerAsked.get() - 1) + " 次");
        }
    }
}
