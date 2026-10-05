package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentDefinition;
import com.inspirationi.loop.agent.AgentHeartbeatScheduler;
import com.inspirationi.loop.agent.AgentRegistry;
import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.ListAgentsTool;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * api 包多智能体集成测试的共用装配。
 * <p>
 * 刻意<b>复刻 {@code ApiAutoConfiguration} 的装配顺序</b>（全局依赖 → 工厂 → 注册中心 →
 * 预注册默认 Agent），而不是自己拼一套更省事的等价物：测试要能发现「装配顺序错了」
 * 这类问题，模拟一套不同的装配就发现不了。
 */
final class MultiAgentTestSupport implements AutoCloseable {

    static final String GLOBAL_PROMPT = "全局系统提示词（测试用）";
    static final String DEFAULT_AGENT_ID = AgentRegistry.DEFAULT_AGENT_ID;

    /** 共享调度池，daemon 线程。与管理器的 {@code ownsCleanupScheduler} 语义配套：不会被误关。 */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "multi-agent-test-scheduler");
                t.setDaemon(true);
                return t;
            });

    final ChatModel model;
    final ToolRegistry globalTools = new ToolRegistry();
    final PermissionSettings globalSettings = new PermissionSettings();
    final PermissionRuleEngine globalEngine;
    final ToolContext globalContext;
    final PromptManager promptManager;
    final AgentHeartbeatScheduler heartbeatScheduler;
    final AgentRegistry registry;
    final AgentRuntimeFactory factory;

    MultiAgentTestSupport() {
        this(new RecordingModel());
    }

    MultiAgentTestSupport(ChatModel model) {
        this.model = model;
        this.globalEngine = new PermissionRuleEngine(globalSettings);
        this.globalContext = ToolContext.defaultContext();
        this.promptManager = new DefaultPromptManager(null, GLOBAL_PROMPT);
        this.heartbeatScheduler = new AgentHeartbeatScheduler(SCHEDULER);
        this.registry = new AgentRegistry(heartbeatScheduler);
        this.factory = new AgentRuntimeFactory(config(), registry);

        // 与 ApiAutoConfiguration 一致：全局上下文挂上注册表，供 ListAgents / 委派工具读取
        globalContext.set(ListAgentsTool.AGENT_REGISTRY_KEY, registry);
    }

    /** 进程级依赖 —— 与自动配置持有的那一份同构。 */
    private AgentRuntimeConfig config() {
        return new AgentRuntimeConfig(
                model,
                globalTools,
                globalEngine,
                globalContext,
                promptManager,
                null,                 // tokenPricing：测试不关心计费
                SCHEDULER,
                10,                   // defaultMaxSessions
                50,                   // defaultMaxIterations
                200_000L,             // contextWindow
                40_000L,              // reservedTokens
                300L,                 // userResponseTimeoutSeconds
                3600L,                // cleanupIntervalSeconds
                1800L);               // defaultIdleTimeoutSeconds
    }

    /** 装配并注册一个 Agent；{@code customizer} 用于定制定义。 */
    AgentRuntime register(String agentId, Consumer<AgentDefinition.Builder> customizer) {
        AgentDefinition.Builder builder = AgentDefinition.builder(agentId);
        if (customizer != null) {
            customizer.accept(builder);
        }
        return registry.register(factory.create(builder.build()));
    }

    /** 装配并注册一个取默认定义的 Agent。 */
    AgentRuntime register(String agentId) {
        return register(agentId, null);
    }

    /**
     * 预注册默认 Agent —— 复刻自动配置的做法：复用全局 {@link PermissionSettings} Bean、
     * 全部工具、无记忆、被动、可委派。
     */
    AgentRuntime registerDefaultAgent() {
        return register(DEFAULT_AGENT_ID, builder -> builder
                .basePrompt(GLOBAL_PROMPT)
                .permissionSettings(globalSettings)
                .toolSelect(com.inspirationi.loop.agent.ToolSelect.all())
                .delegatable(true));
    }

    /** 按自动配置的方式构造门面。 */
    MultiAgentSessionManager facade(AgentRuntime defaultAgent) {
        return new MultiAgentSessionManager(registry, defaultAgent.sessionManager());
    }

    @Override
    public void close() {
        registry.close();
        heartbeatScheduler.close();
    }

    /** 共享调度池是否被关闭 —— 用于断言「释放资源时不得误关别人的线程池」。 */
    static boolean schedulerIsShutdown() {
        return SCHEDULER.isShutdown();
    }

    // ==================== 测试桩 ====================

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

    /**
     * 记录每次调用收到的 system / user 文本的桩模型。
     * <p>
     * 提示词隔离与委派效果都只能通过「模型到底看到了什么」来验证 —— 会话对象本身
     * 在一次性委派里跑完即销毁，留不下别的痕迹。
     */
    static final class RecordingModel implements ChatModel {

        private final ChatOptions options = ChatOptions.builder().model("stub-model").build();
        private final List<String> systemPrompts = Collections.synchronizedList(new ArrayList<>());
        private final List<String> userMessages = Collections.synchronizedList(new ArrayList<>());

        private volatile CountDownLatch blockLatch;
        private volatile CountDownLatch enteredLatch;

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> instructions = prompt.getInstructions();
            for (Message message : instructions) {
                if (message instanceof SystemMessage sm) {
                    systemPrompts.add(sm.getText());
                } else if (message instanceof UserMessage um) {
                    userMessages.add(um.getText());
                }
            }
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
                    new AssistantMessage("已收到"), ChatGenerationMetadata.NULL)));
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }

        /**
         * 让后续每次调用都阻塞，直到 {@link #release()}。
         * <p>
         * 用途是把「委派的一次性会话正在执行」这一瞬间<b>定格</b>下来：
         * 该会话跑完即销毁，不卡住就永远看不到它的工具集。
         */
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

        /** 最近一次调用看到的 system prompt。 */
        String lastSystemPrompt() {
            synchronized (systemPrompts) {
                return systemPrompts.isEmpty() ? null : systemPrompts.get(systemPrompts.size() - 1);
            }
        }

        /** 最近一次调用看到的用户消息。 */
        String lastUserMessage() {
            synchronized (userMessages) {
                return userMessages.isEmpty() ? null : userMessages.get(userMessages.size() - 1);
            }
        }

        int callCount() {
            synchronized (systemPrompts) {
                return systemPrompts.size();
            }
        }
    }
}
