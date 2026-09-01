package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HmsResponse#interrupted()} 必须如实反映本轮是否被取消。
 * <p>
 * 该字段经 {@link HmsEvent.Complete#from} 透给前端，是「这次回答完整吗」的
 * 唯一结构化信号。若它恒为 false，前端只能靠在正文里搜 {@code [用户已中断]}
 * 这类本地化文本来猜 —— 而那段文本会随系统语言变化。
 */
class ResponseInterruptedFlagTest {

    /**
     * 首轮阻塞、其后立即返回的模型 —— 制造一个确定的「执行中」窗口，
     * 让 {@code cancel} 能在 {@code send} 仍在跑的时候到达。
     */
    private static class BlockingChatModel implements ChatModel {
        private static final long MAX_BLOCK_MILLIS = 2000;

        final CountDownLatch inCall = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.incrementAndGet() == 1) {
                inCall.countDown();
                try {
                    proceed.await(MAX_BLOCK_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("partial answer"), ChatGenerationMetadata.NULL)));
        }

        @Override
        public ChatOptions getOptions() {
            return null;
        }
    }

    private static DefaultHmsSessionManager newManager(ChatModel model) {
        return DefaultHmsSessionManager.builder(
                        model, new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(10)
                .build();
    }

    /**
     * 正常完成的请求，{@code interrupted} 必须是 false —— 作为下面断言的对照，
     * 确认该字段不是「恒为 true」而是确实在反映状态。
     */
    @Test
    void normalCompletionIsNotMarkedInterrupted() {
        ChatModel stub = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("done"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
        try (DefaultHmsSessionManager manager = newManager(stub)) {
            String sessionId = manager.createSession("s");
            HmsResponse response = manager.send(sessionId, "hi");
            assertFalse(response.interrupted(), "正常完成不应标记为被中断");
        }
    }

    /**
     * 请求执行期间被 {@code cancel}，返回的响应必须报 {@code interrupted() == true}。
     * <p>
     * 曾经的缺陷：{@code buildResponse} 只有 {@code HmsResponse.ok(...)} 一个
     * 出口，而 {@code ok} 把该字段硬编码为 false；{@code AgentLoop} 取消后只往
     * 返回文本尾部追加一个本地化标记，从不把「被取消」结构化地传出来。
     * <p>
     * 本例的取消时点落在首轮 API 调用<b>之后</b>的检查上，此时
     * {@code lastAssistantText} 仍是空串就 {@code break} 了 —— 连那个文本标记
     * 也不会出现。也就是说缺陷版本下前端连「搜正文」这条退路都没有：
     * 标志位与文本线索双双缺失。
     */
    @Test
    void cancelledRequestIsMarkedInterrupted() throws Exception {
        BlockingChatModel model = new BlockingChatModel();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (DefaultHmsSessionManager manager = newManager(model)) {
            String sessionId = manager.createSession("s");

            Future<HmsResponse> sending = pool.submit(() -> manager.send(sessionId, "hi"));

            // 等到 send 确实进入模型调用（持锁、执行中）再取消
            assertTrue(model.inCall.await(5, TimeUnit.SECONDS), "模型应已进入调用");
            manager.cancel(sessionId);
            model.proceed.countDown();

            HmsResponse response = sending.get(10, TimeUnit.SECONDS);

            assertTrue(response.interrupted(),
                    "执行期间被 cancel 的请求必须报 interrupted()==true。"
                            + "该标志经 HmsEvent.Complete 透给前端，是判断回答是否完整的"
                            + "唯一结构化信号；恒为 false 会让前端只能去正文里搜"
                            + "「[用户已中断]」这类会随语言变化的文本。实际正文="
                            + response.content());
        } finally {
            pool.shutdownNow();
        }
    }
}
