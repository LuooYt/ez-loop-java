package com.inspirationi.loop.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AgentWorkQueue} 的 FIFO 语义与并发入队安全。 */
class AgentWorkQueueTest {

    @Test
    void enqueueAndPollFollowFifoOrder() {
        AgentWorkQueue queue = new AgentWorkQueue();
        queue.enqueue("第一件", "test");
        queue.enqueue("第二件", "test");
        queue.enqueue("第三件", "test");

        assertEquals("第一件", queue.poll().prompt());
        assertEquals("第二件", queue.poll().prompt());
        assertEquals("第三件", queue.poll().prompt());
        assertNull(queue.poll(), "队列取空后应返回 null 而不是抛异常");
    }

    @Test
    void peekDoesNotConsume() {
        AgentWorkQueue queue = new AgentWorkQueue();
        queue.enqueue("唯一一项", "test");

        assertEquals("唯一一项", queue.peek().prompt());
        assertEquals(1, queue.size(), "peek 不应改变队列长度");
        assertEquals("唯一一项", queue.poll().prompt());
    }

    @Test
    void emptyQueueReportsEmpty() {
        AgentWorkQueue queue = new AgentWorkQueue();
        assertTrue(queue.isEmpty());
        assertTrue(queue.snapshot().isEmpty());

        queue.enqueue("x", "s");
        assertFalse(queue.isEmpty());
    }

    @Test
    void clearEmptiesQueue() {
        AgentWorkQueue queue = new AgentWorkQueue();
        queue.enqueue("a", "s");
        queue.enqueue("b", "s");
        queue.clear();
        assertEquals(0, queue.size());
    }

    @Test
    void snapshotIsOrderedAndDetachedFromQueue() {
        AgentWorkQueue queue = new AgentWorkQueue();
        queue.enqueue("a", "s");
        queue.enqueue("b", "s");

        List<AgentWorkQueue.WorkItem> snapshot = queue.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals("a", snapshot.get(0).prompt());
        assertEquals("b", snapshot.get(1).prompt());

        // 快照之后入队不应改变已经取到的快照
        queue.enqueue("c", "s");
        assertEquals(2, snapshot.size());
    }

    @Test
    void enqueueItemAssignsIdAndTimestamp() {
        AgentWorkQueue queue = new AgentWorkQueue();
        AgentWorkQueue.WorkItem item = queue.enqueue("做点事", "external");

        assertNotNull(item.id());
        assertFalse(item.id().isBlank());
        assertEquals("external", item.source());
        assertNotNull(item.createdAt());
        assertEquals(item.id(), queue.peek().id());
    }

    /**
     * 并发入队不丢项 —— 生产者可能来自任意线程（外部 API、工具调用），
     * 而消费者只有心跳线程。这里让 8 个线程各入 100 项，验证总数精确。
     */
    @Test
    void concurrentEnqueueLosesNothing() throws Exception {
        AgentWorkQueue queue = new AgentWorkQueue();
        int threads = 8;
        int perThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            queue.enqueue("t" + id + "-" + i, "concurrent");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "入队线程应在超时前完成");

            assertEquals(threads * perThread, queue.size(),
                    "并发入队不得丢项 —— 底层必须是原子的 addLast");

            // 逐项取出，且每个 id 恰好出现一次
            java.util.Set<String> seen = new java.util.HashSet<>();
            AgentWorkQueue.WorkItem item;
            while ((item = queue.poll()) != null) {
                assertTrue(seen.add(item.id()), "同一工作项被取出两次：" + item.id());
            }
            assertEquals(threads * perThread, seen.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
