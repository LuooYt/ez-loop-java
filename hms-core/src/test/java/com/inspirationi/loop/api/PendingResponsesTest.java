package com.inspirationi.loop.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PendingResponses} 的并发契约测试。
 * <p>
 * 该类协调「Agent 执行线程阻塞等待」与「用户回答从另一线程到达」，其超时、
 * 顶替、清理语义都是回归成本极高的并发行为 —— 一旦破坏，症状是 Agent 线程
 * 永久挂起或用户回答被静默丢弃，都难以从日志定位。
 */
class PendingResponsesTest {

    @Test
    void submitDeliversAnswerToWaiter() throws Exception {
        PendingResponses pending = new PendingResponses(30);
        CompletableFuture<String> future = pending.awaitAskUser("s1");

        assertTrue(pending.submitAskUser("s1", "答案"));
        assertEquals("答案", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void timeoutCompletesWithDefaultRatherThanThrowing() throws Exception {
        // 超时以默认值「正常完成」，而非异常完成 —— 上游拿到的始终是明确的决定
        PendingResponses pending = new PendingResponses(1);

        CompletableFuture<String> ask = pending.awaitAskUser("s1");
        assertEquals(PendingResponses.DEFAULT_ASK_ANSWER, ask.get(5, TimeUnit.SECONDS),
                "提问超时应以 skip 完成");

        CompletableFuture<String> perm = pending.awaitPermission("s2");
        assertEquals(PendingResponses.DEFAULT_PERMISSION_CHOICE, perm.get(5, TimeUnit.SECONDS),
                "权限超时应以 deny 完成（fail-safe）");
    }

    @Test
    void submitAfterTimeoutIsRejectedNotSilentlyLost() throws Exception {
        PendingResponses pending = new PendingResponses(1);
        CompletableFuture<String> future = pending.awaitAskUser("s1");

        // 等到超时兜底生效
        assertEquals(PendingResponses.DEFAULT_ASK_ANSWER, future.get(5, TimeUnit.SECONDS));

        // 迟到的回答必须返回 false，让上游知道没送达
        assertFalse(pending.submitAskUser("s1", "迟到的答案"));
    }

    @Test
    void submitWithNoWaiterReturnsFalse() {
        PendingResponses pending = new PendingResponses(30);
        assertFalse(pending.submitAskUser("unknown", "x"),
                "无人等待时提交应返回 false 而非抛异常");
        assertFalse(pending.submitPermission("unknown", "allow"));
    }

    @Test
    void secondAwaitSupersedesTheFirstWithDefault() throws Exception {
        PendingResponses pending = new PendingResponses(30);
        CompletableFuture<String> first = pending.awaitAskUser("s1");
        CompletableFuture<String> second = pending.awaitAskUser("s1");

        // 旧 Future 必须被默认值完成，否则等它的线程会永久挂起
        assertEquals(PendingResponses.DEFAULT_ASK_ANSWER, first.get(5, TimeUnit.SECONDS),
                "被顶替的旧请求应以默认值完成，避免泄漏等待线程");

        assertTrue(pending.submitAskUser("s1", "新答案"));
        assertEquals("新答案", second.get(5, TimeUnit.SECONDS));
    }

    @Test
    void clearReleasesWaitersImmediately() throws Exception {
        // 超时设得很长：若 clear 不生效，这个测试会因等待而失败
        PendingResponses pending = new PendingResponses(3600);
        CompletableFuture<String> ask = pending.awaitAskUser("s1");
        CompletableFuture<String> perm = pending.awaitPermission("s1");

        pending.clear("s1");

        assertEquals(PendingResponses.DEFAULT_ASK_ANSWER, ask.get(5, TimeUnit.SECONDS));
        assertEquals(PendingResponses.DEFAULT_PERMISSION_CHOICE, perm.get(5, TimeUnit.SECONDS));
    }

    @Test
    void clearOnlyAffectsTheGivenSession() throws Exception {
        PendingResponses pending = new PendingResponses(3600);
        CompletableFuture<String> other = pending.awaitAskUser("s2");

        pending.clear("s1");

        assertFalse(other.isDone(), "clear 不应影响其他会话的等待");
        assertTrue(pending.submitAskUser("s2", "ok"));
        assertEquals("ok", other.get(5, TimeUnit.SECONDS));
    }

    @Test
    void askAndPermissionRegistriesAreIndependent() throws Exception {
        PendingResponses pending = new PendingResponses(3600);
        CompletableFuture<String> ask = pending.awaitAskUser("s1");
        CompletableFuture<String> perm = pending.awaitPermission("s1");

        // 同一会话可同时有提问与权限两个等待，互不干扰
        assertTrue(pending.submitPermission("s1", "allow"));
        assertEquals("allow", perm.get(5, TimeUnit.SECONDS));
        assertFalse(ask.isDone(), "提交权限不应完成提问的 Future");

        assertTrue(pending.submitAskUser("s1", "答案"));
        assertEquals("答案", ask.get(5, TimeUnit.SECONDS));
    }

    @Test
    void concurrentSubmitDeliversExactlyOnce() throws Exception {
        PendingResponses pending = new PendingResponses(30);
        CompletableFuture<String> future = pending.awaitAskUser("s1");

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger delivered =
                new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < threads; i++) {
            String answer = "answer-" + i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (pending.submitAskUser("s1", answer)) {
                        delivered.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发提交应在限时内完成");

        // 并发提交只能有一个成功 —— 否则用户会看到回答被重复消费
        assertEquals(1, delivered.get(), "并发提交应恰好一次送达成功");
        assertTrue(future.isDone());
    }

    @Test
    void getTimeoutSecondsReportsConfiguredValue() {
        assertEquals(42, new PendingResponses(42).getTimeoutSeconds());
    }
}
