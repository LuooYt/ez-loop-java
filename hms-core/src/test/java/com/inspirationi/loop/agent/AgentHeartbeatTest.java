package com.inspirationi.loop.agent;

import com.inspirationi.loop.api.ChatMessage;
import com.inspirationi.loop.api.HmsResponse;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主动智能体的心跳循环 —— 本能力最容易失控的一环，护栏逐条验证。
 * <p>
 * 全部用手动 {@code triggerHeartbeatOnce()} 驱动（除定时器那条），不依赖真实节拍 ——
 * 心跳的「什么时候跑」由 {@link AgentHeartbeatScheduler} 负责，「跑什么」由
 * {@link AgentRuntime} 负责，测试只需盯住后者。
 */
class AgentHeartbeatTest {

    // ==================== ① 基本执行 ====================

    @Test
    void tickRunsAndLandsInBackendSession() {
        HeartbeatSpec spec = new HeartbeatSpec(60, 100, 1.0, 10, "自省一下");
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", spec), new CapturingModel(), null);

        try {
            assertNull(runtime.backendSessionId(), "尚未心跳时不应有后台会话");

            assertTrue(runtime.triggerHeartbeatOnce(), "主动 Agent 的心跳应真的执行一次");
            assertEquals(1, runtime.totalRuns());
            assertNotNull(runtime.backendSessionId(), "心跳应懒创建后台会话");

            // ⑥ 产物确实落在后台会话历史里，而不是凭空消失
            List<ChatMessage> history =
                    runtime.sessionManager().getSessionMessages(runtime.backendSessionId());
            assertTrue(history.stream().anyMatch(m -> "user".equals(m.role())), "应有唤醒提示");
            assertTrue(history.stream().anyMatch(m -> "assistant".equals(m.role())), "应有产物回复");
        } finally {
            runtime.close();
        }
    }

    @Test
    void passiveAgentNeverTicks() {
        AgentRuntime runtime = AgentTestSupport.runtime(AgentTestSupport.passive("idle-one"));
        try {
            assertFalse(runtime.triggerHeartbeatOnce(), "被动 Agent 不该有心跳");
            assertEquals(0, runtime.totalRuns());
            assertNull(runtime.backendSessionId(), "被动 Agent 不该建后台会话");
        } finally {
            runtime.close();
        }
    }

