package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.ActivationMode;
import com.inspirationi.loop.agent.AgentDefinition;
import com.inspirationi.loop.agent.AgentHeartbeatScheduler;
import com.inspirationi.loop.agent.AgentRegistry;
import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.agent.MemorySpec;
import com.inspirationi.loop.agent.ToolSelect;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.telemetry.TokenPricing;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.ListAgentsTool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * API 模式自动配置 —— 注册 SDK 对外接口 Bean。
 * <p>
 * 主要 Bean：
 * <ul>
 *   <li>{@link HmsSessionManager} — 多智能体会话门面（同时也是
 *       {@link AgentSessionManager}）</li>
 *   <li>{@link PromptManager} — 两级提示词管理</li>
 *   <li>{@link ToolManager} — 两级工具管理</li>
 *   <li>{@link AgentRegistry} — 智能体注册中心（运行时插拔）</li>
 * </ul>
 * 依赖 {@link com.inspirationi.loop.config.ToolConfiguration} 产出的
 * ToolRegistry，故声明在其之后装配。
 */
@AutoConfiguration(after = com.inspirationi.loop.config.ToolConfiguration.class)
public class ApiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ApiAutoConfiguration.class);

    /**
     * 共享调度线程池 —— 空闲会话清理与主动 Agent 心跳共用。
     * <p>
     * <b>为什么必须共享</b>：每个 Agent 各带一个 {@code DefaultHmsSessionManager}，
     * 若各建各的池，「10 个 Agent」就是 10 条常驻线程，而它们绝大多数时间什么都不做。
     * 共享之后由 {@code ownsCleanupScheduler} 保证谁也不会去关别人的池。
     * <p>
     * 用虚拟线程：这些任务要么在等模型响应、要么在等锁，几乎不占 CPU，正是虚拟线程
     * 的适用场景。
     * <p>
     * 集成方声明自己的 {@link ScheduledExecutorService} Bean 即可接管（含线程数、
     * 命名与关闭策略）。
     */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(ScheduledExecutorService.class)
    public ScheduledExecutorService sessionCleanupScheduler() {
        return Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("hms-scheduler-", 0).factory());
    }

    /**
     * 主动 Agent 的心跳调度器。
     * <p>
     * 只登记/取消任务，不拥有线程池 —— 见 {@link AgentHeartbeatScheduler} 的类说明。
     */
    @Bean
    @ConditionalOnMissingBean(AgentHeartbeatScheduler.class)
    public AgentHeartbeatScheduler agentHeartbeatScheduler(ScheduledExecutorService sessionCleanupScheduler) {
        return new AgentHeartbeatScheduler(sessionCleanupScheduler);
    }

    /**
     * 智能体注册中心 —— 集成方通过它注册/下线 Agent。
     * <p>
     * 容器关闭时 {@code AgentRegistry.close()} 会取消全部心跳并释放各运行时；
     * 会话本身由门面的 {@code close()} 负责（它也是容器管理的 Bean）。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentRegistry.class)
    public AgentRegistry agentRegistry(AgentHeartbeatScheduler agentHeartbeatScheduler) {
        return new AgentRegistry(agentHeartbeatScheduler);
    }

    /**
     * 装配一个 {@code AgentRuntime} 所需的进程级依赖与默认值。
     * <p>
     * 这里的 {@code @Value} 配置项与改造前 {@code hmsSessionManager} 上的那一组完全一致
     * （同样的键名与默认值），只是整体挪到了配置对象上 —— 每个 Agent 的会话管理器都从
     * 它取默认值，因此既有部署的配置项一个字都不用改。
     */
    @Bean
    @ConditionalOnMissingBean(AgentRuntimeConfig.class)
    public AgentRuntimeConfig agentRuntimeConfig(
            ChatModel activeChatModel, ToolRegistry toolRegistry,
            PermissionRuleEngine permissionRuleEngine, ToolContext toolContext,
            @Lazy PromptManager promptManager,
            ScheduledExecutorService sessionCleanupScheduler,
            ObjectProvider<TokenPricing> tokenPricingProvider,
            @Value("${hms-core.session.idle-timeout-minutes:30}") long idleTimeoutMinutes,
            @Value("${hms-core.session.cleanup-interval-minutes:5}") long cleanupIntervalMinutes,
            @Value("${hms-core.user-response-timeout-seconds:300}") long userResponseTimeoutSeconds,
            @Value("${hms-core.session.max-sessions:1000}") int maxSessions,
            @Value("${hms-core.max-iterations:50}") int maxIterations,
            @Value("${hms-core.context-window:200000}") long contextWindow,
            @Value("${hms-core.reserved-tokens:20000}") long reservedTokens) {
        return new AgentRuntimeConfig(
                activeChatModel, toolRegistry, permissionRuleEngine, toolContext, promptManager,
                tokenPricingProvider.getIfAvailable(), sessionCleanupScheduler,
                maxSessions, maxIterations, contextWindow, reservedTokens,
                userResponseTimeoutSeconds, cleanupIntervalMinutes * 60, idleTimeoutMinutes * 60);
    }

    /** 按定义装配运行时 —— 注册 Agent 时使用。 */
    @Bean
    @ConditionalOnMissingBean(AgentRuntimeFactory.class)
    public AgentRuntimeFactory agentRuntimeFactory(AgentRuntimeConfig agentRuntimeConfig,
                                                   AgentRegistry agentRegistry) {
        return new AgentRuntimeFactory(agentRuntimeConfig, agentRegistry);
    }

    /**
     * 注册会话管理器 Bean —— 多智能体门面，并预注册<b>默认 Agent</b>。
     * <p>
     * <b>默认 Agent 是与改造前逐字节等价的那个 Agent</b>：工具集为全部工具、
     * 权限设置复用全局 Bean、记忆关闭、被动模式、基座提示词未声明（于是 system prompt
     * 仍按「全局提示词 + 分隔符 + 会话提示词」拼接）。因此 demo-app 的 5 个 Controller
     * 与前端契约零改动就能跑在门面之上，而多智能体能力同时可用。
     * <p>
     * <b>权限设置必须复用全局 Bean</b>：{@code PermissionController} 改的是那个单例，
     * 若默认 Agent 另建私有实例，「在界面上把模式改成 STRICT」就只会影响 API 返回值，
     * 不影响实际放行判断。
     * <p>
     * 返回类型是 {@link MultiAgentSessionManager}，因此该 Bean 同时可按
     * {@link HmsSessionManager} 与 {@link AgentSessionManager} 注入。
     * <p>
     * 用 {@link Lazy} 注入 PromptManager，打破「PromptManager ↔ HmsSessionManager」
     * 的构造器循环依赖：两个 Bean 都持对方的懒加载代理，直到真正调用时才解析。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(HmsSessionManager.class)
    public MultiAgentSessionManager hmsSessionManager(
            AgentRuntimeFactory agentRuntimeFactory,
            AgentRegistry agentRegistry,
            PermissionSettings permissionSettings,
            ToolContext toolContext) {
        log.info("Creating MultiAgentSessionManager bean");

        AgentDefinition defaultAgent = AgentDefinition.builder(AgentRegistry.DEFAULT_AGENT_ID)
                .name("默认智能体")
                .description("通用助手：拥有全部工具与全局权限设置，等价于单 Agent 模式下的原有行为")
                .toolSelect(ToolSelect.all())
                .permissionSettings(permissionSettings)
                .memory(MemorySpec.disabled())
                .activationMode(ActivationMode.PASSIVE)
                .delegatable(true)
                .build();

        AgentRuntime defaultRuntime =
                agentRegistry.register(agentRuntimeFactory.create(defaultAgent));

        // 全局上下文也放一份注册表：会话上下文是「Agent 上下文 → 全局上下文」两级回落，
        // 这里放一份可以让不经过 Agent 级上下文的历史路径同样读到它。
        toolContext.set(ListAgentsTool.AGENT_REGISTRY_KEY, agentRegistry);

        return new MultiAgentSessionManager(agentRegistry, defaultRuntime.sessionManager());
    }

    /**
     * 注册默认的提示词管理器 Bean（可被自定义实现覆盖）。
     * <p>
     * 注入 {@link HmsSessionManager}（懒加载）以读取会话级提示词；多智能体模式下它是
     * 门面，按 sessionId 路由到正确的 Agent。
     *
     * @param sessionManager 会话管理器（懒加载代理，用于读取会话级提示词）
     * @param globalPrompt   全局提示词 Bean
     * @return 两级提示词管理器的默认实现
     */
    @Bean
    @ConditionalOnMissingBean(PromptManager.class)
    public PromptManager promptManager(@Lazy HmsSessionManager sessionManager, String globalPrompt) {
        log.info("Creating DefaultPromptManager bean");
        return new DefaultPromptManager(sessionManager, globalPrompt);
    }

    /**
     * 注册默认的工具管理器 Bean（可被自定义实现覆盖）。
     *
     * @param toolRegistry    全局工具注册中心
     * @param sessionManager  会话管理器（用于读取会话级工具）
     * @return 两级工具管理器的默认实现
     */
    @Bean
    @ConditionalOnMissingBean(ToolManager.class)
    public ToolManager toolManager(ToolRegistry toolRegistry, HmsSessionManager sessionManager) {
        log.info("Creating DefaultToolManager bean");
        return new DefaultToolManager(toolRegistry, sessionManager);
    }
}
