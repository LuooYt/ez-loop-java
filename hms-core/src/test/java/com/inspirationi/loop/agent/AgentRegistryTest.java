package com.inspirationi.loop.agent;

import com.inspirationi.loop.api.HmsErrorCode;
import com.inspirationi.loop.api.HmsException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentRegistry} 的插拔语义 —— 上线、下线、替换、并发注册的唯一性。
 * <p>
 * 这些是「运行时插拔」的全部对外承诺，任何一条失守都会表现为「Agent 莫名其妙
 * 不在了」或「两个 Agent 抢同一个 id」，而二者的排查成本都极高。
 */
class AgentRegistryTest {

    private final AgentHeartbeatScheduler scheduler =
            new AgentHeartbeatScheduler(AgentTestSupport.sharedScheduler());
    private final AgentRegistry registry = new AgentRegistry(scheduler);

    @AfterEach
    void tearDown() {
        registry.close();
        scheduler.close();
    }

    // ==================== 注册 ====================

    @Test
    void registerThenLookup() {
        AgentRuntime runtime = registry.register(AgentTestSupport.runtime(AgentTestSupport.passive("alpha")));

        assertSame(runtime, registry.get("alpha"));
        assertSame(runtime, registry.require("alpha"));
        assertTrue(registry.contains("alpha"));
        assertEquals(1, registry.size());
        assertEquals(List.of("alpha"), registry.ids());
    }

    @Test
    void duplicateIdIsRejectedAndLoserIsReleased() {
        registry.register(AgentTestSupport.runtime(AgentTestSupport.passive("alpha")));
        AgentRuntime loser = AgentTestSupport.runtime(AgentTestSupport.passive("alpha"));

        HmsException e = assertThrows(HmsException.class, () -> registry.register(loser));
        assertEquals(HmsErrorCode.AGENT_ALREADY_EXISTS, e.getErrorCode());

        assertTrue(loser.isClosed(),
                "注册失败者的运行时必须立即释放 —— 否则留下一个不在注册表里、" +
                        "却仍持有会话管理器的幽灵 Agent");
        assertEquals(1, registry.size(), "原注册不应被顶掉");
    }

