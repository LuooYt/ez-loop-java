package com.inspirationi.loop.core;

import com.inspirationi.loop.core.compact.AutoCompactManager;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.permission.DenialTracker;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.permission.PermissionTypes.PermissionDecision;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Agent 循环 —— 多轮对话的核心执行引擎。
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>{@link #run(String)} —— 阻塞模式，等待完整响应后返回</li>
 *   <li>{@link #runStreaming(String, Consumer)} —— 流式模式，逐 token 实时输出</li>
 * </ul>
 * 使用 ChatModel（非 ChatClient）的显式循环，完整控制每一轮：
 * <ol>
 *   <li>构建 Prompt（消息历史 + 系统提示 + 工具定义）</li>
 *   <li>调用 ChatModel.call() 或 ChatModel.stream()</li>
 *   <li>检查工具调用 → 权限确认 → 执行工具 → 结果回传</li>
 *   <li>循环直到无工具调用或达到最大迭代</li>
 * </ol>
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 单轮最大迭代次数，防止无限循环 */
    private static final int MAX_ITERATIONS = 50;

    /** 用户中断标记 —— 追加到返回文本（中文，经 {@link PromptI18n} 按系统语言取用）。 */
    public static final String DEFAULT_LOOP_INTERRUPTED = "[用户已中断]";
    /** 达到最大迭代次数警告标记 —— 追加到返回文本（中文，经 {@link PromptI18n} 按系统语言取用）。 */
    public static final String DEFAULT_LOOP_MAX_ITERATIONS = "[警告：已达到最大循环迭代次数限制]";

    /** 语言模型客户端 —— 负责实际的对话推理调用 */
    private final ChatModel chatModel;
    /** 工具注册中心 —— 提供本次循环可调用的工具回调列表 */
    private final ToolRegistry toolRegistry;
    /** 工具执行上下文 —— 携带工具运行的运行时环境 */
    private final ToolContext toolContext;
    /** 系统提示词 —— 定义 Agent 的角色与行为 */
    private final String systemPrompt;
    /** Token 使用量追踪器 —— 记录每次 API 调用的 token 消耗 */
    private final TokenTracker tokenTracker;
    /** Hook 管理器 —— 工具调用前后的拦截钩子 */
    private final HookManager hookManager;

    /** 拒绝追踪器 */
    private final DenialTracker denialTracker = new DenialTracker();

    /** 工具执行器（拆分出的权限+Hook+执行逻辑） */
    private final AgentToolExecutor toolExecutor;

    /** 中断标志 —— 用于取消当前运行中的 Agent 循环 */
    private volatile boolean cancelled = false;

    /** 消息历史 —— 自行管理，不依赖 Spring AI ChatMemory */
    private final List<Message> messageHistory = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 权限规则引擎（会话级持久，为 null 时使用回调方式） */
    private PermissionRuleEngine permissionEngine;

    /** 自动压缩管理器（会话级持久） */
    private AutoCompactManager autoCompactManager;

    /**
     * 构造 AgentLoop（使用默认的 {@link TokenTracker} 实例）。
     */
    public AgentLoop(ChatModel chatModel, ToolRegistry toolRegistry,
                     ToolContext toolContext, String systemPrompt) {
        this(chatModel, toolRegistry, toolContext, systemPrompt, new TokenTracker());
    }

    /**
     * 构造 AgentLoop（可注入自定义 {@link TokenTracker}）。
     * <p>构造时将系统提示词作为第一条 SystemMessage 写入消息历史。</p>
     */
    public AgentLoop(ChatModel chatModel, ToolRegistry toolRegistry,
                     ToolContext toolContext, String systemPrompt, TokenTracker tokenTracker) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
        this.systemPrompt = systemPrompt;
        this.tokenTracker = tokenTracker;
        this.hookManager = new HookManager();
        this.toolExecutor = new AgentToolExecutor(hookManager, toolContext, denialTracker);
        this.messageHistory.add(new SystemMessage(systemPrompt));
    }

    /** 设置权限规则引擎（会话级持久），并同步给工具执行器 */
    public void setPermissionEngine(PermissionRuleEngine engine) {
        this.permissionEngine = engine;
        this.toolExecutor.setPermissionEngine(engine);
    }

    /** 设置自动压缩管理器（会话级持久，上下文过长时自动压缩历史） */
    public void setAutoCompactManager(AutoCompactManager manager) {
        this.autoCompactManager = manager;
    }

    public AutoCompactManager getAutoCompactManager() {
        return autoCompactManager;
    }

    // ==================== 持久回调（会话级默认值，会被请求级 RequestCallbacks 覆盖） ====================

    /** 助手文本回调：在每次助手回复时通知 UI（仅阻塞模式使用） */
    private Consumer<String> onAssistantMessage;

    /** 流式输出开始回调：通知 UI 停止 spinner */
    private Runnable onStreamStart;

    /** 持久工具事件回调（当请求未提供 RequestCallbacks 时使用） */
    private Consumer<ToolEvent> onToolEvent;

    /** 持久权限确认回调（当请求未提供 RequestCallbacks 时使用） */
    private Function<PermissionRequest, PermissionChoice> onPermissionRequest;

    /** 持久 Thinking 内容回调（当请求未提供 RequestCallbacks 时使用） */
    private Consumer<String> onThinkingContent;

    /** 设置持久助手文本回调（仅阻塞模式使用，通知 UI 完整回复） */
    public void setOnAssistantMessage(Consumer<String> onAssistantMessage) {
        this.onAssistantMessage = onAssistantMessage;
    }

    /** 设置持久流式开始回调（首个 token 到达时通知 UI 停止 spinner） */
    public void setOnStreamStart(Runnable onStreamStart) {
        this.onStreamStart = onStreamStart;
    }

    /** 设置持久工具事件回调（工具各阶段 START/PROGRESS/END 通知 UI） */
    public void setOnToolEvent(Consumer<ToolEvent> onToolEvent) {
        this.onToolEvent = onToolEvent;
    }

    /** 设置持久权限确认回调（工具需要用户授权时询问） */
    public void setOnPermissionRequest(Function<PermissionRequest, PermissionChoice> onPermissionRequest) {
        this.onPermissionRequest = onPermissionRequest;
    }

    /** 设置持久 Thinking 内容回调（透出模型思考过程） */
    public void setOnThinkingContent(Consumer<String> onThinkingContent) {
        this.onThinkingContent = onThinkingContent;
    }

    // ==================== 请求级回调记录 ====================

    /**
     * 单次请求的回调集合 —— 每次 run()/runStreaming() 传入，请求结束后不再持有引用。
     * 解决持久 setter 模式在多次连续调用时的状态污染问题。
     */
    public record RequestCallbacks(
            Consumer<ToolEvent> onToolEvent,
            Consumer<String> onThinkingContent,
            Function<PermissionRequest, PermissionChoice> onPermissionRequest,
            Consumer<String> onToken
    ) {}

    /** 取消当前运行中的 Agent 循环 */
    public void cancel() {
        cancelled = true;
    }

    /** 重置取消标志（每次新的循环开始时调用） */
    private void resetCancel() {
        cancelled = false;
    }

    // ==================== 阻塞模式 ====================

    /** 最近一次 run/runStreaming 调用中的工具调用次数 */
    private final java.util.concurrent.atomic.AtomicInteger lastToolCallCount = new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * 阻塞执行一轮用户输入的完整 Agent 循环。
     * 等待完整响应后才返回。
     */
    public String run(String userInput) {
        return run(userInput, null);
    }

    /**
     * 阻塞执行（带请求级回调）。
     */
    public String run(String userInput, RequestCallbacks callbacks) {
        messageHistory.add(new UserMessage(userInput));
        return executeLoop(false, null, callbacks);
    }

    // ==================== 流式模式 ====================

    /**
     * 流式执行一轮用户输入的完整 Agent 循环。
     * 文本逐 token 通过 onToken 回调实时输出到终端。
     *
     * @param userInput 用户输入文本
     * @param onToken   每个文本 token 的实时回调（用于终端逐字显示）
     * @return 最终完整的助手回复文本
     */
    public String runStreaming(String userInput, Consumer<String> onToken) {
        return runStreaming(userInput, onToken, null);
    }

    /**
     * 流式执行（带请求级回调）。
     */
    public String runStreaming(String userInput, Consumer<String> onToken,
                               RequestCallbacks callbacks) {
        messageHistory.add(new UserMessage(userInput));
        return executeLoop(true, onToken, callbacks);
    }

    // ==================== 核心循环（统一阻塞/流式） ====================

    /**
     * 核心循环 —— 统一驱动阻塞/流式两种模式的多轮对话执行。
     * <p>
     * 流程：
     * <ol>
     *   <li>重置取消标志与工具调用计数，并将请求级回调注入工具执行器</li>
     *   <li>构建 Prompt（消息历史 + 系统提示 + 工具定义），调用 ChatModel</li>
     *   <li>记录 Token 用量，将助手消息追加到历史，更新最近助手文本</li>
     *   <li>若助手无工具调用则循环结束；否则执行工具调用并回传结果</li>
     *   <li>工具调用后触发自动压缩检查，进入下一轮迭代</li>
     *   <li>用户取消或达到最大迭代次数时强制退出</li>
     * </ol>
     *
     * @param streaming        是否流式模式
     * @param onToken          流式模式下逐 token 文本回调
     * @param requestCallbacks 请求级回调（可为 null，回退到持久回调）
     * @return 最终完整的助手回复文本
     */
    private String executeLoop(boolean streaming, Consumer<String> onToken,
                               RequestCallbacks requestCallbacks) {
        resetCancel();
        // 重置本轮工具调用计数
        lastToolCallCount.set(0);
        // 将请求级回调注入到 toolExecutor；若请求未提供则回退到持久回调
        if (requestCallbacks != null) {
            toolExecutor.setRequestCallbacks(requestCallbacks);
        } else {
            // 回退：用持久回调构建临时 RequestCallbacks
            toolExecutor.setRequestCallbacks(new RequestCallbacks(
                    onToolEvent, onThinkingContent, onPermissionRequest, onToken));
        }

        List<ToolCallback> springCallbacks = toolRegistry.toCallbacks(toolContext);
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(springCallbacks)
                .internalToolExecutionEnabled(false)
                .build();

        int iteration = 0;
        String lastAssistantText = "";

        while (iteration < MAX_ITERATIONS) {
            // 检查取消标志
            if (cancelled) {
                log.info("Agent loop cancelled by user at iteration {}", iteration);
                lastAssistantText += "\n\n" + PromptI18n.t(PromptI18n.KEY_LOOP_INTERRUPTED, DEFAULT_LOOP_INTERRUPTED);
                break;
            }

            iteration++;
            log.info("[LOOP] === Iteration {} start ({}) ===", iteration, streaming ? "streaming" : "blocking");

            Prompt prompt = new Prompt(List.copyOf(messageHistory), options);

            // 调用 AI 并获取结果
            IterationResult result;
            if (streaming) {
                result = streamIteration(prompt, onToken);
            } else {
                result = blockingIteration(prompt, requestCallbacks);
            }

            log.info("[LOOP] Iteration {} API call done, hasText={}, hasToolCalls={}",
                    iteration,
                    result.assistant.getText() != null && !result.assistant.getText().isBlank(),
                    result.assistant.hasToolCalls());

            // 检查取消标志（API调用后）
            if (cancelled) {
                log.info("Agent loop cancelled by user after API call at iteration {}", iteration);
                break;
            }

            // 记录 Token 使用量
            if (result.promptTokens > 0 || result.completionTokens > 0) {
                tokenTracker.recordUsage(result.promptTokens, result.completionTokens);
            }

            // 将助手消息加入历史
            messageHistory.add(result.assistant);

            String text = result.assistant.getText();
            if (text != null && !text.isBlank()) {
                lastAssistantText = text;
                // 阻塞模式通知 UI（流式模式已在回调中实时输出）
                if (!streaming && onAssistantMessage != null) {
                    onAssistantMessage.accept(text);
                }
            }

            // 无工具调用 → 结束
            if (!result.assistant.hasToolCalls()) {
                log.info("[LOOP] No tool calls, loop ended (total {} iterations)", iteration);
                break;
            }

            // 执行工具调用（委托给 AgentToolExecutor，请求级回调已在其中）
            log.info("[LOOP] Executing {} tool calls at iteration {}",
                    result.assistant.getToolCalls().size(), iteration);
            var toolResponseMsg = toolExecutor.executeToolCalls(
                    result.assistant.getToolCalls(), springCallbacks, cancelled);
            lastToolCallCount.addAndGet(result.assistant.getToolCalls().size());
            messageHistory.add(toolResponseMsg);
            log.info("[LOOP] Tool calls executed, toolResponse count={}, advancing to next iteration",
                    toolResponseMsg.getResponses().size());

            // 自动压缩检查（在工具调用后，下次 API 调用前）
            if (autoCompactManager != null) {
                autoCompactManager.autoCompactIfNeeded(
                        () -> messageHistory,
                        this::replaceHistory
                );
            }

        }

        if (iteration >= MAX_ITERATIONS) {
            log.warn("Agent loop reached max iterations {}, force stopping", MAX_ITERATIONS);
            lastAssistantText += "\n\n" + PromptI18n.t(PromptI18n.KEY_LOOP_MAX_ITERATIONS, DEFAULT_LOOP_MAX_ITERATIONS);
        }

        return lastAssistantText;
    }

    /** 阻塞模式：调用 chatModel.call() 并解析结果 */
    private IterationResult blockingIteration(Prompt prompt, RequestCallbacks requestCallbacks) {
        ChatResponse response = chatModel.call(prompt);

        long promptTokens = 0, completionTokens = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            promptTokens = usage.getPromptTokens();
            completionTokens = usage.getCompletionTokens();
        }

        // 尝试提取 thinking 内容（Anthropic extended thinking）
        Consumer<String> thinkingCb = requestCallbacks != null ? requestCallbacks.onThinkingContent() : null;
        extractThinkingContent(response, thinkingCb);

        return new IterationResult(response.getResult().getOutput(), promptTokens, completionTokens);
    }

    /** 流式模式：调用 chatModel.stream() 逐 token 输出，累积完整响应 */
    private IterationResult streamIteration(Prompt prompt, Consumer<String> onToken) {
        StringBuilder textBuffer = new StringBuilder();
        // 工具调用按 ID 去重累积（流式分片可能多次发送同一工具调用）
        Map<String, AssistantMessage.ToolCall> toolCallMap = new LinkedHashMap<>();
        long[] tokenUsage = {0, 0};
        boolean[] firstToken = {true};
        long streamStartTime = System.currentTimeMillis();

        try {
            log.info("[STREAM] Starting stream iteration, messageHistory size={}", messageHistory.size());
            Flux<ChatResponse> flux = chatModel.stream(prompt);

            flux.doOnNext(chunk -> {
                // 记录 token 使用量（通常出现在最后一个 chunk）
                if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
                    var usage = chunk.getMetadata().getUsage();
                    if (usage.getPromptTokens() > 0) tokenUsage[0] = usage.getPromptTokens();
                    if (usage.getCompletionTokens() > 0) tokenUsage[1] = usage.getCompletionTokens();
                }

                if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                AssistantMessage output = chunk.getResult().getOutput();

                // 实时输出文本 token
                String text = output.getText();
                if (text != null && !text.isEmpty() && !cancelled) {
                    // 第一个 token 到达时通知 UI（停止 spinner）
                    if (firstToken[0]) {
                        firstToken[0] = false;
                        long latency = System.currentTimeMillis() - streamStartTime;
                        log.info("[STREAM] First token arrived after {}ms", latency);
                        if (onStreamStart != null) onStreamStart.run();
                    }
                    textBuffer.append(text);
                    if (onToken != null) onToken.accept(text);
                }

                // 累积工具调用（按 ID 去重）
                if (output.hasToolCalls()) {
                    log.info("[STREAM] Tool calls detected in chunk: count={}", output.getToolCalls().size());
                    for (var tc : output.getToolCalls()) {
                        log.info("[STREAM] Tool call: id={}, name={}, args={}",
                                tc.id(), tc.name(), tc.arguments());
                        if (tc.id() != null) {
                            toolCallMap.putIfAbsent(tc.id(), tc);
                        }
                    }
                }
            }).blockLast();

            long elapsed = System.currentTimeMillis() - streamStartTime;
            log.info("[STREAM] Stream completed in {}ms, textBuffer length={}, toolCalls={}",
                    elapsed, textBuffer.length(), toolCallMap.size());

        } catch (Exception e) {
            // 流式调用失败 → 降级到阻塞模式
            log.warn("[STREAM] Streaming call failed, falling back to blocking mode: {}", e.getMessage(), e);
            return blockingIteration(prompt, null);
        }

        // 使用 Builder 构建 AssistantMessage（构造器是 protected 的）
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>(toolCallMap.values());
        AssistantMessage assistant = AssistantMessage.builder()
                .content(textBuffer.toString())
                .toolCalls(toolCalls)
                .build();

        return new IterationResult(assistant, tokenUsage[0], tokenUsage[1]);
    }

    /** 获取消息历史（用于上下文压缩等场景） */
    public List<Message> getMessageHistory() {
        return Collections.unmodifiableList(messageHistory);
    }

    /** 返回消息历史的线程安全快照（不可变副本），用于外部只读查询。 */
    public List<Message> copyMessageHistory() {
        synchronized (messageHistory) {
            return List.copyOf(messageHistory);
        }
    }

    /** 获取 Token 追踪器 */
    public TokenTracker getTokenTracker() {
        return tokenTracker;
    }

    /** 获取系统提示词 */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** 获取 ChatModel（用于上下文压缩等需要直接调用模型的场景） */
    public ChatModel getChatModel() {
        return chatModel;
    }

    /** 获取工具上下文（用于注册回调） */
    public ToolContext getToolContext() {
        return toolContext;
    }

    /** 获取 Hook 管理器 */
    public HookManager getHookManager() {
        return hookManager;
    }

    /** 获取当前工具注册中心 */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /** 获取最近一次循环执行的工具调用次数 */
    public int getLastToolCallCount() {
        return lastToolCallCount.get();
    }

    /** 重置历史（保留系统提示词） */
    public void reset() {
        messageHistory.clear();
        messageHistory.add(new SystemMessage(systemPrompt));
    }

    /**
     * 运行时更新系统提示词 —— 重建 messageHistory[0]。
     * <p>
     * 用于会话级别的提示词 API 管理，更新后已有的对话历史保留，
     * 仅替换基础系统消息。
     */
    public void updateSystemPrompt(String newSystemPrompt) {
        if (messageHistory.isEmpty()) {
            messageHistory.add(new SystemMessage(newSystemPrompt));
        } else {
            messageHistory.set(0, new SystemMessage(newSystemPrompt));
        }
    }

    /** 替换消息历史（用于上下文压缩后替换） */
    public void replaceHistory(List<Message> newHistory) {
        messageHistory.clear();
        messageHistory.addAll(newHistory);
    }

    /** 单次迭代结果 */
    private record IterationResult(AssistantMessage assistant, long promptTokens, long completionTokens) {}

    /**
     * 从 ChatResponse 中尝试提取 thinking 内容。
     * <p>
     * Anthropic 的 extended thinking 功能会在响应中包含思考过程。
     * Spring AI 可能将其放在 metadata 中或作为独立的消息属性。
     */
    private void extractThinkingContent(ChatResponse response, Consumer<String> thinkingCb) {
        if (thinkingCb == null) return;

        try {
            // 方式1: 检查 response metadata 中的 thinking 字段
            if (response.getMetadata() != null) {
                var metadata = response.getMetadata();
                if (metadata instanceof Map<?, ?> metaMap) {
                    Object thinking = metaMap.get("thinking");
                    if (thinking instanceof String thinkText && !thinkText.isBlank()) {
                        thinkingCb.accept(thinkText);
                        return;
                    }
                }
            }

            // 方式2: 检查 AssistantMessage 的 metadata
            if (response.getResult() != null && response.getResult().getOutput() != null) {
                var output = response.getResult().getOutput();
                var msgMeta = output.getMetadata();
                if (msgMeta != null) {
                    Object thinking = msgMeta.get("thinking");
                    if (thinking instanceof String thinkText && !thinkText.isBlank()) {
                        thinkingCb.accept(thinkText);
                    }
                }
            }
        } catch (Exception e) {
            // thinking 提取失败不影响主流程
            log.debug("Thinking content extraction exception (can be ignored): {}", e.getMessage());
        }
    }

    /** 权限确认请求 */
    public static class PermissionRequest {
        private final String toolName;
        private final String arguments;
        private final String activityDescription;
        private PermissionDecision decision;

        public PermissionRequest(String toolName, String arguments, String activityDescription) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.activityDescription = activityDescription;
        }

        public String toolName() { return toolName; }
        public String arguments() { return arguments; }
        public String activityDescription() { return activityDescription; }
        public PermissionDecision decision() { return decision; }
        public void setDecision(PermissionDecision decision) { this.decision = decision; }
    }

    /** 工具事件，用于 UI 展示 */
    public record ToolEvent(String toolName, Phase phase, String arguments, String result) {
        public enum Phase { START, PROGRESS, END }
    }
}
