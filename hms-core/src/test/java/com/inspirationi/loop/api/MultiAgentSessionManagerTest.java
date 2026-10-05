package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentRegistry;
import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.agent.HeartbeatSpec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多智能体会话门面 —— 容器里唯一的 {@link HmsSessionManager} Bean。
 * <p>
 * 门面自身不管理会话，只按 {@code sessionId} 路由；因此测试的重点全在「路由对不对」
 * 与「聚合值全不全」，而不是会话生命周期本身（那些留在各自的 manager 上，未被改动）。
 */
class MultiAgentSessionManagerTest {

    /** 无参 {@code createSession} 必须落在默认 Agent 上 —— 既有调用方的行为基线。 */
    @Test
    void createSessionLandsOnDefaultAgent() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            support.register("alpha");
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            String sessionId = facade.createSession();

            assertEquals(AgentRegistry.DEFAULT_AGENT_ID, facade.agentIdOf(sessionId));
            assertTrue(defaultAgent.sessionManager().sessionExists(sessionId));
            assertFalse(support.registry.get("alpha").sessionManager().sessionExists(sessionId));
        }
    }

    @Test
    void createAgentSessionLandsOnNamedAgent() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            AgentRuntime alpha = support.register("alpha");
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            String sessionId = facade.createAgentSession("alpha", "处理数据");

            assertEquals("alpha", facade.agentIdOf(sessionId));
            assertTrue(alpha.sessionManager().sessionExists(sessionId));
            assertFalse(defaultAgent.sessionManager().sessionExists(sessionId));
        }
    }

    @Test
    void createAgentSessionForUnknownAgentThrows() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            assertThrows(HmsException.class, () -> facade.createAgentSession("查无此人"));
        }
    }

    /** 路由不能串台：发给 A 的消息只出现在 A 的历史里。 */
    @Test
    void sendRoutesToOwningManager() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            support.register("alpha");
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            String sessionA = facade.createAgentSession("alpha");
            String sessionDefault = facade.createSession();

            facade.send(sessionA, "给 alpha 的话");
            facade.send(sessionDefault, "给默认智能体的话");

            assertTrue(hasUserMessage(facade, sessionA, "给 alpha 的话"));
            assertTrue(hasUserMessage(facade, sessionDefault, "给默认智能体的话"));
            assertFalse(hasUserMessage(facade, sessionA, "给默认智能体的话"),
                    "发给默认 Agent 的消息不得出现在 alpha 的会话里");
        }
    }

    /** 聚合视图：一次列出全部 Agent 的会话，并各自带上归属 agentId。 */
    @Test
    void listSessionsAggregatesWithCorrectAgentId() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            support.register("alpha");
            support.register("beta");
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            facade.createSession();
            facade.createAgentSession("alpha");
            facade.createAgentSession("alpha");
            facade.createAgentSession("beta");

            List<SessionInfo> all = facade.listSessions();
            assertEquals(4, all.size(), "应汇总全部 Agent 的会话");

            Set<String> agentIds = all.stream().map(SessionInfo::agentId).collect(Collectors.toSet());
            assertEquals(Set.of(AgentRegistry.DEFAULT_AGENT_ID, "alpha", "beta"), agentIds);

            assertEquals(2, all.stream().filter(s -> "alpha".equals(s.agentId())).count());
        }
    }

    /** 每个会话都能反查归属 —— 这是前端「按智能体分组」的依据。 */
    @Test
    void agentIdOfResolvesEverySession() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            support.register("alpha");
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            for (SessionInfo info : List.of(
                    infoOf(facade, facade.createSession()),
                    infoOf(facade, facade.createAgentSession("alpha")))) {
                assertEquals(info.agentId(), facade.agentIdOf(info.sessionId()),
                        "listSessions 给出的 agentId 与 agentIdOf 必须一致");
            }
            assertNull(facade.agentIdOf("不存在的会话"));
        }
    }

    /**
     * 未知 sessionId 的异常文本<b>逐字符</b>保持与改造前一致。
     * <p>
     * 既有集成方按消息文本做判断；门面若另造一句错误，那些判断会静默失效 ——
     * 而这里的比较对象正是它回落的那个 manager 自己抛的原文。
     */
    @Test
    void unknownSessionThrowsIdenticalMessageToPreviousImplementation() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            support.register("alpha");
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            String viaFacade = messageOf(() -> facade.send("no-such-session", "hi"));
            String viaDefault = messageOf(() ->
                    defaultAgent.sessionManager().send("no-such-session", "hi"));

            assertNotNull(viaFacade);
            assertEquals(viaDefault, viaFacade, "异常文本必须逐字符相同");
        }
    }

    @Test
    void sessionExistsReflectsActualOwnership() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            support.register("alpha");
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            String sessionA = facade.createAgentSession("alpha");

            assertTrue(facade.sessionExists(sessionA));
            assertFalse(facade.sessionExists("no-such-session"));
            assertFalse(facade.sessionExists(null), "null 会话 id 应安全返回 false");

            facade.destroySession(sessionA);
            assertFalse(facade.sessionExists(sessionA), "销毁后路由缓存必须失效");
        }
    }

    @Test
    void activeSessionCountSumsAcrossAgents() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            support.register("alpha");
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            facade.createSession();
            facade.createAgentSession("alpha");
            facade.createAgentSession("alpha");

            assertEquals(3, facade.getActiveSessionCount());
        }
    }

    /**
     * 主动 Agent 的后台会话不计入用户会话数上限。
     * <p>
     * 若计入，一个配了 {@code maxSessions=2} 的主动 Agent 会有一格被自己的心跳永久占住，
     * 用户实际只能开 1 个会话 —— 而错误信息会说「已达上限」，指向完全错误的原因。
     */
    @Test
    void heartbeatBackendSessionDoesNotConsumeSessionQuota() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            AgentRuntime worker = support.register("worker",
                    builder -> builder.proactive(HeartbeatSpec.every(300)));
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            assertTrue(worker.triggerHeartbeatOnce());
            String backend = worker.backendSessionId();
            assertNotNull(backend);

            assertEquals(0, worker.userSessionCount(), "后台会话不得占用用户会话配额");
            assertEquals(1, facade.getActiveSessionCount(), "但它确实是一个存在的会话");
            assertEquals("worker", facade.agentIdOf(backend),
                    "后台会话也要能被路由与列示");
        }
    }

    /** 关闭门面：各 Agent 的会话管理器一并释放，共享调度池不受影响。 */
    @Test
    void closeReleasesAllManagers() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();
            // 先留住引用：registry.close() 会清空注册表，之后再 get 只会拿到 null
            AgentRuntime alpha = support.register("alpha");
            MultiAgentSessionManager facade = support.facade(defaultAgent);

            facade.close();

            assertTrue(defaultAgent.isClosed());
            assertTrue(alpha.isClosed());
            assertTrue(support.registry.ids().isEmpty(), "注册表应被清空");
            assertFalse(MultiAgentTestSupport.schedulerIsShutdown(),
                    "共享调度池由容器持有，门面无权关闭它");
        }
    }

    // ==================== 辅助 ====================

    private static SessionInfo infoOf(MultiAgentSessionManager facade, String sessionId) {
        return facade.getSessionInfo(sessionId);
    }

    private static boolean hasUserMessage(MultiAgentSessionManager facade, String sessionId,
                                          String content) {
        return facade.getSessionMessages(sessionId).stream()
                .anyMatch(m -> "user".equals(m.role()) && content.equals(m.content()));
    }

    /** 捕获某次调用抛出的异常消息；未抛出时返回 null。 */
    private static String messageOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