    /**
     * 并发注册同一个 id：{@code putIfAbsent} 抢占，恰好一个成功。
     * <p>
     * 用「先查后放」的写法会在这里产生两个「成功」—— 两个 Agent 同时上线，
     * 而其中一个的会话会被路由到另一个身上。
     */
    @Test
    void concurrentRegisterOfSameIdHasExactlyOneWinner() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        List<AgentRuntime> all = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                AgentRuntime candidate = AgentTestSupport.runtime(AgentTestSupport.passive("race"));
                all.add(candidate);
                pool.submit(() -> {
                    try {
                        start.await();
                        registry.register(candidate);
                        successes.incrementAndGet();
                    } catch (HmsException expected) {
                        // 败者：id 已被占用
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));

            assertEquals(1, successes.get(), "并发注册同一 id 必须恰好一个成功");
            assertEquals(1, registry.size());

            long closedCount = all.stream().filter(AgentRuntime::isClosed).count();
            assertEquals(threads - 1, closedCount, "全部败者都应被释放");
            assertFalse(registry.require("race").isClosed(), "胜者必须仍然在线");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void requireUnknownAgentThrowsNotFound() {
        HmsException e = assertThrows(HmsException.class, () -> registry.require("nobody"));
        assertEquals(HmsErrorCode.AGENT_NOT_FOUND, e.getErrorCode());
        assertNull(registry.get("nobody"), "get 与 require 的差别就在这里：一个返回 null");
    }

    // ==================== 心跳随注册启停 ====================

    @Test
    void proactiveAgentGetsHeartbeatOnRegister() {
        registry.register(AgentTestSupport.runtime(
                AgentTestSupport.proactive("walker", HeartbeatSpec.every(300))));

        assertTrue(scheduler.isRunning("walker"), "主动 Agent 注册即应挂上心跳");
    }

    @Test
    void passiveAgentDoesNotGetHeartbeat() {
        registry.register(AgentTestSupport.runtime(AgentTestSupport.passive("quiet")));
        assertFalse(scheduler.isRunning("quiet"));
    }

    /** 下线必须真的取消心跳任务 —— 留下孤儿任务就是留下一个持续烧 token 的幽灵。 */
    @Test
    void unregisterStopsHeartbeatTask() {
        registry.register(AgentTestSupport.runtime(
                AgentTestSupport.proactive("walker", HeartbeatSpec.every(300))));
        assertTrue(scheduler.isRunning("walker"));

        assertTrue(registry.unregister("walker", false));

        assertFalse(scheduler.isRunning("walker"), "心跳任务必须被取消");
        assertEquals(0, scheduler.size(), "调度器不应残留任何任务句柄");
        assertNull(registry.get("walker"));
    }

    // ==================== 下线 ====================

    @Test
    void defaultAgentCannotBeUnregistered() {
        registry.register(AgentTestSupport.runtime(
                AgentTestSupport.passive(AgentRegistry.DEFAULT_AGENT_ID)));

        HmsException e = assertThrows(HmsException.class,
                () -> registry.unregister(AgentRegistry.DEFAULT_AGENT_ID, true));
        assertEquals(HmsErrorCode.AGENT_BUSY, e.getErrorCode());
        assertNotNull(registry.get(AgentRegistry.DEFAULT_AGENT_ID),
                "默认 Agent 是单 Agent 部署的行为基线，不可下线");
    }

    @Test
    void unregisterWithLiveSessionsRequiresForce() {
        AgentRuntime runtime = registry.register(
                AgentTestSupport.runtime(AgentTestSupport.passive("alpha")));
        runtime.sessionManager().createSession();
        assertEquals(1, runtime.userSessionCount());

        HmsException e = assertThrows(HmsException.class, () -> registry.unregister("alpha", false));
        assertEquals(HmsErrorCode.AGENT_BUSY, e.getErrorCode());
        assertNotNull(registry.get("alpha"), "拒绝后该 Agent 仍应在线");

        assertTrue(registry.unregister("alpha", true), "force 应能强制下线");
        assertNull(registry.get("alpha"));
        assertTrue(runtime.isClosed());
    }

    /**
     * 强制下线要销毁该 Agent 的全部会话 —— 含后台会话。
     * <p>
     * 漏掉后台会话的话，主动 Agent 的产物会话会留在门面的 {@code listSessions}
     * 里，而它归属的 manager 已经关闭：列表点得进去、发消息必失败。
     */
    @Test
    void forceUnregisterDestroysSessionsIncludingBackend() {
        AgentRuntime runtime = registry.register(AgentTestSupport.runtime(
                AgentTestSupport.proactive("walker", HeartbeatSpec.every(300))));
        runtime.sessionManager().createSession("用户会话");
        runtime.triggerHeartbeatOnce();
        assertNotNull(runtime.backendSessionId(), "先造出后台会话");
        assertEquals(2, runtime.allSessionIds().size());

        registry.unregister("walker", true);

        assertTrue(runtime.allSessionIds().isEmpty(), "全部会话（含后台）都应被销毁");
    }

    @Test
    void unregisterUnknownAgentReturnsFalse() {
        assertFalse(registry.unregister("nobody", false));
    }

    // ==================== 替换 ====================

    @Test
    void registerOrReplaceSwapsDefinition() {
        AgentRuntime first = registry.register(AgentTestSupport.runtime(AgentTestSupport.passive("alpha")));
        AgentRuntime second = AgentTestSupport.runtime(
                AgentDefinition.builder("alpha").basePrompt("换一套提示词").build());

        AgentRuntime replaced = registry.registerOrReplace(second);

        assertSame(first, replaced, "应返回被顶替的旧运行时");
        assertSame(second, registry.get("alpha"));
        assertTrue(first.isClosed(), "旧运行时必须释放");
        assertEquals(1, registry.size(), "替换不改变 Agent 数量");
    }

    @Test
    void registerOrReplaceRejectsWhenOldAgentHasLiveSessions() {
        AgentRuntime first = registry.register(AgentTestSupport.runtime(AgentTestSupport.passive("alpha")));
        first.sessionManager().createSession();

        AgentRuntime second = AgentTestSupport.runtime(AgentTestSupport.passive("alpha"));
        HmsException e = assertThrows(HmsException.class, () -> registry.registerOrReplace(second));
        assertEquals(HmsErrorCode.AGENT_BUSY, e.getErrorCode());

        assertTrue(second.isClosed(), "被拒的新运行时也应释放");
        assertSame(first, registry.get("alpha"), "旧 Agent 保持在线");
        assertFalse(first.isClosed());
    }

    @Test
    void registerOrReplaceOnFreshIdReturnsNull() {
        assertNull(registry.registerOrReplace(
                AgentTestSupport.runtime(AgentTestSupport.passive("fresh"))));
        assertEquals(1, registry.size());
    }

    // ==================== 查询辅助 ====================

    @Test
    void listIsSortedAndDelegatableFiltersCorrectly() {
        registry.register(AgentTestSupport.runtime(AgentTestSupport.passive("charlie")));
        registry.register(AgentTestSupport.runtime(AgentTestSupport.passive("alpha")));
        registry.register(AgentTestSupport.runtime(
                AgentDefinition.builder("bravo").delegatable(false).build()));

        assertEquals(List.of("alpha", "bravo", "charlie"), registry.ids(), "输出应稳定有序");
        assertEquals(List.of("alpha", "charlie"),
                registry.listDelegatable().stream().map(rt -> rt.definition().id()).toList(),
                "不可委派的 Agent 不应出现在给模型看的清单里");
    }

    /** 注册中心是纯内存的：close 之后一切归零，不留半关闭状态。 */
    @Test
    void closeReleasesEverything() {
        AgentRuntime runtime = registry.register(AgentTestSupport.runtime(
                AgentTestSupport.proactive("walker", HeartbeatSpec.every(300))));

        registry.close();

        assertEquals(0, registry.size());
        assertTrue(runtime.isClosed());
        assertFalse(scheduler.isRunning("walker"));
    }

    /** 备注：注册表不持有线程池，close 不得关掉共享调度器（否则会顺带杀死会话清理任务）。 */
    @Test
    void closeDoesNotShutdownSharedScheduler() {
        registry.register(AgentTestSupport.runtime(
                AgentTestSupport.proactive("walker", HeartbeatSpec.every(300))));

        registry.close();

        assertFalse(AgentTestSupport.sharedScheduler().isShutdown(),
                "共享调度池由容器持有，注册中心无权关闭它");
    }
}
