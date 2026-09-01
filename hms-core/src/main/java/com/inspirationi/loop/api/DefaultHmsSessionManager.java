package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.core.TokenTracker;
import com.inspirationi.loop.core.compact.AutoCompactManager;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.telemetry.MetricsCollector;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

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

    /** Jackson 对象映射器，用于将工具参数 JSON 解析为 Map（权限评估用）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

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

    /** 等待用户回答的默认上限秒数 —— 需容纳真人思考与操作时间。 */
    public static final long DEFAULT_USER_RESPONSE_TIMEOUT_SECONDS = 300;

    /**
     * 构造会话管理器（使用默认空闲超时 30 分钟、清理间隔 5 分钟）。
     */
    public DefaultHmsSessionManager(ChatModel chatModel, ToolRegistry globalToolRegistry,
                                    PermissionRuleEngine permissionEngine, PromptManager promptManager) {
        this(chatModel, globalToolRegistry, permissionEngine, promptManager,
                30 * 60, 5 * 60);
    }

    /**
     * 构造会话管理器，并启动空闲会话清理任务。
     *
     * @param chatModel               聊天模型
     * @param globalToolRegistry      全局工具注册中心
     * @param permissionEngine        权限规则引擎（可为 null）
     * @param promptManager           提示词管理器
     * @param defaultIdleTimeoutSeconds 默认空闲超时秒数
     * @param cleanupIntervalSeconds    空闲清理任务的执行间隔秒数
     */
    public DefaultHmsSessionManager(ChatModel chatModel, ToolRegistry globalToolRegistry,
                                    PermissionRuleEngine permissionEngine, PromptManager promptManager,
                                    long defaultIdleTimeoutSeconds, long cleanupIntervalSeconds) {
        this(chatModel, globalToolRegistry, permissionEngine, promptManager,
                defaultIdleTimeoutSeconds, cleanupIntervalSeconds,
                DEFAULT_USER_RESPONSE_TIMEOUT_SECONDS);
    }

    /**
     * 构造会话管理器，并启动空闲会话清理任务。
     *
     * @param chatModel               聊天模型
     * @param globalToolRegistry      全局工具注册中心
     * @param permissionEngine        权限规则引擎（可为 null）
     * @param promptManager           提示词管理器
     * @param defaultIdleTimeoutSeconds 默认空闲超时秒数
     * @param cleanupIntervalSeconds    空闲清理任务的执行间隔秒数
     * @param userResponseTimeoutSeconds 等待用户回答（AskUser / 权限确认）的上限秒数
     */
    public DefaultHmsSessionManager(ChatModel chatModel, ToolRegistry globalToolRegistry,
                                    PermissionRuleEngine permissionEngine, PromptManager promptManager,
                                    long defaultIdleTimeoutSeconds, long cleanupIntervalSeconds,
                                    long userResponseTimeoutSeconds) {
        this.chatModel = chatModel;
        this.globalToolRegistry = globalToolRegistry;
        this.permissionEngine = permissionEngine;
        this.promptManager = promptManager;
        this.defaultIdleTimeoutSeconds = defaultIdleTimeoutSeconds;
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
        this.userResponseTimeoutSeconds = userResponseTimeoutSeconds;

        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("session-cleanup-", 0).factory());
        startCleanupTask();
        log.info("SessionManager initialized (idleTimeout={}s)", defaultIdleTimeoutSeconds);
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

        // 两级提示词拼接（通过接口默认方法，不依赖具体实现类型）
        String fullPrompt = promptManager.buildFullPrompt(customSessionPrompt);

        // 从全局工具复制一份给此会话（实现两级工具隔离）
        ToolRegistry sessionToolRegistry = copyGlobalTools();

        TokenTracker tokenTracker = newTokenTracker();
        ToolContext toolContext = ToolContext.defaultContext();
        toolContext.set("TOOL_REGISTRY", sessionToolRegistry);

        AgentLoop agentLoop = new AgentLoop(chatModel, sessionToolRegistry, toolContext,
                fullPrompt, tokenTracker);
        // 压缩器必须绑定本会话的 tokenTracker —— 阈值判断读的是该 tracker 的
        // lastPromptTokens，绑到其他实例会导致 shouldAutoCompact() 恒为 false。
        agentLoop.setAutoCompactManager(new AutoCompactManager(chatModel, tokenTracker));

        // 注册子 Agent 工厂 —— 使用当前 ChatModel + 复制的工具集创建独立 AgentLoop
        toolContext.set(
                com.inspirationi.loop.tool.impl.AgentTool.AGENT_FACTORY_KEY,
                (java.util.function.Function<String, String>) prompt -> {
                    TokenTracker subTracker = newTokenTracker();
                    ToolRegistry subToolRegistry = copyGlobalTools();
                    ToolContext subContext = ToolContext.defaultContext();
                    subContext.set("TOOL_REGISTRY", subToolRegistry);
                    AgentLoop subAgent = new AgentLoop(chatModel, subToolRegistry, subContext,
                            PromptI18n.t(PromptI18n.KEY_SUBAGENT_SESSION_PROMPT, DEFAULT_SUBAGENT_SYSTEM_PROMPT),
                            subTracker);
                    subAgent.setAutoCompactManager(new AutoCompactManager(chatModel, subTracker));
                    if (permissionEngine != null) {
                        subAgent.setPermissionEngine(permissionEngine);
                    }
                    subAgent.setOnPermissionRequest(req ->
                            PermissionChoice.ALLOW_ONCE);
                    return subAgent.run(prompt);
                });

        if (permissionEngine != null) {
            agentLoop.setPermissionEngine(permissionEngine);
        }

        // 使用 PermissionRuleEngine 评估权限（非无条件放行）
        agentLoop.setOnPermissionRequest(req -> {
            if (permissionEngine != null) {
                var decision = permissionEngine.evaluate(
                        req.toolName(),
                        parseToolArguments(req.arguments()),
                        Tool.RiskLevel.MEDIUM,  // 无具体工具对象时默认中等风险
                        toolContext);
                if (decision.isAllowed()) {
                    return PermissionChoice.ALLOW_ONCE;
                } else if (decision.isDenied()) {
                    log.info("[{}] Permission denied by rule: {} — {}", sessionId, req.toolName(), decision.reason());
                    return PermissionChoice.DENY_ONCE;
                }
                // needs ASK → fall through to auto-allow for headless mode
            }
            log.debug("[{}] Permission auto-allowed (headless): {}", sessionId, req.toolName());
            return PermissionChoice.ALLOW_ONCE;
        });

        MetricsCollector metrics = new MetricsCollector(sessionId);

        agentLoop.setOnToolEvent(event -> {
            metrics.recordToolUse(event.toolName());
        });

        LoopSession loopSession = new LoopSession(sessionId, agentLoop, tokenTracker,
                metrics, customSessionPrompt);
        sessions.put(sessionId, loopSession);

        log.info("Session {} created (tools: {}, total sessions: {})",
                sessionId, sessionToolRegistry.size(), sessions.size());
        return sessionId;
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
     * 从 ChatModel 的默认选项中读取模型名 —— 直接取用生效配置，
     * 避免与环境变量/yml 两处配置源不一致。
     *
     * @return 模型名；无法解析时为 {@code null}
     */
    private String resolveModelName() {
        try {
            ChatOptions options = chatModel.getDefaultOptions();
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
     * 会话不存在抛 {@link IllegalArgumentException}，处于 PAUSED 状态抛 {@link IllegalStateException}。
     */
    private LoopSession requireSession(String sessionId) {
        LoopSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException(
                    "Session not found: " + sessionId + ". Call createSession() first.");
        }
        if (session.getStatus() == SessionStatus.PAUSED) {
            throw new IllegalStateException("Session is paused: " + sessionId);
        }
        session.touch();
        return session;
    }

    /**
     * 获取已存在的会话，仅校验是否存在，不拒绝 PAUSED 状态。
     * 用于 resume 以及只读查询（token/metrics），这些操作在会话暂停时也应可用。
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
        // 会话级互斥：同一会话同一时间只能有一个请求在执行
        synchronized (session) {
            session.getMetricsCollector().recordUserMessage();

            AgentLoop loop = session.getAgentLoop();
            String result = loop.run(userMessage);

            TokenTracker tt = session.getTokenTracker();
            session.getMetricsCollector().recordApiCall(tt.getInputTokens(), tt.getOutputTokens());

            return HmsResponse.ok(result, loop.getLastToolCallCount(),
                    tt.getInputTokens(), tt.getOutputTokens());
        }
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
        synchronized (session) {
            session.getMetricsCollector().recordUserMessage();

            AgentLoop loop = session.getAgentLoop();
            String result = loop.runStreaming(userMessage, onToken);

            TokenTracker tt = session.getTokenTracker();
            session.getMetricsCollector().recordApiCall(tt.getInputTokens(), tt.getOutputTokens());

            return HmsResponse.ok(result, loop.getLastToolCallCount(),
                    tt.getInputTokens(), tt.getOutputTokens());
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
        synchronized (session) {
            session.getMetricsCollector().recordUserMessage();

            AgentLoop loop = session.getAgentLoop();
            ToolContext toolContext = loop.getToolContext();

            // 注册 AskUser 回调链：同步阻塞 → 异步 → ToolContext 回退
            toolContext.set(
                    com.inspirationi.loop.tool.impl.AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                    (BiFunction<String, java.util.List<String>, String>) (question, options) ->
                            resolveAskUser(callbacks, question, options));

            // 注册简单文本回调（作为二级回退）
            toolContext.set(
                    com.inspirationi.loop.tool.impl.AskUserQuestionTool.USER_INPUT_CALLBACK,
                    (java.util.function.Function<String, String>) prompt ->
                            resolveAskUser(callbacks, prompt, null));

            // 构建请求级回调（不污染 AgentLoop 持久状态）
            AgentLoop.RequestCallbacks requestCallbacks = new AgentLoop.RequestCallbacks(
                    event -> {
                        session.getMetricsCollector().recordToolUse(event.toolName());
                        callbacks.onToolUse(event.toolName(), event.arguments(), event.result());
                    },
                    callbacks::onThinking,
                    req -> resolvePermission(callbacks, req),
                    callbacks::onToken
            );

            long startTime = System.currentTimeMillis();
            String result;
            try {
                result = loop.runStreaming(userMessage, callbacks::onToken, requestCallbacks);
            } catch (RuntimeException e) {
                // 通知调用方（onError 决定 abort/retry），随后仍向上抛出，
                // 保持 send 失败即抛异常的既有语义。
                log.error("[SEND] Session {} failed: {}", sessionId, e.getMessage(), e);
                notifyError(callbacks, e);
                throw e;
            }
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("[SEND] runStreaming completed in {}ms, result length={}, toolCalls={}",
                    elapsed, result != null ? result.length() : 0, loop.getLastToolCallCount());

            TokenTracker tt = session.getTokenTracker();
            session.getMetricsCollector().recordApiCall(tt.getInputTokens(), tt.getOutputTokens());

            HmsResponse response = HmsResponse.ok(result, loop.getLastToolCallCount(),
                    tt.getInputTokens(), tt.getOutputTokens());
            callbacks.onComplete(response);
            return response;
        }
    }

    // ==================== 回调解析（同步 → 异步 → 回退） ====================

    /**
     * 解析用户提问的回答：同步回调 → 异步回调 → 返回 {@code null} 交给 ToolContext 回退链。
     *
     * @param callbacks 回调集合
     * @param question  问题文本
     * @param options   可选答案列表（可为 null，表示自由文本回答）
     * @return 用户回答；无人应答时为 {@code null}
     */
    private String resolveAskUser(HmsCallbacks callbacks, String question,
                                  java.util.List<String> options) {
        String answer = callbacks.onAskUser(question, options);
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        try {
            String asyncAnswer = callbacks.onAskUserAsync(question, options)
                    .get(userResponseTimeoutSeconds, TimeUnit.SECONDS);
            if (asyncAnswer != null && !asyncAnswer.isBlank()) {
                return asyncAnswer;
            }
        } catch (Exception e) {
            log.debug("Async askUser timed out or failed after {}s: {}",
                    userResponseTimeoutSeconds, e.getMessage());
        }
        return null;  // 回退到 ToolContext 链
    }

    /**
     * 解析权限确认：同步回调 → 异步回调 → 拒绝（fail-safe）。
     * <p>
     * 同步回调返回 {@code null} 或空字符串视为弃权并回退到异步回调，
     * 因此只覆写异步回调的集成方也能正常工作。
     *
     * @param callbacks 回调集合
     * @param req       权限请求
     * @return 允许则 {@link PermissionChoice#ALLOW_ONCE}，否则 {@link PermissionChoice#DENY_ONCE}
     */
    private PermissionChoice resolvePermission(HmsCallbacks callbacks, AgentLoop.PermissionRequest req) {
        String description = req.activityDescription() != null ? req.activityDescription() : "";
        String choice = callbacks.onPermissionRequest(req.toolName(), description);
        if ("allow".equalsIgnoreCase(choice)) {
            return PermissionChoice.ALLOW_ONCE;
        }
        if ("deny".equalsIgnoreCase(choice)) {
            return PermissionChoice.DENY_ONCE;
        }
        try {
            String asyncChoice = callbacks.onPermissionRequestAsync(req.toolName(), description)
                    .get(userResponseTimeoutSeconds, TimeUnit.SECONDS);
            if ("allow".equalsIgnoreCase(asyncChoice)) {
                return PermissionChoice.ALLOW_ONCE;
            }
        } catch (Exception e) {
            log.debug("Async permission request timed out after {}s: {}",
                    userResponseTimeoutSeconds, e.getMessage());
        }
        return PermissionChoice.DENY_ONCE;
    }

    /** 通知调用方发生错误；回调自身抛出的异常不得掩盖原始异常。 */
    private void notifyError(HmsCallbacks callbacks, Throwable error) {
        try {
            callbacks.onError(error);
        } catch (RuntimeException callbackFailure) {
            log.warn("onError callback itself failed: {}", callbackFailure.getMessage());
        }
    }

    // ==================== 会话控制 ====================

    /** 取消指定会话正在执行的请求（会话不存在时静默忽略）。 */
    @Override
    public void cancel(String sessionId) {
        LoopSession session = sessions.get(sessionId);
        if (session != null) {
            synchronized (session) {
                session.getAgentLoop().cancel();
            }
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

    // ==================== 信息查询 ====================

    /** 获取指定会话的完整信息（会话不存在时返回 null）。 */
    @Override
    public SessionInfo getSessionInfo(String sessionId) {
        LoopSession session = sessions.get(sessionId);
        if (session == null) return null;

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
        return convertHistory(session.getAgentLoop().copyMessageHistory());
    }

    /**
     * 将 Spring AI 消息历史转换为中立 DTO 列表。
     * <p>
     * 工具调用（AssistantMessage.ToolCall）与其结果（ToolResponseMessage.ToolResponse）
     * 通过相同 id 配对为一条含 name/arguments/result 的 tool 记录。
     */
    private static List<ChatMessage> convertHistory(List<Message> history) {
        List<ChatMessage> out = new ArrayList<>();
        // 尚未配对的工具调用：toolCallId -> [name, arguments]
        Map<String, String[]> pendingCalls = new LinkedHashMap<>();

        for (Message m : history) {
            switch (m.getMessageType()) {
                case SYSTEM -> out.add(new ChatMessage("system",
                        ((SystemMessage) m).getText(), null, null, null));
                case USER -> out.add(new ChatMessage("user",
                        ((UserMessage) m).getText(), null, null, null));
                case ASSISTANT -> {
                    AssistantMessage am = (AssistantMessage) m;
                    // 有文本 → 一条 assistant 文本记录（工具中转态的空白 assistant 不产生气泡）
                    if (am.getText() != null && !am.getText().isBlank()) {
                        out.add(new ChatMessage("assistant", am.getText(), null, null, null));
                    }
                    // 工具调用暂存，等待紧随的 ToolResponse 配对
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        pendingCalls.put(tc.id(), new String[]{tc.name(), tc.arguments()});
                    }
                }
                case TOOL -> {
                    ToolResponseMessage trm = (ToolResponseMessage) m;
                    for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                        String[] call = (r.id() != null) ? pendingCalls.remove(r.id()) : null;
                        out.add(new ChatMessage("tool",
                                null,
                                (call != null) ? call[0] : r.name(),
                                (call != null) ? call[1] : null,
                                r.responseData()));
                    }
                }
            }
        }
        // 兜底：极少数未捕获结果的 toolCall 原样输出
        for (var e : pendingCalls.entrySet()) {
            out.add(new ChatMessage("tool", null, e.getValue()[0], e.getValue()[1], null));
        }
        return List.copyOf(out);
    }

    /** 列出当前所有会话的信息。 */
    @Override
    public List<SessionInfo> listSessions() {
        List<SessionInfo> result = new ArrayList<>();
        for (LoopSession session : sessions.values()) {
            TokenTracker tt = session.getTokenTracker();
            result.add(new SessionInfo(
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
            ));
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
     *
     * @param idleTimeoutSeconds 空闲超时阈值（秒）
     * @return 被清理的会话数量
     */
    @Override
    public int cleanupIdleSessions(long idleTimeoutSeconds) {
        int cleaned = 0;
        for (Map.Entry<String, LoopSession> entry : List.copyOf(sessions.entrySet())) {
            LoopSession session = entry.getValue();
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

    /** 解析工具参数 JSON 为 Map（用于权限评估） */
    private static Map<String, Object> parseToolArguments(String toolArgs) {
        if (toolArgs == null || toolArgs.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(toolArgs, Map.class);
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
