package com.inspirationi.loop.api;

import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.AgentTool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子 Agent 的装配约束。
 * <p>
 * 子 Agent 与主会话共用同一套装配流程（独立工具副本、独立 TokenTracker、同一套
 * headless 权限策略），但有一条关键差异：<b>子 Agent 拿不到子 Agent 工厂</b>。
 * 允许递归派发既会让 token 消耗失控，也让「哪个循环在做什么」无从追踪。
 */
class SubAgentWiringTest {

    private static ChatModel stubChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("stub"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ChatOptions.builder().model("claude-sonnet-4-20250514").build();
            }
        };
    }

    private static DefaultHmsSessionManager newManager(ToolContext globalContext) {
        return DefaultHmsSessionManager.builder(
                        stubChatModel(), new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .globalToolContext(globalContext)
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .build();
    }

    /** 取出会话上下文里注册的子 Agent 工厂。 */
    @SuppressWarnings("unchecked")
    private static Function<String, String> agentFactoryOf(DefaultHmsSessionManager manager,
                                                           String sessionId) {
        return (Function<String, String>) manager.getSessionInternal(sessionId)
                .getAgentLoop().getToolContext().get(AgentTool.AGENT_FACTORY_KEY);
    }

    @Test
    void mainSessionGetsASubAgentFactory() {
        try (DefaultHmsSessionManager manager = newManager(ToolContext.defaultContext())) {
            String sessionId = manager.createSession("s");
            assertNotNull(agentFactoryOf(manager, sessionId),
                    "主会话必须注册子 Agent 工厂，否则 Agent 工具不可用");
        }
    }

    @Test
    void subAgentInheritsGlobalSharedState() {
        ToolContext global = ToolContext.defaultContext();
        TaskManager taskManager = new TaskManager();
        global.set("TASK_MANAGER", taskManager);

        try (DefaultHmsSessionManager manager = newManager(global)) {
            String sessionId = manager.createSession("s");
            ToolContext sessionContext = manager.getSessionInternal(sessionId)
                    .getAgentLoop().getToolContext();

            assertSame(taskManager, sessionContext.get("TASK_MANAGER"),
                    "会话上下文应回落到全局共享对象，否则 Task* 等工具全部不可用");
        }
    }

    @Test
    void eachSessionGetsItsOwnToolRegistryCopy() {
        try (DefaultHmsSessionManager manager = newManager(ToolContext.defaultContext())) {
            String s1 = manager.createSession("a");
            String s2 = manager.createSession("b");

            ToolRegistry r1 = manager.getSessionInternal(s1).getAgentLoop().getToolRegistry();
            ToolRegistry r2 = manager.getSessionInternal(s2).getAgentLoop().getToolRegistry();

            assertNotSame(r1, r2, "两级工具隔离：每个会话持有自己的工具副本");
            assertNotNull(manager.getSessionInternal(s1).getAgentLoop()
                            .getToolContext().get("TOOL_REGISTRY"),
                    "会话上下文里应能取到本会话的 TOOL_REGISTRY");
        }
    }

    @Test
    void eachSessionGetsItsOwnTokenTracker() {
        try (DefaultHmsSessionManager manager = newManager(ToolContext.defaultContext())) {
            String s1 = manager.createSession("a");
            String s2 = manager.createSession("b");

            assertNotSame(manager.getSessionInternal(s1).getTokenTracker(),
                    manager.getSessionInternal(s2).getTokenTracker(),
                    "token 统计必须按会话隔离");
            assertNotSame(manager.getSessionInternal(s1).getAgentLoop().getAutoCompactManager(),
                    manager.getSessionInternal(s2).getAgentLoop().getAutoCompactManager(),
                    "压缩器也须按会话隔离 —— 它绑定的是该会话的 TokenTracker");
        }
    }

    /**
     * 核心约束：子 Agent 无法递归派发子 Agent。
     * <p>
     * 直接观察不到子 Agent 的 ToolContext（它由工厂闭包内部创建、不外露），因此换个
     * 角度：让子 Agent 真的去调用 {@code Agent} 工具，断言它拿不到结果。
     * <p>
     * 实际有<b>两层</b>阻断，任一层生效即算达标：
     * <ol>
     *   <li>headless 权限策略 —— Agent 工具默认风险等级为 MEDIUM（{@code Tool.riskLevel()}
     *       按 {@code isReadOnly()} 推断），子 Agent 无 UI 可询问，故被拒绝；</li>
     *   <li>工厂未注册 —— 即使权限放行，{@link AgentTool} 也会因取不到
     *       {@code AGENT_FACTORY_KEY} 而返回错误说明。</li>
     * </ol>
     */
    @Test
    void subAgentCannotSpawnFurtherSubAgents() {
        // 首轮让模型调用 Agent 工具，次轮把工具结果原样回述，便于断言
        ChatModel recursiveModel = new ChatModel() {
            private int calls;

            @Override
            public ChatResponse call(Prompt prompt) {
                if (calls++ == 0) {
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder()
                                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                                            "c1", "function", "Agent",
                                            "{\"prompt\":\"nested\",\"description\":\"n\"}")))
                                    .build(),
                            ChatGenerationMetadata.NULL)));
                }
                // 把上一轮的工具结果回述出来
                String lastToolText = prompt.getInstructions().stream()
                        .filter(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage)
                        .map(m -> ((org.springframework.ai.chat.messages.ToolResponseMessage) m)
                                .getResponses().getFirst().responseData())
                        .reduce((a, b) -> b)
                        .orElse("");
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage(lastToolText), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ChatOptions.builder().model("claude-sonnet-4-20250514").build();
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new AgentTool());

        try (DefaultHmsSessionManager manager = DefaultHmsSessionManager.builder(
                        recursiveModel, registry, new DefaultPromptManager(null, "g"))
                .globalToolContext(ToolContext.defaultContext())
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .build()) {
            String sessionId = manager.createSession("s");
            Function<String, String> factory = agentFactoryOf(manager, sessionId);
            assertNotNull(factory, "主会话必须有工厂");

            // 跑一个子 Agent，它会尝试再派一个子 Agent
            String result = factory.apply("try to spawn another agent");

            assertNotNull(result);
            // 权限拒绝（第一层）或工厂未配置（第二层）都算阻断成功；
            // 唯一不可接受的是子 Agent 真的又派出了一个循环。
            boolean blocked = result.contains("权限被拒绝")
                    || result.contains("Permission denied")
                    || result.contains("not configured");
            assertTrue(blocked,
                    "子 Agent 再派子 Agent 必须被阻断（权限拒绝或工厂未配置），实际：" + result);
        }
    }

    @Test
    void nullGlobalContextDoesNotBreakSubAgentWiring() {
        // 未设置 globalToolContext 时上下文退化为无父级，装配仍应完成
        try (DefaultHmsSessionManager manager = DefaultHmsSessionManager.builder(
                        stubChatModel(), new ToolRegistry(), new DefaultPromptManager(null, "g"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .build()) {
            String sessionId = manager.createSession("s");
            assertNotNull(agentFactoryOf(manager, sessionId));
            assertNull(manager.getSessionInternal(sessionId).getAgentLoop()
                            .getToolContext().get("TASK_MANAGER"),
                    "无全局上下文时共享对象取不到 —— 这是预期，但不应抛异常");
        }
    }
}
