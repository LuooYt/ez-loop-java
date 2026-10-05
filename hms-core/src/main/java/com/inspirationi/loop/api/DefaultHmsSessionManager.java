package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentRegistry;
import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.core.HookManager;
import com.inspirationi.loop.core.TokenTracker;
import com.inspirationi.loop.core.compact.AutoCompactManager;
import com.inspirationi.loop.core.compact.CompactionResult;
import com.inspirationi.loop.permission.DenialTracker;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.telemetry.MetricsCollector;
import com.inspirationi.loop.telemetry.TokenPricing;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.AgentMemoryRememberTool;
import com.inspirationi.loop.tool.impl.AgentTool;
import com.inspirationi.loop.tool.impl.AskUserQuestionTool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link HmsSessionManager} 的默认实现。
 * <p>
 * 两级提示词：GlobalPrompt（Bean 注入）+ SessionPrompt（会话独立）。
 * 两级工具：GlobalToolRegistry（Bean 注入）+ 会话独立副本。
 * <p>
 * 会话生命周期：ACTIVE → PAUSE → RESUME → DESTROY。
 */
public class DefaultHmsSessionManager implements HmsSessionManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultHmsSessionManager.class);

    /** 默认会话提示词（两级提示词中的会话级，默认中文）。 */
    public static final String DEFAULT_SESSION_PROMPT = "HMS Core AI Agent 会话";

    /** 子 Agent 会话级 systemPrompt（创建子 Agent 时使用）。 */
    public static final String DEFAULT_SUBAGENT_SYSTEM_PROMPT =
            "你是一个 AI 子 Agent。请完成分配给你的任务，并在完成后返回一份简洁的报告。";

    /** 聊天模型，用于为每个会话创建 AgentLoop。 */
    private final ChatModel chatModel;
    /** 全局工具注册中心（两级工具中的全局级，所有会话共享）。 */
    private final ToolRegistry globalToolRegistry;
    /** 权限规则引擎，用于评估工具调用权限（可为 null）。 */
    private final PermissionRuleEngine permissionEngine;
    /** 提示词管理器，负责两级提示词（全局 + 会话）的组装。 */
    private final PromptManager promptManager;
    /**
     * 全局工具上下文（可为 null）—— 每个会话的上下文以它为父级，
     * 从而读到 TaskManager / McpManager / PermissionSettings 等全局共享对象。
     */
    private final ToolContext globalToolContext;
    /** 回调解析器 —— 同步优先、异步回退的协议实现，与 {@link DefaultHmsService} 共用。 */
    private final CallbackResolver callbackResolver;

    /** sessionId → LoopSession 的映射，维护所有会话实例。 */
    private final ConcurrentHashMap<String, LoopSession> sessions = new ConcurrentHashMap<>();

    /**
     * 后台会话 id 集合 —— 主动 Agent 的周期心跳所用的专用会话。
     * <p>
     * 存在的唯一理由：把这类会话从会话数上限里排除。一个主动 Agent 的后台会话会
     * 长期占据一个名额，若照常计数，「10 个主动 Agent + maxSessions=10」会让用户
     * 一个会话都开不出来 —— 而用户根本看不到、也没创建过这些会话。
     */
    private final Set<String> backendSessions = ConcurrentHashMap.newKeySet();

    /** 空闲会话定期清理的调度线程池。 */
    private final ScheduledExecutorService cleanupScheduler;

    /**
     * 是否由本实例拥有 {@link #cleanupScheduler} 的生命周期。
     * <p>
     * 手动构造（未传共享池）时为 {@code true}，{@link #close()} 需要 shutdown 它；
     * 由 Spring 注入共享池时为 {@code false} —— 那个池还跑着别的任务（主动 Agent 心跳），
     * 关掉它等于误伤。此前 close() 无条件 shutdownNow()，多实例场景下第一个被
     * 回收的 Agent 会把其余 Agent 的心跳连同自己的清理任务一起打死。
     */
    private final boolean ownsCleanupScheduler;

    /** 空闲清理任务句柄 —— 不保存就无从取消，close() 只能靠 shutdown 整个池。 */
    private volatile ScheduledFuture<?> cleanupTask;

    /** 本实例所属的 Agent id —— 写进 {@link SessionInfo} 与会话上下文。 */
    private final String agentId;

    /**
     * Agent 基座提示词的供给方（可为 {@code null}）。
     * <p>
     * {@code null} 表示这是默认 Agent —— 此时 system prompt 走既有的一参数路径，
     * 与改造前逐字节等价。非默认 Agent 由 {@code AgentRuntimeFactory} 注入
     * {@code () -> definition.basePrompt()}。
     */
    private final Supplier<String> agentBasePrompt;

    /**
     * 记忆摘要的供给方（可为 {@code null}）。
     * <p>
     * 每次构建 system prompt 时<b>现取一次</b>：会话创建时取到的就固化进
     * {@code messageHistory[0]} 冻结下来；显式刷新（{@code AgentRuntime.refreshMemoryDigest()}）
     * 时才重新取。日常路径不会触碰它，因此不会无意中击穿 prompt cache。
     */
    private final Supplier<String> memoryDigest;

    /** 默认空闲超时秒数（空闲超过该值即被清理）。 */
    private final long defaultIdleTimeoutSeconds;
    /** 空闲清理任务的执行间隔秒数。 */
    private final long cleanupIntervalSeconds;
    /** 等待用户回答（AskUser / 权限确认）的上限秒数。 */
    private final long userResponseTimeoutSeconds;

    /** 同时存活的会话数上限（超出即拒绝创建）。 */
    private final int maxSessions;

    /** 单轮最大迭代次数 —— 传给每个会话及其子 Agent 的 AgentLoop。 */
    private final int maxIterations;

    /** 上下文窗口大小（token）—— 传给每个会话的 TokenTracker，决定压缩阈值。 */
    private final long contextWindow;

    /** 预留 token 数 —— 从窗口中扣除后得到有效窗口。 */
    private final long reservedTokens;

    /**
     * Token 计费策略（可为 null —— 此时费用一律呈现为「未知」）。
     * <p>
     * 由集成方经 Bean 注入覆写，见 {@link TokenPricing}。
     */
    private final TokenPricing tokenPricing;

    /** 等待用户回答的默认上限秒数 —— 需容纳真人思考与操作时间。 */
    public static final long DEFAULT_USER_RESPONSE_TIMEOUT_SECONDS = 300;

    /**
     * 默认会话数上限 —— 每个会话持有独立的消息历史与工具副本，
     * 无上限时失控的 createSession() 调用会耗尽堆内存。
     */
    public static final int DEFAULT_MAX_SESSIONS = 1000;

    /** 默认空闲超时 —— 30 分钟无访问即回收。 */
    public static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 30 * 60;

    /** 默认清理间隔 —— 每 5 分钟扫一遍空闲会话。 */
    public static final long DEFAULT_CLEANUP_INTERVAL_SECONDS = 5 * 60;

    /**
     * 构造会话管理器（四项必需依赖 + 全部默认配置），并启动空闲会话清理任务。
     * <p>
     * 需要调整超时、会话上限或注入全局工具上下文时用 {@link #builder(ChatModel,
     * ToolRegistry, PromptManager)}。
     *
     * @param chatModel          聊天模型
     * @param globalToolRegistry 全局工具注册中心
     * @param permissionEngine   权限规则引擎（可为 null，此时工具权限交由回调决定）
     * @param promptManager      提示词管理器
     */
    public DefaultHmsSessionManager(ChatModel chatModel, ToolRegistry globalToolRegistry,
                                    PermissionRuleEngine permissionEngine, PromptManager promptManager) {
        this(new Builder(chatModel, globalToolRegistry, promptManager)
                .permissionEngine(permissionEngine));
    }

    /** 由 {@link Builder#build()} 调用 —— 所有字段都在这里落定。 */
    private DefaultHmsSessionManager(Builder builder) {
        this.chatModel = builder.chatModel;
        this.globalToolRegistry = builder.globalToolRegistry;
        this.permissionEngine = builder.permissionEngine;
        this.promptManager = builder.promptManager;
        this.globalToolContext = builder.globalToolContext;
        this.defaultIdleTimeoutSeconds = builder.idleTimeoutSeconds;
        this.cleanupIntervalSeconds = builder.cleanupIntervalSeconds;
        this.userResponseTimeoutSeconds = builder.userResponseTimeoutSeconds;
        this.maxSessions = builder.maxSessions;
        this.maxIterations = builder.maxIterations;
        this.contextWindow = builder.contextWindow;
        this.reservedTokens = builder.reservedTokens;
        this.tokenPricing = builder.tokenPricing;
        this.callbackResolver = new CallbackResolver(builder.userResponseTimeoutSeconds, "[SESSION]");

        this.agentId = builder.agentId != null ? builder.agentId : AgentRegistry.DEFAULT_AGENT_ID;
        this.agentBasePrompt = builder.agentBasePrompt;
        this.memoryDigest = builder.memoryDigest;

        // 调度池按「谁创建谁负责」分流：集成方传进来的共享池由集成方关闭（它上面还挂着
        // 主动 Agent 的心跳），只有自建的那个才由 close() 收掉。
        if (builder.cleanupScheduler != null) {
            this.cleanupScheduler = builder.cleanupScheduler;
            this.ownsCleanupScheduler = false;
        } else {
            this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("session-cleanup-", 0).factory());
            this.ownsCleanupScheduler = true;
        }
        startCleanupTask();
        log.info("SessionManager initialized (agent={}, idleTimeout={}s, maxSessions={})",
                agentId, defaultIdleTimeoutSeconds, maxSessions);
    }

    /**
     * 开始构建会话管理器 —— 三项无默认值的依赖作为参数，其余通过链式方法覆盖。
     *
     * @param chatModel          聊天模型
     * @param globalToolRegistry 全局工具注册中心
     * @param promptManager      提示词管理器
     */
    public static Builder builder(ChatModel chatModel, ToolRegistry globalToolRegistry,
                                  PromptManager promptManager) {
        return new Builder(chatModel, globalToolRegistry, promptManager);
    }

    /**
     * {@link DefaultHmsSessionManager} 的构建器。
     * <p>
     * 替代此前 5 个逐层加参的重载构造器 —— 那种写法下每加一个可选参数就要多一个
     * 重载和一份复制的 Javadoc，而调用方从 {@code (…, 3600, 3600, 300, 10)} 这样
     * 的参数列表也读不出每个数字的含义。
     */
    public static final class Builder {

        private final ChatModel chatModel;
        private final ToolRegistry globalToolRegistry;
        private final PromptManager promptManager;

        private PermissionRuleEngine permissionEngine;
        private ToolContext globalToolContext;
        private long idleTimeoutSeconds = DEFAULT_IDLE_TIMEOUT_SECONDS;
        private long cleanupIntervalSeconds = DEFAULT_CLEANUP_INTERVAL_SECONDS;
        private long userResponseTimeoutSeconds = DEFAULT_USER_RESPONSE_TIMEOUT_SECONDS;
        private int maxSessions = DEFAULT_MAX_SESSIONS;
        private int maxIterations = AgentLoop.DEFAULT_MAX_ITERATIONS;
        private long contextWindow = TokenTracker.DEFAULT_CONTEXT_WINDOW;
        private long reservedTokens = TokenTracker.DEFAULT_RESERVED_TOKENS;
        private TokenPricing tokenPricing;
        private ScheduledExecutorService cleanupScheduler;
        private String agentId;
        private Supplier<String> agentBasePrompt;
        private Supplier<String> memoryDigest;

        private Builder(ChatModel chatModel, ToolRegistry globalToolRegistry,
                        PromptManager promptManager) {
            this.chatModel = chatModel;
            this.globalToolRegistry = globalToolRegistry;
            this.promptManager = promptManager;
        }

        /** 权限规则引擎；不设置时工具权限完全由请求级回调决定。 */
        public Builder permissionEngine(PermissionRuleEngine permissionEngine) {
            this.permissionEngine = permissionEngine;
            return this;
        }

        /**
         * 全局工具上下文 —— 作为各会话上下文的父级，承载 TaskManager / McpManager
         * 等全局共享对象。不设置时依赖这些对象的内置工具将不可用。
         */
        public Builder globalToolContext(ToolContext globalToolContext) {
            this.globalToolContext = globalToolContext;
            return this;
        }

        /** 会话空闲多久后被清理线程回收（秒）。 */
        public Builder idleTimeoutSeconds(long idleTimeoutSeconds) {
            this.idleTimeoutSeconds = idleTimeoutSeconds;
            return this;
        }

        /** 空闲清理任务的执行间隔（秒）。 */
        public Builder cleanupIntervalSeconds(long cleanupIntervalSeconds) {
            this.cleanupIntervalSeconds = cleanupIntervalSeconds;
            return this;
        }

        /** 等待用户回答（AskUser / 权限确认）的上限（秒），超时按拒绝处理。 */
        public Builder userResponseTimeoutSeconds(long userResponseTimeoutSeconds) {
            this.userResponseTimeoutSeconds = userResponseTimeoutSeconds;
            return this;
        }

        /** 同时存活的会话数上限 —— 每个会话持有独立的历史与工具副本。 */
        public Builder maxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
            return this;
        }

        /**
         * 单轮最大迭代次数 —— 撞上限会截断回答并追加警告标记。
         * {@code <= 0} 时回退到 {@link AgentLoop#DEFAULT_MAX_ITERATIONS}。
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * 上下文窗口大小（token）—— 决定压缩阈值，应与实际所用模型的窗口一致。
         * <p>
         * 配小了会过早压缩、白花摘要费用；配大了压缩来不及，请求直接被上游拒。
         * {@code <= 0} 时回退到 {@link TokenTracker#DEFAULT_CONTEXT_WINDOW}。
         */
        public Builder contextWindow(long contextWindow) {
            this.contextWindow = contextWindow;
            return this;
        }

        /**
         * 预留 token 数 —— 从窗口中扣除，留给模型输出与压缩摘要本身。
         * {@code <= 0} 或 {@code >= 窗口} 时回退到
         * {@link TokenTracker#DEFAULT_RESERVED_TOKENS}。
         */
        public Builder reservedTokens(long reservedTokens) {
            this.reservedTokens = reservedTokens;
            return this;
        }

        /**
         * Token 计费策略 —— 不设置时 {@link TokenStats#cost()} 恒为 {@code null}
         * （呈现为「定价未知」），token 计数不受影响。
         * <p>
         * Spring 装配下由 {@code ApiAutoConfiguration} 注入容器里的
         * {@link TokenPricing} Bean；集成方声明自己的 Bean 即可接管计费。
         */
        public Builder tokenPricing(TokenPricing tokenPricing) {
            this.tokenPricing = tokenPricing;
            return this;
        }

        /**
         * 使用外部共享的调度线程池执行空闲清理 —— 多智能体装配下与主动 Agent
         * 的心跳共用一个池，避免「每个 Agent 一条线程」。
         * <p>
         * 传入共享池后，本实例<b>不再拥有</b>该池的生命周期：{@link #build()} 只在
         * 其上注册清理任务，{@code close()} 只取消自己的任务而不 shutdown 池。
         * 不传则自建一个单线程池，由 {@code close()} 负责收掉。
         */
        public Builder cleanupScheduler(ScheduledExecutorService cleanupScheduler) {
            this.cleanupScheduler = cleanupScheduler;
            return this;
        }

        /** 本实例所属的 Agent id（写进会话信息）；不设置时为 {@link AgentRegistry#DEFAULT_AGENT_ID}。 */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /**
         * Agent 基座提示词供给方 —— 三级提示词的第 1 级。
         * <p>
         * 不设置（默认 Agent）时 system prompt 走既有的一参数拼接路径，
         * 结果与改造前逐字节相同。
         */
        public Builder agentBasePrompt(Supplier<String> agentBasePrompt) {
            this.agentBasePrompt = agentBasePrompt;
            return this;
        }

        /**
         * 记忆摘要供给方 —— 三级提示词的第 2 级。
         * <p>
         * 不设置时跳过该段（默认 Agent 即如此）。每次构建 system prompt 时现取，
         * 因此「会话创建时冻结」是自然结果，无需额外状态。
         */
        public Builder memoryDigest(Supplier<String> memoryDigest) {
            this.memoryDigest = memoryDigest;
            return this;
        }

        /** 构建实例并启动空闲清理任务。 */
        public DefaultHmsSessionManager build() {
            return new DefaultHmsSessionManager(this);
        }
    }

    // ==================== 会话生命周期 ====================

    /** 创建新会话，使用当前语言下的默认会话提示词。 */
    @Override
    public String createSession() {
        // SDK 场景：不再依赖 projectDir 文件系统路径
        // 使用当前语言下的默认会话提示词（中文系统 → 中文；非中文系统 → 翻译后的版本）
        String defaultSessionPrompt = PromptI18n.t(PromptI18n.KEY_SESSION_PROMPT, DEFAULT_SESSION_PROMPT);
        return createSession(defaultSessionPrompt);
    }

    /**
     * 创建带自定义提示词的新会话。
     * <p>
     * 会复制一份全局工具集作为会话独立副本、拼接两级提示词、
     * 注册子 Agent 工厂及权限回调，最后加入会话映射。
     *
     * @param customSessionPrompt 会话级提示词
     * @return 新会话的 sessionId
     */
    @Override
    public String createSession(String customSessionPrompt) {
        return doCreateSession(customSessionPrompt, false, true);
    }

    /**
     * 【内部】创建一个后台会话 —— 主动 Agent 的周期心跳专用，<b>不计入会话数上限</b>。
     * <p>
     * 与普通会话的唯一差别是容量计数把它排除在外：用户没创建过它、也看不见它，
     * 让它占用「最多能开几个会话」的名额会让主动 Agent 一多就把用户挡在门外。
     * 其余行为（工具集、权限、提示词、压缩、空闲回收）与普通会话完全一致，
     * 因此心跳产物自然落在它的消息历史里，可用 {@code getSessionMessages} 查看。
     * <p>
     * 供 {@code AgentRuntime} 使用，不属于面向集成方的稳定 API。
     *
     * @param sessionPrompt 会话级提示词
     * @return 新建的后台会话 id
     */
    public String createBackendSession(String sessionPrompt) {
        return doCreateSession(sessionPrompt, true, true);
    }

    /**
     * 【内部】一次性执行：建一个临时会话跑一轮，取回文本后立即销毁。
     * <p>
     * 用于跨 Agent 委派（{@code AgentRuntime.runOnce}）。该临时会话<b>不注册子 Agent
     * 工厂</b>（{@code allowSubAgents=false}）—— 目标 Agent 因此无法再往下派，
     * 委派深度被硬性限制为 1 层，token 消耗不会因递归委派而失控。
     * <p>
     * 临时会话照常计入会话上限：它是真实资源占用，只是存活时间极短。
     *
     * @param prompt 完整任务提示词
     * @return 目标 Agent 的最终回复
     */
    public String runEphemeral(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new HmsException(HmsErrorCode.INVALID_INPUT,
                    "Prompt cannot be null or empty");
        }
        String sessionId = doCreateSession(
                PromptI18n.t(PromptI18n.KEY_SESSION_PROMPT, DEFAULT_SESSION_PROMPT), false, false);
        try {
            return send(sessionId, prompt).content();
        } finally {
            destroyIfExists(sessionId);
        }
    }

    /**
     * 【内部】本 Agent 名下的用户会话数（不含后台会话）—— 插拔时判断「能否下线」的依据。
     */
    public int getUserSessionCount() {
        return Math.max(0, sessions.size() - backendSessions.size());
    }

    /**
     * 装配一个会话的完整对象图并入册。
     *
     * @param sessionPrompt  会话级提示词
     * @param backend        是否为后台会话（决定要不要排除于容量计数）
     * @param allowSubAgents 是否注册子 Agent 工厂 —— 委派用的一次性会话传 {@code false}
     */
    private String doCreateSession(String sessionPrompt, boolean backend, boolean allowSubAgents) {
        String sessionId = UUID.randomUUID().toString();
        log.info("Creating {}session: {} (agent={})", backend ? "backend " : "", sessionId, agentId);

        TokenTracker tokenTracker = newTokenTracker();
        MetricsCollector metrics = new MetricsCollector(sessionId);
        AgentLoop agentLoop = newAgentLoop(sessionId, tokenTracker,
                buildFullPrompt(sessionPrompt), allowSubAgents);
        agentLoop.setOnToolEvent(event -> metrics.recordToolUse(event.toolName()));

        LoopSession loopSession = new LoopSession(sessionId, agentLoop, tokenTracker,
                metrics, sessionPrompt);

        // 容量检查放在插入处：先占位再校验，避免 size() 的 check-then-act 竞态
        // 让并发创建突破上限。后台会话先登记再计数 —— 顺序反了它就会在
        // 「已入册但尚未标记」的窗口里被算成一个用户会话。
        sessions.put(sessionId, loopSession);
        if (backend) {
            backendSessions.add(sessionId);
        }
        if (getUserSessionCount() > maxSessions) {
            sessions.remove(sessionId);
            backendSessions.remove(sessionId);
            loopSession.destroy();
            log.warn("Session creation rejected: limit {} reached (agent={})", maxSessions, agentId);
            throw new HmsException(HmsErrorCode.SESSION_LIMIT_EXCEEDED,
                    "Cannot create session: limit of " + maxSessions
                            + " reached. Destroy idle sessions or raise "
                            + "hms-core.session.max-sessions.");
        }

        log.info("Session {} created (agent={}, tools: {}, user sessions: {})",
                sessionId, agentId, agentLoop.getToolRegistry().size(), getUserSessionCount());
        return sessionId;
    }

    /** 销毁会话（若存在），不抛异常 —— 供 finally 兜底使用。 */
    private void destroyIfExists(String sessionId) {
        LoopSession session = sessions.remove(sessionId);
        backendSessions.remove(sessionId);
        if (session != null) {
            session.destroy();
        }
    }

    /**
     * 装配一个会话（或子 Agent）的 {@link AgentLoop} 对象图。
     *
     * @param sessionId      仅用于日志标识（子 Agent 沿用所属会话的 id）
     * @param tokenTracker   该循环专属的 token 追踪器
     * @param systemPrompt   系统提示词
     * @param allowSubAgents 是否注册子 Agent 工厂 —— 只有主会话为 {@code true}
     */
    private AgentLoop newAgentLoop(String sessionId, TokenTracker tokenTracker,
                                   String systemPrompt, boolean allowSubAgents) {
        // 从全局工具复制一份（两级工具隔离），上下文取全局上下文的子上下文：
        // TaskManager / McpManager / PermissionSettings 等共享对象注册在全局上下文上，
        // 用空上下文会让依赖它们的工具（Task*、*McpResource*、Enter/ExitPlanMode）
        // 全部返回「未初始化」错误。
        ToolRegistry toolRegistry = copyGlobalTools();
        ToolContext toolContext = ToolContext.childOf(globalToolContext);
        toolContext.set("TOOL_REGISTRY", toolRegistry);
        toolContext.set("AGENT_ID", agentId);

        // 从属会话（子 Agent、以及为跨 Agent 委派开的一次性会话）的能力收窄：
        //
        // 1) 记忆只读不写 —— 写工具会让「派个子 Agent 去做」成为一条绕过调用方记忆审查
        //    的通道。产出该不该被记住，由发起方决定；发起方自己的会话（含后台会话）照常可写。
        // 2) 不能再往下派 —— 这正是「委派深度恒为 1 层」的保证。移除 Agent 工具而不是
        //    只靠「不注册子 Agent 工厂」：工厂只管不传 agent 参数的路径，而传了 agent 参数
        //    的跨 Agent 委派读的是上下文里的 AGENT_REGISTRY —— 它在本层依然可达。
        //    不移除的话 A→B→A 可以无限循环，每轮都在烧 token 且不会自然终止。
        // 3) ListAgents 保留：它只读，能让被委派者看清自己在整个智能体图谱里的位置。
        if (!allowSubAgents) {
            toolRegistry.remove(AgentMemoryRememberTool.NAME);
            toolRegistry.remove(AgentTool.NAME);
        }

        AgentLoop loop = new AgentLoop(chatModel, toolRegistry, toolContext,
                systemPrompt, tokenTracker, maxIterations);
        // 压缩器必须绑定本循环自己的 tokenTracker —— 阈值判断读的是该 tracker 的
        // lastPromptTokens，绑到其他实例会导致 shouldAutoCompact() 恒为 false。
        loop.setAutoCompactManager(new AutoCompactManager(chatModel, tokenTracker));
        if (permissionEngine != null) {
            loop.setPermissionEngine(permissionEngine);
        }
        loop.setOnPermissionRequest(req -> headlessPermission(sessionId, req));

        // 子 Agent 工厂 —— 只给主会话注册。子 Agent 自身拿不到工厂（allowSubAgents
        // 为 false），因此无法再派子 Agent：递归派发既会让 token 消耗失控，也让
        // 「哪个循环在做什么」无从追踪。
        if (allowSubAgents) {
            toolContext.set(AgentTool.AGENT_FACTORY_KEY,
                    (Function<String, String>) prompt -> newAgentLoop(
                            sessionId, newTokenTracker(),
                            PromptI18n.t(PromptI18n.KEY_SUBAGENT_SESSION_PROMPT,
                                    DEFAULT_SUBAGENT_SYSTEM_PROMPT),
                            false)
                            .run(prompt));
        }

        return loop;
    }

    /**
     * 无 UI 可询问时的权限决定 —— 只放行本就无需确认的低风险操作。
     * <p>
     * 主会话在提供了 {@link HmsCallbacks} 时会被请求级回调覆盖，真正去问用户；
     * 子 Agent 与 headless 调用则始终走这里。
     * <p>
     * <b>不重新评估</b>：请求能到达本回调，说明规则引擎已用工具的真实风险等级判定为
     * 「需要询问」。重新评估只能得到同一个 ASK，或者因为拿不到工具对象而猜一个风险
     * 等级 —— 曾经的实现猜 MEDIUM，而 DEFAULT 模式下 autoAllowUpTo 恰好也是 MEDIUM，
     * 于是「风险等级自动放行」必然命中，CRITICAL / HIGH 工具的用户确认被完全跳过。
     * 子 Agent 那条路更彻底：无条件放行，让「派一个子 Agent 去做」成了越权通道。
     * <p>
     * 拒绝会作为工具结果回传给模型，它可以换一种方式继续，而不是静默越权执行。
     */
    private static PermissionChoice headlessPermission(String sessionId,
                                                       AgentLoop.PermissionRequest req) {
        Tool.RiskLevel risk = req.riskLevel();
        if (risk != null && risk.ordinal() <= Tool.RiskLevel.LOW.ordinal()) {
            log.debug("[{}] Permission auto-allowed (headless, risk={}): {}",
                    sessionId, risk, req.toolName());
            return PermissionChoice.ALLOW_ONCE;
        }
        log.info("[{}] Permission denied (headless, no callback to ask; risk={}): {} — {}",
                sessionId, risk, req.toolName(),
                req.decision() != null ? req.decision().reason() : "needs user confirmation");
        return PermissionChoice.DENY_ONCE;
    }

    /**
     * 新建会话级 {@link TokenTracker}。
     * <p>
     * 不再调用已废弃的 {@code setModel} 去「配置定价」—— 定价由 {@link TokenPricing}
     * 承担，模型名在算费时经 {@link #resolveModelName()} 现取（那是唯一权威来源，
     * 不会与本类的副本不同步）。
     */
    private TokenTracker newTokenTracker() {
        return new TokenTracker(contextWindow, reservedTokens);
    }

    /**
     * 从 ChatModel 的生效选项中读取模型名 —— 直接取用运行时配置，
     * 避免与环境变量 / yml 两处配置源不一致。
     * <p>
     * 用 {@code getOptions()} 而非 {@code getDefaultOptions()}：后者在 Spring AI
     * 2.0 起已标记 {@code @Deprecated(forRemoval = true)}，其默认实现只是委托给
     * 前者。注意 {@code getOptions()} 的接口默认实现返回的是空 ChatOptions
     * （{@code getModel()} 为 null），因此 null 判断仍然必需 —— 自定义 ChatModel
     * 实现未覆写它时会走到那里。
     *
     * @return 模型名；无法解析时为 {@code null} —— 此时 {@link TokenPricing} 无从
     *         查价，费用会以「定价未知」呈现而非按某个默认价目表估一个数
     */
    private String resolveModelName() {
        try {
            ChatOptions options = chatModel.getOptions();
            return options != null ? options.getModel() : null;
        } catch (RuntimeException e) {
            log.debug("Cannot resolve model name from ChatModel: {}", e.getMessage());
            return null;
        }
    }

    /** 复制全局工具注册中心给新会话。 */
    private ToolRegistry copyGlobalTools() {
        ToolRegistry copy = new ToolRegistry();
        for (Tool tool : globalToolRegistry.getTools()) {
            copy.register(tool);
        }
        return copy;
    }

    /**
     * 组装本会话的完整系统提示词。
     * <p>
     * 默认 Agent（两个供给方均为 {@code null}）走<b>既有的一参数路径</b>，
     * 拼接结果与改造前逐字节相同 —— 这是 demo-app 零回归的根本保证。
     * 非默认 Agent 走三级拼接：基座提示词 + 记忆摘要 + 会话提示词。
     */
    private String buildFullPrompt(String sessionPrompt) {
        String agentPrompt = agentBasePrompt != null ? agentBasePrompt.get() : null;
        String digest = memoryDigest != null ? memoryDigest.get() : null;
        if ((agentPrompt == null || agentPrompt.isBlank())
                && (digest == null || digest.isBlank())) {
            return promptManager.buildFullPrompt(sessionPrompt);
        }
        return promptManager.buildFullPrompt(agentPrompt, digest, sessionPrompt);
    }

    /** 销毁会话并从映射中移除，释放相关资源（会话不存在时抛异常）。 */
    @Override
    public void destroySession(String sessionId) {
        LoopSession session = sessions.remove(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        backendSessions.remove(sessionId);
        session.destroy();
        log.info("Session {} destroyed (remaining: {})", sessionId, sessions.size());
    }

    /** 暂停会话（保留上下文但拒绝新消息）。 */
    @Override
    public void pauseSession(String sessionId) {
        LoopSession session = requireSession(sessionId);
        session.pause();
        log.info("Session {} paused", sessionId);
    }

    /** 恢复被暂停的会话。 */
    @Override
    public void resumeSession(String sessionId) {
        LoopSession session = requireExistingSession(sessionId);
        session.resume();
        log.info("Session {} resumed", sessionId);
    }

    /** 检查会话是否处于暂停状态。 */
    @Override
    public boolean isPaused(String sessionId) {
        LoopSession session = sessions.get(sessionId);
        return session != null && session.getStatus() == SessionStatus.PAUSED;
    }

    /** 检查会话是否存在。 */
    @Override
    public boolean sessionExists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    // ==================== 消息发送 ====================

    /**
     * 获取会话并校验可发送状态，同时刷新最后访问时间。
     * <p>
     * 会话不存在抛 {@link IllegalArgumentException}，处于 PAUSED 状态抛
     * {@link IllegalStateException}。
     */
    private LoopSession requireSession(String sessionId) {
        LoopSession session = requireExistingSession(sessionId);
        if (session.getStatus() == SessionStatus.PAUSED) {
            throw new IllegalStateException("Session is paused: " + sessionId);
        }
        session.touch();
        return session;
    }

    /**
     * 获取已存在的会话，仅校验是否存在，不拒绝 PAUSED 状态、也不刷新访问时间。
     * <p>
     * 用于 resume 以及只读查询（token / metrics / 历史）—— 这些操作在会话暂停时
     * 也应可用，且只读查询不应把一个即将被回收的空闲会话「续命」。
     */
    private LoopSession requireExistingSession(String sessionId) {
        LoopSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException(
                    "Session not found: " + sessionId + ". Call createSession() first.");
        }
        return session;
    }

    /**
     * 同步调用 —— 向指定会话发送用户消息并等待完整回复。
     * 会话级互斥（{@code synchronized(session)}），同一会话同一时间只能执行一个请求。
     */
    @Override
    public HmsResponse send(String sessionId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new HmsException(HmsErrorCode.INVALID_INPUT,
                    "User message cannot be null or empty");
        }
        LoopSession session = requireSession(sessionId);
        session.beginRequest();
        try {
            // 会话级互斥：同一会话同一时间只能有一个请求在执行
            synchronized (session) {
                session.getMetricsCollector().recordUserMessage();

                AgentLoop loop = session.getAgentLoop();
                TokenTracker tt = session.getTokenTracker();
                // 基线取自调用前 —— 之后的差值才是本轮用量
                long inputBefore = tt.getInputTokens();
                long outputBefore = tt.getOutputTokens();

                String result = loop.run(userMessage);

                return buildResponse(session, loop, tt, result, inputBefore, outputBefore);
            }
        } finally {
            session.endRequest();
        }
    }

    /**
     * 汇总本轮响应，并按<b>本轮增量</b>（而非会话累计）记录指标与 token 数。
     * <p>
     * {@link TokenTracker} 是会话级累计器，直接把它的总量当作单轮用量会造成
     * 两处错误：{@link HmsResponse#promptTokens()} 与其「本轮消耗」的文档语义
     * 不符，且 {@link MetricsCollector#recordApiCall} 每轮都累加一次总量，
     * 使会话总量随轮数呈平方级膨胀（3 轮各 100 token 会被记成 600）。
     * <p>
     * 因取消而结束的轮次标记 {@link HmsResponse#interrupted()}，并保留已产生的
     * 用量 —— 中断前消耗的 token 一样要计费。没有这个标志，调用方只能去匹配回复
     * 末尾的「[用户已中断]」文本，而那段文本会被 i18n 按系统语言翻译。
     */
    private static HmsResponse buildResponse(LoopSession session, AgentLoop loop, TokenTracker tt,
                                             String result, long inputBefore, long outputBefore) {
        long inputDelta = tt.getInputTokens() - inputBefore;
        long outputDelta = tt.getOutputTokens() - outputBefore;
        session.getMetricsCollector().recordApiCall(inputDelta, outputDelta);
        session.getMetricsCollector().recordAssistantMessage();

        int toolCalls = loop.getLastToolCallCount();
        return loop.wasLastRunInterrupted()
                ? HmsResponse.interrupted(result, toolCalls, inputDelta, outputDelta)
                : HmsResponse.ok(result, toolCalls, inputDelta, outputDelta);
    }

    /**
     * 流式调用 —— 向指定会话发送消息，每个 token 实时回调，结束后返回聚合响应。
     */
    @Override
    public HmsResponse sendStreaming(String sessionId, String userMessage,
                                     Consumer<String> onToken) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new HmsException(HmsErrorCode.INVALID_INPUT,
                    "User message cannot be null or empty");
        }
        LoopSession session = requireSession(sessionId);
        session.beginRequest();
        try {
            synchronized (session) {
                session.getMetricsCollector().recordUserMessage();

                AgentLoop loop = session.getAgentLoop();
                TokenTracker tt = session.getTokenTracker();
                long inputBefore = tt.getInputTokens();
                long outputBefore = tt.getOutputTokens();

                String result = loop.runStreaming(userMessage, onToken);

                return buildResponse(session, loop, tt, result, inputBefore, outputBefore);
            }
        } finally {
            session.endRequest();
        }
    }

    /**
     * 带完整回调的调用 —— 支持 token 流、工具事件、thinking、AskUser、权限请求等回调。
     * <p>
     * 通过 ToolContext 注册 AskUser 回调链（同步 → 异步 → 回退），
     * 使用请求级回调避免污染 AgentLoop 持久状态。
     */
    @Override
    public HmsResponse send(String sessionId, String userMessage,
                            HmsCallbacks callbacks) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new HmsException(HmsErrorCode.INVALID_INPUT,
                    "User message cannot be null or empty");
        }
        log.info("[SEND] Session {} received message ({} chars): {}",
                sessionId, userMessage.length(),
                userMessage.length() > 200 ? userMessage.substring(0, 200) + "..." : userMessage);
        LoopSession session = requireSession(sessionId);
        session.beginRequest();
        try {
            return doSend(session, sessionId, userMessage, callbacks);
        } finally {
            // 顺序要紧：先摘掉回调再标记请求结束，否则在两步之间到达的调用仍可能
            // 看到上一个请求的回调。
            clearAskUserCallbacks(session.getAgentLoop().getToolContext());
            session.endRequest();
        }
    }

    /**
     * 把本次请求的 AskUser 回调链注册到会话上下文。
     * <p>
     * 两级回退：结构化回调（带候选项）优先，简单文本回调兜底，两者都由
     * {@link CallbackResolver} 走「同步 → 异步 → 放弃」的解析。
     * <p>
     * <b>必须与 {@link #clearAskUserCallbacks} 成对使用</b>：上下文是会话级的、
     * 跨请求存活，而这里的闭包捕获了本次请求的 {@link HmsCallbacks}。残留下来会让
     * 后续不带回调的 {@code send} 把提问打给上一个请求的回调 —— SSE 场景下那个
     * 接收端早已 complete，提问既送不出也收不回，只能空等到超时才回退。
     */
    private void registerAskUserCallbacks(ToolContext toolContext, HmsCallbacks callbacks,
                                          AgentLoop loop) {
        toolContext.set(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                (BiFunction<String, List<String>, String>) (question, options) ->
                        awaitUserAnswer(loop,
                                () -> callbackResolver.resolveAskUser(callbacks, question, options)));
        toolContext.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                (Function<String, String>) prompt ->
                        awaitUserAnswer(loop,
                                () -> callbackResolver.resolveAskUser(callbacks, prompt, null)));
    }

    /**
     * 在等待用户回答期间把活动状态标记为 {@link SessionActivity#WAITING_USER}。
     * <p>
     * 事件推送由 {@code EventBridgeCallbacks} 负责（它才知道何时登记完成），这里只
     * 维护会话快照读取的那个字段 —— 阻塞在解析器内部的线程没法自己更新状态。
     * <p>
     * {@code finally} 里恢复为 {@code USING_TOOL}：提问与权限确认都发生在工具执行
     * 期间，回答到达后紧接着就是该工具继续执行。超时兜底同样走这条路径。
     */
    private <T> T awaitUserAnswer(AgentLoop loop, java.util.function.Supplier<T> resolver) {
        SessionActivity previous = loop.getActivity();
        loop.reportActivity(SessionActivity.WAITING_USER);
        try {
            return resolver.get();
        } finally {
            loop.reportActivity(previous == SessionActivity.WAITING_USER
                    ? SessionActivity.USING_TOOL : previous);
        }
    }

    /** 摘除请求级 AskUser 回调 —— 只删本地键，不影响父级注册的全局共享对象。 */
    private static void clearAskUserCallbacks(ToolContext toolContext) {
        toolContext.remove(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK);
        toolContext.remove(AskUserQuestionTool.USER_INPUT_CALLBACK);
    }

    /** {@link #send(String, String, HmsCallbacks)} 的主体（执行中标记已由调用方管理）。 */
    private HmsResponse doSend(LoopSession session, String sessionId, String userMessage,
                               HmsCallbacks callbacks) {
        synchronized (session) {
            session.getMetricsCollector().recordUserMessage();

            AgentLoop loop = session.getAgentLoop();
            ToolContext toolContext = loop.getToolContext();

            // 注册 AskUser 回调链：同步阻塞 → 异步 → ToolContext 回退。
            // 请求结束后由 send() 的 finally 清除，见 registerAskUserCallbacks 的说明。
            registerAskUserCallbacks(toolContext, callbacks, loop);

            // 构建请求级回调（不污染 AgentLoop 持久状态）
            AgentLoop.RequestCallbacks requestCallbacks = new AgentLoop.RequestCallbacks(
                    event -> {
                        // 只在 END 记一次用量。同一次调用会发 START、若干 PROGRESS、END
                        // 三类事件，逐条计数会让工具用量翻几倍（此前正是如此）。
                        if (event.phase() == AgentLoop.ToolEvent.Phase.END) {
                            session.getMetricsCollector().recordToolUse(event.toolName());
                        }
                        callbacks.onToolUse(event.toolName(), event.phase().name(),
                                event.arguments(), event.result());
                        // 活动状态跟随工具阶段：START 进入「调用工具」并带上工具名。
                        // END 之后由 AgentLoop 在下一轮开始前置回 CALLING_MODEL。
                        // 两件事都要做 —— reportActivity 供会话快照（轮询）读取，
                        // onActivity 推给 SSE 观测方。
                        if (event.phase() == AgentLoop.ToolEvent.Phase.START) {
                            loop.reportActivity(SessionActivity.USING_TOOL);
                            callbacks.onActivity(SessionActivity.USING_TOOL, event.toolName());
                        }
                    },
                    callbacks::onThinking,
                    // 权限确认期间同样标记「待确认」，供会话快照读取
                    req -> awaitUserAnswer(loop, () -> callbackResolver.resolvePermission(callbacks, req)),
                    callbacks::onToken,
                    callbacks::onCompaction,
                    // 循环内部的状态切换：AgentLoop 已经落好字段，这里只负责转发给观测方
                    callbacks::onActivity
            );

            TokenTracker tt = session.getTokenTracker();
            long inputBefore = tt.getInputTokens();
            long outputBefore = tt.getOutputTokens();

            long startTime = System.currentTimeMillis();
            String result;
            try {
                result = loop.runStreaming(userMessage, callbacks::onToken, requestCallbacks);
            } catch (RuntimeException e) {
                // 通知调用方（onError 决定 abort/retry），随后仍向上抛出，
                // 保持 send 失败即抛异常的既有语义。
                log.error("[SEND] Session {} failed: {}", sessionId, e.getMessage(), e);
                session.getMetricsCollector().recordError(e.getClass().getSimpleName());
                callbackResolver.notifyError(callbacks, e);
                throw e;
            }
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("[SEND] runStreaming completed in {}ms, result length={}, toolCalls={}",
                    elapsed, result != null ? result.length() : 0, loop.getLastToolCallCount());

            HmsResponse response = buildResponse(session, loop, tt, result, inputBefore, outputBefore);
            callbacks.onComplete(response);
            return response;
        }
    }


    // ==================== 会话控制 ====================

    /**
     * 取消指定会话正在执行的请求（会话不存在时静默忽略）。
     * <p>
     * <b>不得持有会话锁</b>：{@code send} 全程持有它，取消请求若也去抢锁，
     * 就只能等对话自然结束后才生效 —— 即彻底失效。{@code AgentLoop.cancel()}
     * 只翻转一个 {@code volatile} 标志，本身无需互斥。
     */
    @Override
    public void cancel(String sessionId) {
        LoopSession session = sessions.get(sessionId);
        if (session != null) {
            session.getAgentLoop().cancel();
        }
    }

    /**
     * 手动触发一次全量上下文压缩 —— 忽略 token 阈值与熔断器。
     * <p>
     * <b>为什么必须持会话锁，且必须先确认无请求在跑</b>：压缩会整体替换消息历史，
     * 与正在执行的 {@code send} 并发就可能产出 tool_use 缺少配对 tool_result 的历史，
     * 被上游以 400 拒绝。而且这种损坏是<b>持久的</b> —— 历史已经被替换掉，此后每一轮
     * 请求都会拿同一份坏历史再撞 400。所以 {@code isExecuting()} 预检是正确性要求，
     * 不是性能优化。
     * <p>
     * <b>为什么不能照搬 {@code pause}</b>：{@link LoopSession#pause()} 只翻转一个
     * volatile 状态位，而 {@code AgentLoop.run} 从不读该状态 —— 正在跑的循环不会被打断。
     * 「pause → compact → resume」因此无法保证压缩期间没有请求在执行。
     * <p>
     * <b>为什么不能照搬 {@link #cancel}</b>：{@code cancel} 刻意不持会话锁，因为它必须
     * 能打断正持锁的 {@code send}。compact 的语义正好相反 —— 它必须排在 {@code send}
     * 之外，只能靠持锁实现。
     * <p>
     * <b>双重检查</b>：锁外那次读的是 {@link LoopSession#isExecuting()} 背后的
     * {@code AtomicInteger}，目的是在已有请求在跑时<b>不去排队等锁</b>（{@code send}
     * 可能跑几十秒），而立刻返回一个调用方能处理的错误。锁内那次由持锁本身已经保证
     * （{@code send} 是先 {@code synchronized(session)} 再自增 activeRequests），
     * 保留它属防御性 —— 防止将来新增不持锁却自增 activeRequests 的旁路。
     * <p>
     * 用 {@code requireExistingSession} 而非 {@code requireSession}：与
     * {@link #getSessionHooks} 同理，PAUSED 会话应允许管理操作，
     * 「暂停 → 压缩 → 恢复」正是本方法最合理的使用场景。
     */
    @Override
    public CompactionResult compactNow(String sessionId) {
        LoopSession session = requireExistingSession(sessionId);
        if (session.isExecuting()) {
            throw new IllegalStateException(
                    "Session is executing; cancel or wait before compacting: " + sessionId);
        }
        synchronized (session) {
            if (session.isExecuting()) {
                throw new IllegalStateException(
                        "Session is executing; cancel or wait before compacting: " + sessionId);
            }
            AgentLoop loop = session.getAgentLoop();
            AutoCompactManager mgr = loop.getAutoCompactManager();
            if (mgr == null) {
                throw new IllegalStateException(
                        "Auto-compact is not configured for session: " + sessionId);
            }
            // 传 copyMessageHistory 而非活列表：它在 messageHistory 的锁内返回副本，
            // 于是 AutoCompactManager.succeed 里读到的 before.size() 不会被随后的
            // replaceHistory 就地改写所影响。
            return mgr.compactNow(loop::copyMessageHistory, loop::replaceHistory);
        }
    }

    /**
     * 重置指定会话自动压缩的熔断器。
     * <p>
     * 不持会话锁：{@link AutoCompactManager#resetCircuitBreaker()} 只把两个裸字段
     * 清零，没有「读-改-写」的复合不变式需要保护。与 {@link #compactNow} 不同 ——
     * 后者要整体替换消息历史，必须排在 {@code send} 之外，所以才需要持锁。
     * <p>
     * 用 {@code requireExistingSession}：与 {@code compactNow} 同理，PAUSED 会话
     * 应允许管理操作。
     */
    @Override
    public boolean resetCompactionCircuitBreaker(String sessionId) {
        LoopSession session = requireExistingSession(sessionId);
        AutoCompactManager mgr = session.getAgentLoop().getAutoCompactManager();
        if (mgr == null) {
            throw new IllegalStateException(
                    "Auto-compact is not configured for session: " + sessionId);
        }
        boolean wasBroken = mgr.isCircuitBroken();
        mgr.resetCircuitBreaker();
        log.info("Session {} compaction circuit breaker reset (was broken: {})",
                sessionId, wasBroken);
        return wasBroken;
    }

    /** 获取指定会话累计的 Token 使用统计。 */
    @Override
    public TokenStats getSessionTokenStats(String sessionId) {
        LoopSession session = requireExistingSession(sessionId);
        return statsOf(session);
    }

    /**
     * 汇总一个会话的用量与费用。
     * <p>
     * 模型名在此<b>现取</b>（{@link #resolveModelName()} 读 ChatModel 的生效选项）
     * 而不用会话创建时的快照：运行时换模型后，费用应按当前实际所用的模型计算。
     * 定价未知时 {@link TokenStats#cost()} 为 null，由调用方呈现为「定价未知」。
     */
    private TokenStats statsOf(LoopSession session) {
        return TokenStats.of(session.getTokenTracker().usageSnapshot(),
                tokenPricing, resolveModelName());
    }

    /** 更新指定会话的会话级提示词，并同步刷新该会话 AgentLoop 的系统提示词。 */
    @Override
    public void updateSessionPrompt(String sessionId, String sessionPrompt) {
        LoopSession session = requireExistingSession(sessionId);
        // 写回 LoopSession 中存储的会话提示词
        session.setSessionPrompt(sessionPrompt);
        // 重建 AgentLoop 的 systemPrompt（Agent 基座 + 记忆摘要 + 会话），不依赖具体实现
        String fullPrompt = buildFullPrompt(sessionPrompt);
        session.getAgentLoop().updateSystemPrompt(fullPrompt);
        log.info("Session {} prompt updated (full prompt: {} chars)", sessionId, fullPrompt.length());
    }

    /** 获取指定会话的工具注册中心（用于会话级工具增删）。 */
    @Override
    public ToolRegistry getSessionToolRegistry(String sessionId) {
        return requireExistingSession(sessionId).getToolRegistry();
    }

    // ==================== 扩展点 ====================

    /**
     * 获取指定会话的 Hook 管理器 —— 注册工具调用的前后拦截器。
     * <p>
     * 用 {@code requireExistingSession} 而非 {@code requireSession}：暂停中的会话
     * 也应允许调整钩子（恢复后即生效），注册钩子本身不是一次「发消息」。
     */
    @Override
    public HookManager getSessionHooks(String sessionId) {
        return requireExistingSession(sessionId).getAgentLoop().getHookManager();
    }

    /**
     * 获取指定会话的权限拒绝追踪器 —— 观测拒绝次数与阈值回调。
     * <p>
     * 与 {@link #getSessionHooks} 同理不拒绝 PAUSED 状态：查询/注册观测回调
     * 在会话暂停时同样应可用。
     */
    @Override
    public DenialTracker getSessionDenials(String sessionId) {
        return requireExistingSession(sessionId).getAgentLoop().getDenialTracker();
    }

    // ==================== 信息查询 ====================

    /** 获取指定会话的完整信息（会话不存在时返回 null）。 */
    @Override
    public SessionInfo getSessionInfo(String sessionId) {
        LoopSession session = sessions.get(sessionId);
        if (session == null) return null;

        return snapshotOf(session);
    }

    /**
     * 为会话生成一份 {@link SessionInfo} 快照。
     * <p>
     * {@code getSessionInfo} 与 {@code listSessions} 共用 —— 两处曾各写一遍相同的
     * 字段列表，新增字段时漏改一处就会让单查与列表给出不一致的视图。
     */
    private SessionInfo snapshotOf(LoopSession session) {
        TokenTracker tt = session.getTokenTracker();
        TokenStats stats = statsOf(session);
        return new SessionInfo(
                session.getSessionId(),
                session.getStatus(),
                // 活动状态存在 AgentLoop 上 —— 它才是知道自己在干什么的对象
                session.getAgentLoop().getActivity(),
                session.getSessionPrompt(),
                List.copyOf(session.getToolRegistry().getToolNames()),
                session.getCreatedAt(),
                session.getLastAccessTime(),
                session.idleSeconds(),
                tt.getInputTokens(),
                tt.getOutputTokens(),
                stats.cost(),
                stats.pricingModel(),
                session.getMessageCount(),
                agentId
        );
    }

    /** 获取指定会话的历史消息（按时间顺序，返回不可变副本）。 */
    @Override
    public List<ChatMessage> getSessionMessages(String sessionId) {
        LoopSession session = requireExistingSession(sessionId);
        return MessageHistoryMapper.toChatMessages(session.getAgentLoop().copyMessageHistory());
    }


    /** 列出当前所有会话的信息。 */
    @Override
    public List<SessionInfo> listSessions() {
        List<SessionInfo> result = new ArrayList<>();
        for (LoopSession session : sessions.values()) {
            result.add(snapshotOf(session));
        }
        return result;
    }

    /** 获取处于 ACTIVE 状态的会话数。 */
    @Override
    public int getActiveSessionCount() {
        return (int) sessions.values().stream()
                .filter(s -> s.getStatus() == SessionStatus.ACTIVE)
                .count();
    }

    /** 获取指定会话的指标收集器（用于查询消息/API 调用/工具使用等指标）。 */
    @Override
    public MetricsCollector getSessionMetrics(String sessionId) {
        return requireExistingSession(sessionId).getMetricsCollector();
    }

    // ==================== 运维 ====================

    /**
     * 清理空闲超过指定秒数的会话并返回清理数量。
     * <p>
     * <b>执行中的会话被豁免</b>：{@code lastAccessTime} 只在请求进入时刷新，
     * 因此一个运行时长超过空闲阈值的请求（长工具链、深度压缩、等待用户回答）
     * 在执行途中就会显得「已空闲」。若照此回收，AgentLoop 会被取消、
     * 会话被移出映射，而调用方的 {@code send} 仍在阻塞，最终拿到截断结果。
     *
     * @param idleTimeoutSeconds 空闲超时阈值（秒）
     * @return 被清理的会话数量
     */
    @Override
    public int cleanupIdleSessions(long idleTimeoutSeconds) {
        int cleaned = 0;
        for (Map.Entry<String, LoopSession> entry : List.copyOf(sessions.entrySet())) {
            LoopSession session = entry.getValue();
            if (session.isExecuting()) {
                continue;
            }
            // 后台会话豁免空闲回收：它的存活期应与 Agent 对齐，而不是与「最近一次心跳」
            // 对齐。否则「心跳间隔 1 小时 + 空闲超时 30 分钟」的配置会让每次心跳都
            // 重建一个空会话，产物历史被反复抹掉 —— 那正是它该保存的东西。
            if (backendSessions.contains(entry.getKey())) {
                continue;
            }
            if (session.idleSeconds() > idleTimeoutSeconds) {
                sessions.remove(entry.getKey());
                session.destroy();
                cleaned++;
                log.info("Cleaned idle session: {} (idle: {}s)", entry.getKey(), session.idleSeconds());
            }
        }
        return cleaned;
    }

    // ==================== 内部 API（供 PromptManager/ToolManager 使用） ====================

    /** 获取内部 LoopSession（不要求 ACTIVE 状态，不触发 touch）。 */
    LoopSession getSessionInternal(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 关闭管理器 —— 取消清理任务、销毁所有存活会话，并在自建池时关闭它。
     * <p>
     * 作为 Spring Bean 时由容器在关闭阶段自动调用（{@code @Bean} 的
     * {@code destroyMethod} 默认推断 {@code close}）。手动构造时应显式调用，
     * 否则调度线程会一直存活 —— 反复创建容器的测试尤其容易泄漏。
     * <p>
     * <b>取消任务与关闭线程池是两件事，必须分开处理</b>：调度池可能是多个 Agent
     * 共享的（{@code ApiAutoConfiguration} 注入的那一个），无条件
     * {@code shutdownNow()} 会把其他 Agent 的心跳一并打死。{@link #ownsCleanupScheduler}
     * 记录的正是这个区别。
     */
    @Override
    public void close() {
        ScheduledFuture<?> task = cleanupTask;
        if (task != null) {
            task.cancel(false);
            cleanupTask = null;
        }
        if (ownsCleanupScheduler) {
            cleanupScheduler.shutdownNow();
        }
        for (Map.Entry<String, LoopSession> entry : List.copyOf(sessions.entrySet())) {
            sessions.remove(entry.getKey());
            try {
                entry.getValue().destroy();
            } catch (RuntimeException e) {
                log.debug("Error destroying session {} on close: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        backendSessions.clear();
        log.info("SessionManager closed (agent={})", agentId);
    }

    /**
     * 启动周期性空闲会话清理任务（按配置间隔执行，异常不影响后续调度）。
     * <p>
     * 句柄必须留存 —— 否则 {@link #close()} 想停掉这一个任务，唯一的办法就是
     * shutdown 整个（可能是共享的）线程池。
     */
    private void startCleanupTask() {
        cleanupTask = cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                int cleaned = cleanupIdleSessions(defaultIdleTimeoutSeconds);
                if (cleaned > 0) {
                    log.info("Idle cleanup: {} removed, {} remaining", cleaned, sessions.size());
                }
            } catch (Exception e) {
                log.error("Cleanup task error", e);
            }
        }, cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS);
    }

}
