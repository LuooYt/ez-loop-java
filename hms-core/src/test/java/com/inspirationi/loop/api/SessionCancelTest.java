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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cancel(sessionId)} 必须在请求执行<b>期间</b>生效。
 * <p>
 * 曾经的缺陷：{@code cancel} 也去抢 {@code synchronized (session)}，
 * 而 {@code send} 全程持有该锁 —— 取消请求只能排队到对话自然结束后才拿到锁，
 * 等于彻底失效（demo-app 的 /cancel 端点因此形同空操作）。
 * <p>
 * 复现该缺陷必须让 {@code send} 在被取消时<b>确实</b>还在执行并持有锁。
 * 用桩模型跑满迭代是不够的 —— 它快到 {@code send} 早已返回，
 * {@code cancel} 自然不会阻塞，缺陷版本也能通过。因此这里用闩锁把模型
 * 调用卡在循环中间，制造一个确定的「执行中」窗口。
 */
class SessionCancelTest {

    /**
     * 只在<b>第一轮</b>阻塞的模型：进入 {@code call()} 后先放行 {@link #inCall}，
     * 再等待 {@link #proceed}，从而让 {@code send} 稳定停在持锁状态；
     * 其后各轮立即返回。
     * <p>
     * 首轮的等待必须有上限（{@link #MAX_BLOCK_MILLIS}）且只作用于第一轮 ——
     * 否则在缺陷版本下 {@code proceed.countDown()} 永远等不到（它排在被阻塞的
     * {@code cancel} 之后），50 轮迭代会把一个应当秒级失败的断言拖成几百秒。
     */
    private static class BlockingChatModel implements ChatModel {
        /** 首轮阻塞上限 —— 只为制造「执行中」窗口，不应成为测试时长的来源。 */
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
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    private static DefaultHmsSessionManager newManager(ChatModel model) {
        return new DefaultHmsSessionManager(
                model, new ToolRegistry(), null,
                new DefaultPromptManager(null, "global"),
                3600, 3600, 300, 10);
    }

    @Test
    void cancelDoesNotBlockWhileSendHoldsTheSessionLock() throws Exception {
        BlockingChatModel model = new BlockingChatModel();

        try (DefaultHmsSessionManager manager = newManager(model)) {
            String sessionId = manager.createSession("s");

            Thread worker = Thread.ofVirtual().start(() -> manager.send(sessionId, "go"));

            // send 已进入循环并持有会话锁，且正卡在模型调用里
            assertTrue(model.inCall.await(5, TimeUnit.SECONDS), "模型应已被调用");

            // 在另一个线程里取消 —— 缺陷版本会在此阻塞到 send 结束
            CountDownLatch cancelReturned = new CountDownLatch(1);
            Thread canceller = Thread.ofVirtual().start(() -> {
                manager.cancel(sessionId);
                cancelReturned.countDown();
            });

            // 等待窗口必须短于模型首轮的阻塞上限，否则 send 会先自行解除阻塞、
            // 释放会话锁，让缺陷版本的 cancel 也「及时」返回而漏过缺陷。
            boolean returned = cancelReturned.await(1, TimeUnit.SECONDS);

            // 无论断言结果如何都要放行，避免卡住整个测试套件
            model.proceed.countDown();

            assertTrue(returned,
                    "cancel 在 send 执行期间必须立即返回；阻塞说明它去抢了会话锁");

            canceller.join(5_000);
            worker.join(15_000);
        }
    }

    @Test
    void cancelStopsAnInFlightLoop() throws Exception {
        BlockingChatModel model = new BlockingChatModel();

        try (DefaultHmsSessionManager manager = newManager(model)) {
            String sessionId = manager.createSession("s");

            CountDownLatch sendReturned = new CountDownLatch(1);
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    manager.send(sessionId, "go");
                } finally {
                    sendReturned.countDown();
                }
            });

            assertTrue(model.inCall.await(5, TimeUnit.SECONDS), "模型应已被调用");

            // 先取消，再放行第一轮 —— 循环应在下一个检查点退出。
            // 缺陷版本下 cancel 会阻塞在这里直到 send 跑完全部迭代，
            // 随后 calls==50 的断言失败（首轮阻塞有上限，因此仍是秒级）。
            manager.cancel(sessionId);
            model.proceed.countDown();

            assertTrue(sendReturned.await(15, TimeUnit.SECONDS),
                    "取消后循环应尽快退出");

            worker.join(5_000);
            // 该模型每轮都返回工具调用；未取消会跑满 MAX_ITERATIONS(50)
            assertTrue(model.calls.get() < 50,
                    "取消后不应跑满 MAX_ITERATIONS，实际调用 " + model.calls.get() + " 次");
        }
    }

    @Test
    void cancelOnUnknownSessionIsSilentlyIgnored() {
        try (DefaultHmsSessionManager manager = newManager(new BlockingChatModel())) {
            // 不存在的会话不应抛异常（既有语义）
            manager.cancel("no-such-session");
        }
    }
}
