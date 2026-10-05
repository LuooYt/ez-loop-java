package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.agent.HeartbeatSpec;
import com.inspirationi.loop.agent.MemorySpec;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.impl.AgentMemoryRecallTool;
import com.inspirationi.loop.tool.impl.AgentMemoryRememberTool;
import com.inspirationi.loop.tool.impl.EnqueueWorkTool;
import com.inspirationi.loop.tool.impl.ListAgentsTool;
import com.inspirationi.loop.tool.impl.ListWorkTool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五项新工具的行为 —— 全部直接调用 {@code execute}，用<b>真实装配出来的</b>
 * Agent 上下文（{@code AgentRuntimeFactory} 写入的那些键），而不是手搭的上下文。
 * <p>
 * 上下文键是这套能力的隐式契约：工具与写入方各写一个字符串常量，改一处漏一处就会
 * 静默失效（工具只会返回「未配置」）。用真实装配的上下文测，这类失配才会被抓住。
 */
class AgentCapabilityToolsTest {

    // ==================== ListAgents ====================

    @Test
    void listAgentsMarksCurrentDelegatableAndActivation() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime alpha = support.register("alpha",
                    builder -> builder.description("负责数据处理"));
            support.register("beta", builder -> builder.delegatable(false));
            support.register("worker", builder -> builder.proactive(HeartbeatSpec.every(300)));

            String output = new ListAgentsTool().execute(Map.of(), alpha.agentToolContext());

