package com.inspirationi.loop.agent;

import com.inspirationi.loop.api.DefaultHmsSessionManager;
import com.inspirationi.loop.api.DefaultPromptManager;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * agent 包各测试共用的最小装配与桩件。
 * <p>
 * 手写匿名 {@link ChatModel} 而非引入 Mockito：既有的
 * {@code api/SessionLifecycleTest} 已经确立了这条路径，保持一致。
 * <p>
 * 调度池是<b>静态共享</b>的：每个 AgentRuntime 各建一个池会让「几十个测试各留几条
 * 线程」变成常态，而管理器的 {@code ownsCleanupScheduler} 语义正好支持共享 ——
 * 传进去的池不会被任何一个实例关闭。
 */
final class AgentTestSupport {

    /** 共享调度池，daemon 线程 —— 不阻止 JVM 退出。 */
    private static final ScheduledExecutorService SHARED_SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "agent-test-scheduler");
                t.setDaemon(true);
                return t;
            });

    private AgentTestSupport() {
    }

    static ScheduledExecutorService sharedScheduler() {
        return SHARED_SCHEDULER;
    }

    /** 固定回复的桩模型。 */
    static ChatModel stubModel(String reply) {
        ChatOptions options = ChatOptions.builder().model("stub-model").build();
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage(reply), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return options;
            }
        };
    }

    /** 记录调用次数的桩模型 —— 用于断言「清洗是否真的调了模型」。 */
    static ChatModel countingModel(AtomicInteger counter, String reply) {
        ChatOptions options = ChatOptions.builder().model("stub-model").build();
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                counter.incrementAndGet();
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage(reply), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return options;
            }
        };
    }

    /** 一个被动的 Agent 定义。 */
    static AgentDefinition passive(String id) {
        return AgentDefinition.builder(id).basePrompt("你是 " + id).build();
    }

    /** 一个主动的 Agent 定义。 */
    static AgentDefinition proactive(String id, HeartbeatSpec heartbeat) {
        return AgentDefinition.builder(id).basePrompt("你是 " + id).proactive(heartbeat).build();
    }

    /** 用默认桩模型装配运行时（无记忆）。 */
    static AgentRuntime runtime(AgentDefinition definition) {
        return runtime(definition, stubModel("ok"), null);
    }

    /** 用指定模型与记忆装配运行时。 */
    static AgentRuntime runtime(AgentDefinition definition, ChatModel model, AgentMemory memory) {
        PermissionSettings settings = new PermissionSettings();
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        ToolRegistry tools = new ToolRegistry();
        ToolContext context = ToolContext.defaultContext();
        AgentWorkQueue queue = new AgentWorkQueue();

        Supplier<String> basePrompt = definition.basePrompt() == null
                ? null : definition::basePrompt;
        Supplier<String> digest = memory == null || !definition.memory().digestInPrompt()
                ? null : memory::digest;

        DefaultHmsSessionManager manager = DefaultHmsSessionManager
                .builder(model, tools, new DefaultPromptManager(null, "global"))
                .permissionEngine(engine)
                .globalToolContext(context)
                .cleanupScheduler(SHARED_SCHEDULER)
                .agentId(definition.id())
                .agentBasePrompt(basePrompt)
                .memoryDigest(digest)
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .build();

        return new AgentRuntime(definition, tools, settings, engine, memory, queue, context, manager);
    }
}
