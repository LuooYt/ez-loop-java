package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.core.HookManager;
import com.inspirationi.loop.core.TokenTracker;
import com.inspirationi.loop.core.compact.AutoCompactManager;
import com.inspirationi.loop.permission.DenialTracker;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.telemetry.MetricsCollector;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

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
    /** 空闲会话定期清理的调度线程池。 */
    private final ScheduledExecutorService cleanupScheduler;

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
        this.callbackResolver = new CallbackResolver(builder.userResponseTimeoutSeconds, "[SESSION]");

        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("session-cleanup-", 0).factory());
        startCleanupTask();
        log.info("SessionManager initialized (idleTimeout={}s, maxSessions={})",
                defaultIdleTimeoutSeconds, maxSessions);
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
        String sessionId = UUID.randomUUID().toString();
        log.info("Creating session: {}", sessionId);

        TokenTracker tokenTracker = newTokenTracker();
        MetricsCollector metrics = new MetricsCollector(sessionId);
        AgentLoop agentLoop = newAgentLoop(sessionId, tokenTracker,
                promptManager.buildFullPrompt(customSessionPrompt), true);
        agentLoop.setOnToolEvent(event -> metrics.recordToolUse(event.toolName()));

        LoopSession loopSession = new LoopSession(sessionId, agentLoop, tokenTracker,
                metrics, customSessionPrompt);

        // 容量检查放在插入处：先占位再校验，避免 size() 的 check-then-act 竞态
        // 让并发创建突破上限。
        sessions.put(sessionId, loopSession);
        if (sessions.size() > maxSessions) {
            sessions.remove(sessionId);
            loopSession.destroy();
            log.warn("Session creation rejected: limit {} reached", maxSessions);
            throw new HmsException(HmsErrorCode.SESSION_LIMIT_EXCEEDED,
                    "Cannot create session: limit of " + maxSessions
                            + " reached. Destroy idle sessions or raise "
                            + "hms-core.session.max-sessions.");
        }

        log.info("Session {} created (tools: {}, total sessions: {})",
                sessionId, agentLoop.getToolRegistry().size(), sessions.size());
        return sessionId;
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
     * 新建会话级 {@link TokenTracker}，并按当前模型名配置定价。
     * <p>
     * 定价影响 {@link TokenTracker#estimateCost()}；模型名解析失败时保留
     * TokenTracker 自身的默认定价（Claude Sonnet）。
     */
    private TokenTracker newTokenTracker() {
        TokenTracker tracker = new TokenTracker();
        String model = resolveModelName();
        if (model != null && !model.isBlank()) {
            tracker.setModel(model);
        }
        return tracker;
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
     * @return 模型名；无法解析时为 {@code null}，此时 TokenTracker 保留其自身的
     *         默认定价（Claude Sonnet）
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

    /** 销毁会话并从映射中移除，释放相关资源（会话不存在时抛异常）。 */
    @Override
    public void destroySession(String sessionId) {
        LoopSession session = sessions.remove(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
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
    private void registerAskUserCallbacks(ToolContext toolContext, HmsCallbacks callbacks) {
        toolContext.set(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                (BiFunction<String, List<String>, String>) (question, options) ->
                        callbackResolver.resolveAskUser(callbacks, question, options));
        toolContext.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                (Function<String, String>) prompt ->
                        callbackResolver.resolveAskUser(callbacks, prompt, null));
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
            registerAskUserCallbacks(toolContext, callbacks);

            // 构建请求级回调（不污染 AgentLoop 持久状态）
            AgentLoop.RequestCallbacks requestCallbacks = new AgentLoop.RequestCallbacks(
                    event -> {
                        session.getMetricsCollector().recordToolUse(event.toolName());
                        callbacks.onToolUse(event.toolName(), event.arguments(), event.result());
                    },
                    callbacks::onThinking,
                    req -> callbackResolver.resolvePermission(callbacks, req),
                    callbacks::onToken,
                    callbacks::onCompaction
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

    /** 获取指定会话累计的 Token 使用统计。 */
    @Override
    public TokenStats getSessionTokenStats(String sessionId) {
        LoopSession session = requireExistingSession(sessionId);
        TokenTracker tt = session.getTokenTracker();
        return TokenStats.of(tt.getInputTokens(), tt.getOutputTokens());
    }

    /** 更新指定会话的会话级提示词，并同步刷新该会话 AgentLoop 的系统提示词。 */
    @Override
    public void updateSessionPrompt(String sessionId, String sessionPrompt) {
        LoopSession session = requireExistingSession(sessionId);
        // 写回 LoopSession 中存储的会话提示词
        session.setSessionPrompt(sessionPrompt);
        // 重建 AgentLoop 的 systemPrompt = Global + Session（通过接口拼接，不依赖具体实现）
        String fullPrompt = promptManager.buildFullPrompt(sessionPrompt);
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
     * 10 个字段，新增字段时漏改一处就会让单查与列表给出不一致的视图。
     */
    private static SessionInfo snapshotOf(LoopSession session) {
        TokenTracker tt = session.getTokenTracker();
        return new SessionInfo(
                session.getSessionId(),
                session.getStatus(),
                session.getSessionPrompt(),
                List.copyOf(session.getToolRegistry().getToolNames()),
                session.getCreatedAt(),
                session.getLastAccessTime(),
                session.idleSeconds(),
                tt.getInputTokens(),
                tt.getOutputTokens(),
                session.getMessageCount()
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
     * 关闭管理器 —— 停止清理调度线程并销毁所有存活会话。
     * <p>
     * 作为 Spring Bean 时由容器在关闭阶段自动调用（{@code @Bean} 的
     * {@code destroyMethod} 默认推断 {@code close}）。手动构造时应显式调用，
     * 否则调度线程会一直存活 —— 反复创建容器的测试尤其容易泄漏。
     */
    @Override
    public void close() {
        cleanupScheduler.shutdownNow();
        for (Map.Entry<String, LoopSession> entry : List.copyOf(sessions.entrySet())) {
            sessions.remove(entry.getKey());
            try {
                entry.getValue().destroy();
            } catch (RuntimeException e) {
                log.debug("Error destroying session {} on close: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        log.info("SessionManager closed");
    }

    /** 启动周期性空闲会话清理任务（按配置间隔执行，异常不影响后续调度）。 */
    private void startCleanupTask() {
        cleanupScheduler.scheduleAtFixedRate(() -> {
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