    @Test
    void backendSessionIsReusedAcrossTicks() {
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 1.0, 10, "自省")),
                new CapturingModel(), null);
        try {
            runtime.triggerHeartbeatOnce();
            String first = runtime.backendSessionId();
            runtime.triggerHeartbeatOnce();
            assertEquals(first, runtime.backendSessionId(),
                    "后台会话必须长期复用 —— 每跳新建一个会丢掉累积的产物");
        } finally {
            runtime.close();
        }
    }

    // ==================== ② 工作项 vs 自省 ====================

    /** 队列非空时消费工作项，空时用自省模板 —— 两条来源的优先级。 */
    @Test
    void consumesWorkItemThenFallsBackToIntrospection() {
        String introspection = "定期巡检：复查既定目标进展";
        CapturingModel model = new CapturingModel();
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 1.0, 10, introspection)),
                model, null);

        try {
            runtime.workQueue().enqueue("把昨天的报告补完", "external");
            assertTrue(runtime.triggerHeartbeatOnce());
            assertEquals("把昨天的报告补完", model.lastUserMessage(),
                    "队列非空时心跳必须优先消费工作项");
            assertTrue(runtime.workQueue().isEmpty(), "消费过的工作项应出队");

            // 队列已空 → 回落到自省模板
            assertTrue(runtime.triggerHeartbeatOnce());
            assertEquals(introspection, model.lastUserMessage(),
                    "队列为空时应使用自省提示词");
        } finally {
            runtime.close();
        }
    }

    /**
     * 发送失败时，工作项必须放回队首 —— 它已经离开队列了。
     * <p>
     * 旧实现 poll 出来就直接 send，异常沿着 triggerHeartbeatOnce 的 catch 消失，
     * 工作项随之蒸发；调用方只看到「本轮没执行」，谁也不知道那条工作丢了。
     */
    @Test
    void failedWorkItemIsRequeuedAtTheHead() {
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 1.0, 10, "自省")),
                failingModel(), null);
        try {
            AgentWorkQueue.WorkItem queued = runtime.workQueue().enqueue("补完昨天的报告", "external");

            assertFalse(runtime.triggerHeartbeatOnce(), "发送失败后本轮不算执行成功");

            AgentWorkQueue.WorkItem head = runtime.workQueue().peek();
            assertNotNull(head, "失败的工作项不能消失");
            assertEquals(queued.id(), head.id(), "放回的应是同一个工作项");
            assertEquals(1, head.attempts(), "尝试次数应递增，用于最终丢弃判定");
            assertEquals(1, runtime.idleRuns(), "失败要计入空闲，否则退避永不生效");
        } finally {
            runtime.close();
        }
    }

    /** 反复失败的工作项最终被丢弃，而不是无限重试把队列里其它工作饿死。 */
    @Test
    void workItemIsDroppedAfterMaxAttempts() {
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 1.0, 10, "自省")),
                failingModel(), null);
        try {
            runtime.workQueue().enqueue("注定失败的任务", "external");

            runtime.triggerHeartbeatOnce();  // 尝试 1 → 放回
            runtime.triggerHeartbeatOnce();  // 尝试 2 → 放回
            assertFalse(runtime.workQueue().isEmpty(), "未达上限前应继续重试");

            runtime.triggerHeartbeatOnce();  // 尝试 3 → 丢弃
            assertTrue(runtime.workQueue().isEmpty(), "超过尝试上限后应丢弃而不是无限重试");
        } finally {
            runtime.close();
        }
    }

    /** 只会抛异常的模型 —— 模拟上游不可用。 */
    private static ChatModel failingModel() {
        ChatOptions options = ChatOptions.builder().model("stub-model").build();
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("upstream unavailable");
            }

            @Override
            public ChatOptions getOptions() {
                return options;
            }
        };
    }

    /** 工作项按 FIFO 顺序被逐跳消费 —— 外部投递的任务不会被插队或吞掉。 */
    @Test
    void workItemsAreConsumedInOrder() {
        CapturingModel model = new CapturingModel();
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 1.0, 10, "自省")),
                model, null);
        try {
            runtime.workQueue().enqueue("第一件", "external");
            runtime.workQueue().enqueue("第二件", "external");

            runtime.triggerHeartbeatOnce();
            assertEquals("第一件", model.lastUserMessage());
            runtime.triggerHeartbeatOnce();
            assertEquals("第二件", model.lastUserMessage());
            assertTrue(runtime.workQueue().isEmpty());
        } finally {
            runtime.close();
        }
    }

    // ==================== ③ 防重入 ====================

    /**
     * 后台会话正在执行时，本次心跳必须<b>立刻放弃</b>，而不是排队等它跑完。
     * <p>
     * 这条断言用 {@code assertTimeoutPreemptively} 收紧到 2 秒：若实现改成
     * 「阻塞等待会话空闲」，长工具链场景下固定周期心跳会退化成「一跑完就立刻再跑」
     * 的忙循环，而这里的超时会先把这件事暴露出来。
     */
    @Test
    void skipsWhenBackendSessionIsBusy() throws Exception {
        CapturingModel model = new CapturingModel();
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 1.0, 10, "自省")),
                model, null);

        try {
            // 先正常跑一轮，把后台会话建起来
            runtime.triggerHeartbeatOnce();
            String backend = runtime.backendSessionId();
            int runsBefore = runtime.totalRuns();

            // 让它卡在模型调用里，模拟「上一轮还没结束」
            model.block();
            Thread sender = new Thread(() ->
                    runtime.sessionManager().send(backend, "用户插话"), "busy-sender");
            sender.setDaemon(true);
            sender.start();
            assertTrue(model.awaitEntered(5, TimeUnit.SECONDS), "模型应已进入阻塞调用");

            // 会话忙 → 本轮心跳跳过，且不新增轮数
            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> assertFalse(runtime.triggerHeartbeatOnce(),
                            "后台会话执行中，心跳应跳过而不是排队"),
                    "心跳不得阻塞等待忙会话 —— 那会退化成忙循环");
            assertEquals(runsBefore, runtime.totalRuns(), "被跳过的一轮不应计入轮数");

            model.release();
            sender.join(5000);
        } finally {
            model.release();
            runtime.close();
        }
    }

    /**
     * 重入闸：一次心跳还在跑时，第二次调用直接返回 false。
     * <p>
     * 与上一条不同 —— 这里占用的是 {@code ticking} 闸本身（调度线程重入），
     * 上一条占用的是会话。两者各自独立生效，任一条失效都会让心跳叠加执行。
     */
    @Test
    void concurrentTickIsRejectedByReentrancyGate() throws Exception {
        CapturingModel model = new CapturingModel();
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 1.0, 10, "自省")),
                model, null);

        try {
            model.block();
            Thread first = new Thread(runtime::triggerHeartbeatOnce, "first-tick");
            first.setDaemon(true);
            first.start();
            assertTrue(model.awaitEntered(5, TimeUnit.SECONDS));

            assertFalse(runtime.triggerHeartbeatOnce(), "上一轮未结束时不应重入");

            model.release();
            first.join(5000);
            assertEquals(1, runtime.totalRuns(), "两轮并发调用只有一轮真正执行");
        } finally {
            model.release();
            runtime.close();
        }
    }

    // ==================== ④ 自动暂停 ====================

    /** 达 {@code maxConsecutiveRuns} 后自动暂停 —— 防无限自触发烧 token 的最后一道闸。 */
    @Test
    void autoPausesAtMaxConsecutiveRuns() {
        HeartbeatSpec spec = new HeartbeatSpec(60, 3, 1.0, 10, "自省");
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", spec), new CapturingModel(), null);

        try {
            assertTrue(runtime.triggerHeartbeatOnce());
            assertTrue(runtime.triggerHeartbeatOnce());
            assertTrue(runtime.triggerHeartbeatOnce());
            assertEquals(3, runtime.totalRuns());

            assertFalse(runtime.triggerHeartbeatOnce(), "越过上限应停止");
            assertTrue(runtime.isHeartbeatPaused(), "并标记为已暂停");
            assertEquals(3, runtime.totalRuns(), "暂停后不得再消耗 token");
        } finally {
            runtime.close();
        }
    }

    @Test
    void resumeHeartbeatResetsCountersAndAllowsTickingAgain() {
        HeartbeatSpec spec = new HeartbeatSpec(60, 2, 1.0, 10, "自省");
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", spec), new CapturingModel(), null);

        try {
            runtime.triggerHeartbeatOnce();
            runtime.triggerHeartbeatOnce();
            assertFalse(runtime.triggerHeartbeatOnce(), "已达上限");
            assertTrue(runtime.isHeartbeatPaused());

            runtime.resumeHeartbeat();
            assertFalse(runtime.isHeartbeatPaused());
            assertEquals(0, runtime.idleRuns(), "恢复应重置无产出计数");

            assertTrue(runtime.triggerHeartbeatOnce(), "恢复后应能继续心跳");
            assertEquals(3, runtime.totalRuns(), "totalRuns 是累计值，不因恢复而归零");
        } finally {
            runtime.close();
        }
    }

    /** 显式暂停：暂停期间 tick 一律不执行。 */
    @Test
    void pauseHeartbeatStopsTicking() {
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 1.0, 10, "自省")),
                new CapturingModel(), null);
        try {
            runtime.pauseHeartbeat();
            assertFalse(runtime.triggerHeartbeatOnce());
            assertEquals(0, runtime.totalRuns());

            runtime.resumeHeartbeat();
            assertTrue(runtime.triggerHeartbeatOnce());
        } finally {
            runtime.close();
        }
    }

    // ==================== 空闲降频 ====================

    /**
     * 连续无产出时按系数降频 —— 空转的心跳不该保持原节拍。
     * <p>
     * 参数取 {@code maxIdleRuns=1, factor=4}：前两跳照常，之后每 4 次 tick 才真正
     * 执行一次。断言覆盖「跳过的那些 tick 不计入轮数」这一关键行为。
     */
    @Test
    void backsOffAfterConsecutiveIdleRuns() {
        HeartbeatSpec spec = new HeartbeatSpec(60, 100, 4.0, 1, "自省");
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", spec), new CapturingModel(), null);

        try {
            assertTrue(runtime.triggerHeartbeatOnce(), "第 1 跳：无历史空闲，照常执行");
            assertEquals(1, runtime.idleRuns(), "纯文本回复无工具调用 → 记一次无产出");

            assertTrue(runtime.triggerHeartbeatOnce(), "第 2 跳：空闲 1 次，skip=4^0=1 仍执行");
            assertEquals(2, runtime.idleRuns());

            assertFalse(runtime.triggerHeartbeatOnce(), "第 3 跳：进入降频，被跳过");
            assertFalse(runtime.triggerHeartbeatOnce(), "第 4 跳：跳过");
            assertFalse(runtime.triggerHeartbeatOnce(), "第 5 跳：跳过");
            assertEquals(2, runtime.totalRuns(), "被降频跳过的 tick 不得计入轮数");

            assertTrue(runtime.triggerHeartbeatOnce(), "第 6 跳：取模命中，恢复执行一次");
            assertEquals(3, runtime.totalRuns());
        } finally {
            runtime.close();
        }
    }

    /** 有产出（消费了工作项）即清零空闲计数，降频不累积。 */
    @Test
    void productiveRunResetsIdleCounter() {
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 2.0, 1, "自省")),
                new CapturingModel(), null);
        try {
            runtime.triggerHeartbeatOnce();          // 自省，无产出
            assertEquals(1, runtime.idleRuns());

            runtime.workQueue().enqueue("干活", "external");
            runtime.triggerHeartbeatOnce();
            assertEquals(0, runtime.idleRuns(), "消费了工作项即视为有产出");
        } finally {
            runtime.close();
        }
    }

    // ==================== ① 定时器接入 ====================

    /**
     * 真实调度路径：{@link AgentHeartbeatScheduler#start} 之后，无需任何手动触发，
     * 心跳应自行按周期跑起来。周期取最小合法值 1 秒，避免测试拖长。
     */
    @Test
    void schedulerDrivesTicksWithoutManualTrigger() throws Exception {
        try (AgentHeartbeatScheduler scheduler =
                     new AgentHeartbeatScheduler(AgentTestSupport.sharedScheduler())) {
            AgentRuntime runtime = AgentTestSupport.runtime(
                    AgentTestSupport.proactive("timer", new HeartbeatSpec(1, 100, 1.0, 10, "自省")),
                    new CapturingModel(), null);
            try {
                assertFalse(scheduler.isRunning("timer"));
                scheduler.start(runtime);
                assertTrue(scheduler.isRunning("timer"), "主动 Agent 注册后应有心跳任务");
                assertEquals(1, scheduler.size());

                assertTrue(awaitRuns(runtime, 1, 10), "调度器应在周期到达后自行触发心跳");

                scheduler.stop("timer");
                assertFalse(scheduler.isRunning("timer"));
            } finally {
                runtime.close();
            }
        }
    }

    /** 被动 Agent 不登记心跳任务 —— 调度器不该为空转对象占坑。 */
    @Test
    void schedulerSkipsPassiveAgent() {
        try (AgentHeartbeatScheduler scheduler =
                     new AgentHeartbeatScheduler(AgentTestSupport.sharedScheduler())) {
            AgentRuntime runtime = AgentTestSupport.runtime(AgentTestSupport.passive("quiet"));
            try {
                scheduler.start(runtime);
                assertFalse(scheduler.isRunning("quiet"));
                assertEquals(0, scheduler.size());
            } finally {
                runtime.close();
            }
        }
    }

    // ==================== 下线语义 ====================

    @Test
    void closedRuntimeRefusesFurtherTicks() {
        AgentRuntime runtime = AgentTestSupport.runtime(
                AgentTestSupport.proactive("worker", new HeartbeatSpec(60, 100, 1.0, 10, "自省")),
                new CapturingModel(), null);

        runtime.close();
        assertTrue(runtime.isClosed());
        assertFalse(runtime.triggerHeartbeatOnce(), "下线后不得再心跳");
        assertTrue(runtime.workQueue().isEmpty(), "下线应清空工作队列");

        runtime.close();  // 幂等
    }

    // ==================== 测试桩 ====================

    /** 轮询等待心跳跑够指定轮数。 */
    private static boolean awaitRuns(AgentRuntime runtime, int target, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (runtime.totalRuns() >= target) {
                return true;
            }
            Thread.sleep(50);
        }
        return runtime.totalRuns() >= target;
    }

    /**
     * 记录每次收到的用户消息、并可在指定时刻阻塞的模型桩。
     * <p>
     * 阻塞能力用于构造「会话正在执行中」这一瞬时状态 —— 没有它，防重入护栏
     * 只能靠时序运气去碰。
     */
    private static final class CapturingModel implements ChatModel {

        private static final String REPLY = "收到";

        private final ChatOptions options = ChatOptions.builder().model("stub-model").build();
        private final List<String> userMessages = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger calls = new AtomicInteger();

        private volatile CountDownLatch blockLatch;
        private volatile CountDownLatch enteredLatch;

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            userMessages.add(lastUserText(prompt));
            CountDownLatch block = blockLatch;
            if (block != null) {
                CountDownLatch entered = enteredLatch;
                if (entered != null) {
                    entered.countDown();
                }
                try {
                    block.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage(REPLY), ChatGenerationMetadata.NULL)));
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }

        /** 收到的最后一条用户消息 —— 心跳发的提示词。 */
        String lastUserMessage() {
            synchronized (userMessages) {
                return userMessages.isEmpty() ? null : userMessages.get(userMessages.size() - 1);
            }
        }

        int callCount() {
            return calls.get();
        }

        /** 让下一次（以及此后每次）模型调用阻塞，直到 {@link #release()}。 */
        void block() {
            blockLatch = new CountDownLatch(1);
            enteredLatch = new CountDownLatch(1);
        }

        /** 等待模型真的进入了阻塞调用 —— 否则后续断言会跑在竞态上。 */
        boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
            CountDownLatch entered = enteredLatch;
            return entered != null && entered.await(timeout, unit);
        }

        void release() {
            CountDownLatch block = blockLatch;
            blockLatch = null;
            if (block != null) {
                block.countDown();
            }
        }

        private static String lastUserText(Prompt prompt) {
            List<Message> instructions = prompt.getInstructions();
            for (int i = instructions.size() - 1; i >= 0; i--) {
                if (instructions.get(i) instanceof UserMessage um) {
                    return um.getText();
                }
            }
            return "";
        }
    }
}