            assertTrue(output.contains("- alpha [当前]"), "应标出当前会话所属的智能体：" + output);
            assertTrue(output.contains("负责数据处理"), "应带上用途说明供模型选择：" + output);
            assertTrue(output.contains("beta [不可委派]"), "不可委派的应标出：" + output);
            assertTrue(output.contains("worker [主动]"), "主动 Agent 应标出：" + output);
            assertTrue(output.contains("alpha [当前] [被动]") || output.contains("[当前] [被动]"),
                    "被动 Agent 应标出：" + output);
        }
    }

    @Test
    void listAgentsWithoutRegistryReturnsReadableError() {
        String output = new ListAgentsTool().execute(Map.of(), ToolContext.defaultContext());
        assertTrue(output.contains("未配置智能体注册表"), "实际：" + output);
    }

    // ==================== RecallAgentMemory ====================

    @Test
    void recallReturnsMatchingEntries() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime agent = support.register("memo",
                    builder -> builder.memory(MemorySpec.simple()));
            agent.memory().remember("用户偏好用 Java 写后端", "api", List.of("preference"));
            agent.memory().remember("部署在阿里云 ECS", "api", List.of("infra"));

            String output = new AgentMemoryRecallTool().execute(
                    Map.of("query", "java"), agent.agentToolContext());

            assertTrue(output.contains("用户偏好用 Java 写后端"), "实际：" + output);
            assertFalse(output.contains("阿里云"), "不匹配的条目不应当被带出");
            assertTrue(output.contains("非指令"),
                    "检索结果必须再次声明它是笔记而非指令：" + output);
        }
    }

    @Test
    void recallTreatsBlankQueryAsListRecent() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime agent = support.register("memo",
                    builder -> builder.memory(MemorySpec.simple()));
            agent.memory().remember("第一条", "api", List.of());
            agent.memory().remember("第二条", "api", List.of());

            String output = new AgentMemoryRecallTool().execute(Map.of(), agent.agentToolContext());

            assertTrue(output.contains("第一条") && output.contains("第二条"), "实际：" + output);
        }
    }

    @Test
    void recallOnEmptyMemorySaysSo() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime agent = support.register("memo",
                    builder -> builder.memory(MemorySpec.simple()));

            String output = new AgentMemoryRecallTool().execute(
                    Map.of("query", "任何"), agent.agentToolContext());

            assertTrue(output.contains("没有匹配"), "实际：" + output);
        }
    }

    /** 模型可能给出任意 limit —— 上限必须由工具兜住，而不是把整库灌进上下文。 */
    @Test
    void recallLimitIsClamped() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime agent = support.register("memo",
                    builder -> builder.memory(MemorySpec.simple()));
            for (int i = 0; i < 60; i++) {
                agent.memory().remember("记忆条目 " + i, "api", List.of());
            }

            String output = new AgentMemoryRecallTool().execute(
                    Map.of("limit", 9999), agent.agentToolContext());

            long listed = output.lines().filter(line -> line.startsWith("- [")).count();
            assertEquals(50, listed, "单次检索上限 50 条，实际返回 " + listed + " 条");
        }
    }

    @Test
    void recallWithoutMemoryReturnsNotice() {
        String output = new AgentMemoryRecallTool().execute(Map.of(), ToolContext.defaultContext());
        assertTrue(output.contains("未启用记忆"), "实际：" + output);
    }

    // ==================== RememberAgentMemory ====================

    @Test
    void rememberWritesIntoAgentMemory() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime agent = support.register("memo",
                    builder -> builder.memory(MemorySpec.simple()));

            String output = new AgentMemoryRememberTool().execute(
                    Map.of("content", "项目代号 Aegis", "tags", List.of("project")),
                    agent.agentToolContext());

            assertEquals(1, agent.memory().size());
            assertEquals("项目代号 Aegis", agent.memory().entries().get(0).content());
            assertEquals(List.of("project"), agent.memory().entries().get(0).tags());
            assertTrue(output.contains("已记住"), "实际：" + output);
        }
    }

    @Test
    void rememberRequiresContent() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime agent = support.register("memo",
                    builder -> builder.memory(MemorySpec.simple()));

            String output = new AgentMemoryRememberTool().execute(
                    Map.of("content", "   "), agent.agentToolContext());

            assertTrue(output.contains("'content' is required"), "实际：" + output);
            assertEquals(0, agent.memory().size());
        }
    }

    @Test
    void rememberWithoutMemoryReturnsNotice() {
        String output = new AgentMemoryRememberTool().execute(
                Map.of("content", "随便记点什么"), ToolContext.defaultContext());
        assertTrue(output.contains("未启用记忆"), "实际：" + output);
    }

    /**
     * 安全契约固定下来：写工具是 <b>非只读 + 低风险</b>。
     * <p>
     * 低风险意味着在默认权限模式下自动放行，即模型可以自主积累记忆 —— 这是为满足
     * 「自主管理记忆」而显式接受的权衡（记忆进 system prompt，属 prompt-injection 面）。
     * 若哪天有人把它改成 MEDIUM/HIGH，模型将无法自主写入而整套自主能力失效；
     * 改成 READ_ONLY 又会让审计视图失真。两种改动都应在这里被拦下。
     */
    @Test
    void rememberToolKeepsItsDeclaredRiskProfile() {
        AgentMemoryRememberTool tool = new AgentMemoryRememberTool();

        assertFalse(tool.isReadOnly(), "它确实改变状态，不应声明为只读");
        assertEquals(Tool.RiskLevel.LOW, tool.riskLevel(),
                "低风险是「默认放行、模型可自主写入」的前提，改动需同步更新安全权衡说明");
        assertEquals(AgentMemoryRememberTool.NAME, tool.name());
    }

    // ==================== EnqueueWork / ListWork ====================

    @Test
    void enqueueAddsItemToAgentQueue() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime worker = support.register("worker",
                    builder -> builder.proactive(HeartbeatSpec.every(300)));

            String output = new EnqueueWorkTool().execute(
                    Map.of("prompt", "明天复查构建结果", "source", "follow-up"),
                    worker.agentToolContext());

            assertEquals(1, worker.workQueue().size());
            assertEquals("明天复查构建结果", worker.workQueue().peek().prompt());
            assertEquals("follow-up", worker.workQueue().peek().source());
            assertTrue(output.contains("已排入待办队列"), "实际：" + output);
        }
    }

    @Test
    void enqueueRequiresPrompt() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime worker = support.register("worker",
                    builder -> builder.proactive(HeartbeatSpec.every(300)));

            String output = new EnqueueWorkTool().execute(
                    Map.of("prompt", ""), worker.agentToolContext());

            assertTrue(output.contains("'prompt' is required"), "实际：" + output);
            assertTrue(worker.workQueue().isEmpty());
        }
    }

    @Test
    void enqueueWithoutQueueReturnsNotice() {
        String output = new EnqueueWorkTool().execute(
                Map.of("prompt", "随便"), ToolContext.defaultContext());
        assertTrue(output.contains("没有工作队列"), "实际：" + output);
    }

    @Test
    void listWorkShowsPendingItems() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime worker = support.register("worker",
                    builder -> builder.proactive(HeartbeatSpec.every(300)));
            worker.workQueue().enqueue("第一件待办", "external");

            String output = new ListWorkTool().execute(Map.of(), worker.agentToolContext());

            assertTrue(output.contains("共 1 项"), "实际：" + output);
            assertTrue(output.contains("第一件待办"), "实际：" + output);
        }
    }

    @Test
    void listWorkOnEmptyQueueSaysIntrospection() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime worker = support.register("worker",
                    builder -> builder.proactive(HeartbeatSpec.every(300)));

            String output = new ListWorkTool().execute(Map.of(), worker.agentToolContext());

            assertTrue(output.contains("待办队列为空"), "实际：" + output);
            assertTrue(output.contains("自省"), "应告知下一次心跳会走自省流程：" + output);
        }
    }

    /** 队列可能积压很多 —— 清单必须有上限，否则一次查询就能吃掉大半个上下文。 */
    @Test
    void listWorkCapsListedItems() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime worker = support.register("worker",
                    builder -> builder.proactive(HeartbeatSpec.every(300)));
            for (int i = 0; i < 25; i++) {
                worker.workQueue().enqueue("待办 " + i, "external");
            }

            String output = new ListWorkTool().execute(Map.of(), worker.agentToolContext());

            long listed = output.lines().filter(line -> line.startsWith("- [")).count();
            assertEquals(20, listed, "最多列出 20 项，实际 " + listed);
            assertTrue(output.contains("其余 5 项未列出"),
                    "被截断时应明确告知还有多少项：" + output);
            assertEquals(25, worker.workQueue().size(), "列示不得消费队列");
        }
    }

    /** 工具名与提示词里的名字一致 —— 名字对不上时模型会调用不存在的工具。 */
    @Test
    void toolNamesAreStable() {
        assertEquals("ListAgents", new ListAgentsTool().name());
        assertEquals("RecallAgentMemory", new AgentMemoryRecallTool().name());
        assertEquals("EnqueueWork", new EnqueueWorkTool().name());
        assertEquals("ListWork", new ListWorkTool().name());
    }

    /** 三个只读工具应如实声明为只读 —— 权限引擎据此放行，声明错了会平白弹确认。 */
    @Test
    void readOnlyToolsDeclareThemselvesReadOnly() {
        assertTrue(new ListAgentsTool().isReadOnly());
        assertTrue(new AgentMemoryRecallTool().isReadOnly());
        assertTrue(new ListWorkTool().isReadOnly());
        assertFalse(new EnqueueWorkTool().isReadOnly());
    }
}
