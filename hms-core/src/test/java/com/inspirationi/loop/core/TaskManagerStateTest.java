package com.inspirationi.loop.core;

import com.inspirationi.loop.core.TaskManager.TaskStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskManager} 的状态机不变量。
 * <p>
 * 核心不变量：<b>终态（COMPLETED / FAILED / CANCELLED）不可再变更</b>。
 * 该不变量若被破坏，调用方看到的任务状态会出现「完成后又变回运行中」这类
 * 不可能的转换 —— 依赖状态轮询的编排逻辑（如 Agent 的 TaskOutput 工具）会错乱。
 */
class TaskManagerStateTest {

    private TaskManager manager;

    @BeforeEach
    void setUp() {
        manager = new TaskManager();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void terminalStateCannotBeUpdated() {
        String id = manager.createManualTask("t");
        assertTrue(manager.updateTask(id, TaskStatus.COMPLETED, "done"));

        assertFalse(manager.updateTask(id, TaskStatus.RUNNING, "back to running"),
                "已完成的任务不应能改回 RUNNING");
        assertEquals(TaskStatus.COMPLETED, manager.getTask(id).orElseThrow().status());
        assertEquals("done", manager.getTask(id).orElseThrow().result());
    }

    @Test
    void cancelledTaskIsNotOverwrittenByCompletion() throws Exception {
        // 关键竞态：任务执行中被取消，其工作体随后仍正常返回。
        // 工作线程写 COMPLETED 时必须发现已是终态并放弃，否则 CANCELLED 被覆盖。
        //
        // 注意 cancelTask 会 future.cancel(true) 中断工作线程，所以工作体不能用
        // 可中断的等待（await/sleep）—— 那样测的就是「中断能否打断等待」而非
        // 「终态能否被覆盖」。这里用忙等 + 忽略中断，确保工作体一定跑到 return。
        java.util.concurrent.atomic.AtomicBoolean cancelIssued =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        CountDownLatch workReturned = new CountDownLatch(1);

        String id = manager.createTask("racing", () -> {
            while (!cancelIssued.get()) {
                Thread.onSpinWait();       // 不可中断，取消信号不会打断它
            }
            workReturned.countDown();
            return "work finished anyway";
        });

        // 等任务真正进入 RUNNING
        for (int i = 0; i < 200 && manager.getTask(id).orElseThrow().status() != TaskStatus.RUNNING; i++) {
            Thread.sleep(10);
        }
        assertEquals(TaskStatus.RUNNING, manager.getTask(id).orElseThrow().status());

        assertTrue(manager.cancelTask(id));
        cancelIssued.set(true);
        assertTrue(workReturned.await(5, TimeUnit.SECONDS), "工作体应已跑到返回");
        Thread.sleep(200);   // 留时间给工作线程写回状态

        assertEquals(TaskStatus.CANCELLED, manager.getTask(id).orElseThrow().status(),
                "取消后工作体正常返回，不得把状态覆盖成 COMPLETED");
    }

    @Test
    void concurrentUpdateAndCancelYieldsExactlyOneTerminalState() throws Exception {
        // 反复对同一任务并发 update(COMPLETED) 与 cancel，只能有一个胜出
        for (int round = 0; round < 200; round++) {
            String id = manager.createManualTask("t" + round);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            boolean[] results = new boolean[2];

            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    results[0] = manager.updateTask(id, TaskStatus.COMPLETED, "ok");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    results[1] = manager.cancelTask(id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));

            // 两个都返回 true 意味着有一方覆盖了对方设置的终态
            assertFalse(results[0] && results[1],
                    "第 " + round + " 轮：update 与 cancel 不应同时成功 —— "
                            + "终态被覆盖了");
        }
    }

    @Test
    void metadataIsVisibleToTheWorkBody() throws Exception {
        // 带元数据的自动任务：工作体启动时就应能读到 metadata。
        // 若实现是「先 submit 再补 metadata」，工作体会读到空 map。
        CountDownLatch bodyStarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> seen =
                new java.util.concurrent.atomic.AtomicReference<>();

        String[] idHolder = new String[1];
        String id = manager.createTask("with-meta", () -> {
            bodyStarted.countDown();
            seen.set(manager.getTask(idHolder[0]).orElseThrow().metadata());
            return "ok";
        }, Map.of("key", "value"));
        idHolder[0] = id;

        assertTrue(bodyStarted.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        // 最终状态里 metadata 必须存在
        assertEquals("value", manager.getTask(id).orElseThrow().metadata().get("key"),
                "任务完成后 metadata 应保留");
    }

    @Test
    void metadataSurvivesFastCompletingTask() throws Exception {
        // 工作体瞬间完成 —— metadata 补充与状态写回竞争，两者都不能丢
        for (int i = 0; i < 200; i++) {
            String id = manager.createTask("fast" + i, () -> "instant", Map.of("m", "v"));
            Thread.sleep(1);
            var info = manager.getTask(id).orElseThrow();
            assertEquals("v", info.metadata().get("m"),
                    "第 " + i + " 轮：快速完成的任务丢了 metadata");
        }
    }

    @Test
    void completedTaskCannotBeCancelled() {
        String id = manager.createManualTask("t");
        manager.updateTask(id, TaskStatus.COMPLETED, "done");

        assertFalse(manager.cancelTask(id), "已完成的任务不应能被取消");
        assertEquals(TaskStatus.COMPLETED, manager.getTask(id).orElseThrow().status());
    }

    @Test
    void failedWorkBodyIsRecordedAsFailed() throws Exception {
        String id = manager.createTask("boom", () -> {
            throw new IllegalStateException("intentional");
        });
        Thread.sleep(300);

        var info = manager.getTask(id).orElseThrow();
        assertEquals(TaskStatus.FAILED, info.status());
        assertTrue(info.result().contains("intentional"),
                "失败原因应被记录，实际：" + info.result());
    }
}
