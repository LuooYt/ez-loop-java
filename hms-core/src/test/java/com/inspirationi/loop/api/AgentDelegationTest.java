package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.tool.impl.AgentMemoryRememberTool;
import com.inspirationi.loop.tool.impl.AgentTool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨 Agent 委派 —— AI 自主能力的出口，也是权限与递归两道边界所在。
 * <p>
 * 复用既有的 {@code Agent} 工具而不是新增一个：模型只需要认得「把活交出去」这一个
 * 入口。不传 {@code agent} 参数时走原本的「同配置子 Agent」，传了才跨 Agent。
 */
class AgentDelegationTest {

    /** 委派意味着目标 Agent 用自己的提示词、工具与权限执行。 */
    @Test
    void delegatesToNamedAgentUsingThatAgentsOwnPrompt() {
        MultiAgentTestSupport.RecordingModel model = new MultiAgentTestSupport.RecordingModel();
        try (MultiAgentTestSupport support = new MultiAgentTestSupport(model)) {
            AgentRuntime alpha = support.register("alpha", builder -> builder.basePrompt("我是 alpha"));
            support.register("beta", builder -> builder.basePrompt("我是 beta，专门处理数据统计"));

            String result = new AgentTool().execute(
                    Map.of("prompt", "统计上个月的订单量", "agent", "beta"),
                    alpha.agentToolContext());

            assertEquals("已收到", result, "委派结果应原样回到调用方");
            String systemPrompt = model.lastSystemPrompt();
            assertTrue(systemPrompt.contains("我是 beta，专门处理数据统计"),
                    "目标 Agent 必须用自己的基座提示词执行，实际：" + systemPrompt);
            assertFalse(systemPrompt.contains("我是 alpha"),
                    "委派不得沿用调用方的提示词 —— 那等于换个壳继续自己做");
        }
    }

    /** 不传 {@code agent} 参数时保持既有语义：走「同配置子 Agent」路径。 */
    @Test
    void withoutAgentParamFallsBackToSubAgentPath() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime alpha = support.register("alpha");
            support.register("beta");

            String result = new AgentTool().execute(
                    Map.of("prompt", "随便做点什么"),
                    alpha.agentToolContext());

            // Agent 级上下文里没有子 Agent 工厂（工厂只装在主会话的上下文上）
            assertTrue(result.contains("Sub-agent capability is not configured"),
                    "不传 agent 时应走原子 Agent 分支，实际：" + result);
        }
    }

    @Test
    void selfDelegationIsRejected() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime alpha = support.register("alpha");

            String result = new AgentTool().execute(
                    Map.of("prompt", "帮我把这个做完", "agent", "alpha"),
                    alpha.agentToolContext());

            assertTrue(result.contains("不能把任务委派给自己"),
                    "自我委派会立刻绕成死循环，必须直接拒绝。实际：" + result);
        }
    }

    @Test
    void unknownAgentReturnsReadableError() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime alpha = support.register("alpha");

            String result = new AgentTool().execute(
                    Map.of("prompt", "做点事", "agent", "查无此人"),
                    alpha.agentToolContext());

            assertTrue(result.contains("智能体不存在"),
                    "错误要以可读文本回给模型，让它能改派或自己做。实际：" + result);
            assertFalse(result.isBlank());
        }
    }

    @Test
    void nonDelegatableAgentIsRejected() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime alpha = support.register("alpha");
            support.register("private-one", builder -> builder.delegatable(false));

            String result = new AgentTool().execute(
                    Map.of("prompt", "做点事", "agent", "private-one"),
                    alpha.agentToolContext());

            assertTrue(result.contains("不接受委派"), "实际：" + result);
        }
    }

    /**
     * <b>委派深度恒为 1 层</b> —— 本能力最关键的一条护栏。
     * <p>
     * 只靠「不注册子 Agent 工厂」挡不住递归：那只管不传 {@code agent} 参数的路径，
     * 而跨 Agent 委派读的是上下文里的 {@code AGENT_REGISTRY}，它在被委派者的会话里
     * 依然可达。不移除 {@code Agent} 工具的话，A→B→A 可以无限循环，每轮都在烧 token
     * 且不会自然终止。
     * <p>
     * 断言时机：一次性会话跑完即销毁，所以必须让模型停在调用里，把这一瞬定格下来
     * 才看得到它的工具集。
     */
    @Test
    void delegatedSessionCannotDelegateFurther() throws Exception {
        MultiAgentTestSupport.RecordingModel model = new MultiAgentTestSupport.RecordingModel();
        try (MultiAgentTestSupport support = new MultiAgentTestSupport(model)) {
            AgentRuntime beta = support.register("beta", builder -> builder.basePrompt("我是 beta"));

            model.block();
            Thread runner = new Thread(() -> beta.runOnce("一件委派来的活"), "delegated-runner");
            runner.setDaemon(true);
            runner.start();
            assertTrue(model.awaitEntered(5, TimeUnit.SECONDS), "模型应已进入阻塞调用");

            try {
                List<SessionInfo> sessions = beta.sessionManager().listSessions();
                assertEquals(1, sessions.size(), "委派期间恰好存在一个一次性会话");

                Set<String> tools = beta.sessionManager()
                        .getSessionToolRegistry(sessions.get(0).sessionId())
                        .getToolNames();

                assertFalse(tools.contains(AgentTool.NAME),
                        "委派会话不得再持有委派入口，否则 A→B→A 可以无限递归。" + tools);
                assertFalse(tools.contains(AgentMemoryRememberTool.NAME),
                        "委派会话的记忆只读不写 —— 否则它成了一条绕过调用方审查的记忆写入通道");
                assertTrue(tools.contains("ListAgents"),
                        "只读的发现工具应保留：被委派者需要知道自己处在什么位置");
            } finally {
                model.release();
                runner.join(5000);
            }
        }
    }

    /** 委派跑完即销毁，不留历史 —— 否则每次委派都会在目标 Agent 上堆一个空会话。 */
    @Test
    void ephemeralSessionIsDestroyedAfterDelegation() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime beta = support.register("beta", builder -> builder.basePrompt("我是 beta"));

            assertEquals("已收到", beta.runOnce("一件委派来的活"));

            assertEquals(0, beta.sessionManager().getActiveSessionCount(),
                    "一次性会话必须在委派结束后销毁");
            assertTrue(beta.sessionManager().listSessions().isEmpty());
        }
    }

    /** 已下线的 Agent 不再可被委派 —— 注册表里查不到即视为不存在。 */
    @Test
    void unregisteredAgentCannotBeDelegatedTo() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime alpha = support.register("alpha");
            support.register("beta");
            support.registry.unregister("beta", true);

            String result = new AgentTool().execute(
                    Map.of("prompt", "做点事", "agent", "beta"),
                    alpha.agentToolContext());

            assertTrue(result.contains("智能体不存在"), "实际：" + result);
        }
    }

    /** 缺少 registry 时（单 Agent 部署）给出可读错误，而不是 NPE。 */
    @Test
    void delegationWithoutRegistryReturnsReadableError() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime alpha = support.register("alpha");

            // 模拟未挂注册表的上下文
            var bareContext = com.inspirationi.loop.tool.ToolContext.defaultContext();
            bareContext.set(com.inspirationi.loop.tool.impl.ListAgentsTool.AGENT_ID_KEY, "alpha");

            String result = new AgentTool().execute(
                    Map.of("prompt", "做点事", "agent", "beta"),
                    bareContext);

            assertTrue(result.contains("Multi-agent capability is not configured"), "实际：" + result);
        }
    }
}
