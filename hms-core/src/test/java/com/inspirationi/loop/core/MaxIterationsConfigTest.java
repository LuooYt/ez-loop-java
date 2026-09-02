package com.inspirationi.loop.core;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单轮迭代上限可配置。
 * <p>
 * 该上限原为硬编码的 {@code private static final int MAX_ITERATIONS = 50}：
 * 长工具链任务撞上限会被截断（返回半成品答案 + 警告标记），而调整它只能改代码
 * 重新打包 —— 项目其余 8 项运行参数（会话超时、会话上限、SSE 超时…）都走
 * {@code hms-core.*} 配置，唯独这个不行。
 */
class MaxIterationsConfigTest {

    /** 永远返回工具调用的模型 —— 只能靠迭代上限截断。 */
    private static ChatModel alwaysCallsTool(AtomicInteger calls) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int n = calls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder()
                                .content("round " + n)
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "c" + n, "function", "Noop", "{}")))
                                .build(),
                        ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    private static ToolRegistry registryWithNoop() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "Noop";
            }

            @Override
            public String description() {
                return "does nothing";
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\",\"properties\":{}}";
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public String execute(Map<String, Object> input, ToolContext context) {
                return "ok";
            }
        });
        return registry;
    }

    private static AgentLoop newLoop(ChatModel model, int maxIterations) {
        return new AgentLoop(model, registryWithNoop(), ToolContext.defaultContext(),
                "sys", new TokenTracker(), maxIterations);
    }

    /** 配置的上限必须真正生效，而非仍走硬编码的 50。 */
    @Test
    void configuredLimitIsHonored() {
        AtomicInteger calls = new AtomicInteger();
        AgentLoop loop = newLoop(alwaysCallsTool(calls), 3);

        String result = loop.run("go");

        assertEquals(3, calls.get(),
                "应在第 3 轮后停止 —— 配置值未生效说明仍在用硬编码的 50");
        assertFalse(result.isBlank(), "达到上限应返回带警告标记的文本");
        assertEquals(3, loop.getMaxIterations(), "getMaxIterations 应回报生效值");
    }

    /** 上调后能跑得更久 —— 确认不是被别的机制（如 50）截断。 */
    @Test
    void higherLimitAllowsMoreIterations() {
        AtomicInteger calls = new AtomicInteger();
        AgentLoop loop = newLoop(alwaysCallsTool(calls), 12);

        loop.run("go");

        assertEquals(12, calls.get(), "上调后应跑满 12 轮");
    }

    /** 不指定时取默认值，保持既有行为。 */
    @Test
    void defaultsToFifty() {
        AgentLoop loop = new AgentLoop(alwaysCallsTool(new AtomicInteger()),
                registryWithNoop(), ToolContext.defaultContext(), "sys", new TokenTracker());

        assertEquals(AgentLoop.DEFAULT_MAX_ITERATIONS, loop.getMaxIterations(),
                "未指定时应取默认值，保持既有行为不变");
        assertEquals(50, AgentLoop.DEFAULT_MAX_ITERATIONS, "默认值应仍是原先的 50");
    }

    /**
     * 非法值回退到默认，而不是让循环一轮都跑不了。
     * <p>
     * {@code maxIterations = 0} 会使 {@code while (iteration < maxIterations)}
     * 直接不进入 —— 一次 API 调用都不发，返回空回复。这种配置失误应当被兜住，
     * 否则表现为「Agent 完全不响应」，极难排查到是配置写错了。
     */
    @Test
    void nonPositiveLimitFallsBackToDefault() {
        for (int illegal : new int[]{0, -1, Integer.MIN_VALUE}) {
            AgentLoop loop = newLoop(alwaysCallsTool(new AtomicInteger()), illegal);
            assertEquals(AgentLoop.DEFAULT_MAX_ITERATIONS, loop.getMaxIterations(),
                    "非法上限 " + illegal + " 应回退到默认值，"
                            + "否则 0 会让循环一轮不跑、静默返回空回复");
        }
    }

    /** 回退生效后循环确实能跑起来 —— 光看 getter 不够。 */
    @Test
    void zeroLimitStillRunsTheLoop() {
        AtomicInteger calls = new AtomicInteger();
        AgentLoop loop = newLoop(alwaysCallsTool(calls), 0);

        loop.run("go");

        assertTrue(calls.get() > 0,
                "上限为 0 时若不回退，循环一轮都不会跑，Agent 表现为完全不响应");
        assertEquals(AgentLoop.DEFAULT_MAX_ITERATIONS, calls.get(),
                "回退后应按默认上限跑满");
    }

    /**
     * 配置必须沿装配链传到会话的 AgentLoop —— 光有 Builder 方法不算接通。
     * <p>
     * {@code newAgentLoop} 是主会话与子 Agent 共用的装配点，因此这一条同时
     *覆盖了两者。
     */
    @Test
    void sessionManagerPropagatesLimitToItsLoops() {
        AtomicInteger calls = new AtomicInteger();
        try (var manager = com.inspirationi.loop.api.DefaultHmsSessionManager.builder(
                        alwaysCallsTool(calls), registryWithNoop(),
                        new com.inspirationi.loop.api.DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxIterations(4)
                .maxSessions(10)
                .build()) {

            String sessionId = manager.createSession("s");
            manager.send(sessionId, "go");

            assertEquals(4, calls.get(),
                    "Builder 上配的迭代上限必须传到会话的 AgentLoop；"
                            + "仍跑 50 轮说明装配链没接上");
        }
    }
}
