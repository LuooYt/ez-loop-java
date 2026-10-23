package com.inspirationi.loop.api;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一会话并发请求必须<b>立刻</b>以 {@code AGENT_BUSY} 失败。
 * <p>
 * 曾经的缺陷：会话互斥用 {@code synchronized (session)}，第二个请求无限期排队。
 * 一个跑长工具链的请求（子 Agent、慢网页抓取）能把会话占住十几分钟，此时用户
 * 刷新页面再发一句，界面只会永远转圈 —— 调用方既拿不到「忙」，也给不出提示。
 * <p>
 * 复现必须让第一个请求<b>确实</b>还持有会话锁：用闩锁把模型调用卡在循环中间，
 * 而不是指望桩模型跑得够慢。
 */
class SessionBusyTest {

    /** 首轮阻塞的模型 —— 制造一个确定的「第一个请求仍在执行」窗口。 */
    private static class BlockingChatModel implements ChatModel {
        private static final long MAX_BLOCK_MILLIS = 2000;

        final CountDownLatch inCall = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean firstCall = calls.incrementAndGet() == 1;
            inCall.countDown();
            if (firstCall) {
                try {
                    proceed.await(MAX_BLOCK_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            AssistantMessage msg = AssistantMessage.builder()
                    .content("thinking")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-" + calls.get(), "function", "Ghost", "{}")))
                    .build();
            return new ChatResponse(List.of(new Generation(msg, ChatGenerationMetadata.NULL)));
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

    @Test
    void concurrentSendFailsFastWithAgentBusy() throws Exception {
        BlockingChatModel model = new BlockingChatModel();

        try (DefaultHmsSessionManager manager = newManager(model)) {
            String sessionId = manager.createSession("s");

            Thread worker = Thread.ofVirtual().start(() -> manager.send(sessionId, "first"));

            // 第一个请求已进入循环、持有会话锁，并卡在模型调用里
            assertTrue(model.inCall.await(5, TimeUnit.SECONDS), "模型应已被调用");

            long start = System.nanoTime();
            // 断言与放行顺序：先拿异常，再无条件放行第一轮，避免断言失败后卡死套件
            HmsException busy;
            try {
                busy = assertThrows(HmsException.class,
                        () -> manager.send(sessionId, "second"));
            } finally {
                model.proceed.countDown();
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertEquals(HmsErrorCode.AGENT_BUSY, busy.getErrorCode(),
                    "并发请求应报 AGENT_BUSY，而不是别的错误");
            // 排队等待的实现会让这里停在秒级；fail-fast 应在毫秒级返回。
            // 上限取 1 秒：既远离「秒级」的缺陷表现，又给慢速 CI 留出余量。
            assertTrue(elapsedMillis < 1000,
                    "第二个请求必须立刻失败，实际耗时 " + elapsedMillis + "ms");

            worker.join(15_000);
            assertTrue(!worker.isAlive(), "第一个请求应已结束");
        }
    }

    @Test
    void lockIsReleasedAfterTheFirstRequest() throws Exception {
        BlockingChatModel model = new BlockingChatModel();

        try (DefaultHmsSessionManager manager = newManager(model)) {
            String sessionId = manager.createSession("s");

            Thread worker = Thread.ofVirtual().start(() -> manager.send(sessionId, "first"));
            assertTrue(model.inCall.await(5, TimeUnit.SECONDS), "模型应已被调用");
            model.proceed.countDown();
            worker.join(15_000);

            // 上一轮正常结束后，同一会话必须能再次发送（锁在 finally 里释放）
            manager.send(sessionId, "second");
        }
    }
}
