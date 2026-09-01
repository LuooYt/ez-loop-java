package com.inspirationi.loop.core;

import com.inspirationi.loop.core.compact.AutoCompactManager;
import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.permission.DenialTracker;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.permission.PermissionTypes.PermissionDecision;
import com.inspirationi.loop.tool.Tool;
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

    /** 最近一轮循环是否因取消而提前结束（见 {@link #wasLastRunInterrupted()}）。 */
    private volatile boolean lastRunInterrupted = false;

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

    /**
     * 最近一次 {@code run}/{@code runStreaming} 是否因取消而提前结束。
     * <p>
     * 与 {@link #cancel()} 翻转的 {@code cancelled} 分开记录：后者在每轮开始时被
     * 重置，调用方拿不到「刚结束的那一轮是否被中断」。没有这个标志，前端只能去
     * 匹配回复末尾的「[用户已中断]」文本 —— 而那段文本会被 i18n 按系统语言翻译，
     * 匹配随时失效。
     */
    public boolean wasLastRunInterrupted() {
        return lastRunInterrupted;
    }

    /** 重置取消标志（每次新的循环开始时调用） */
    private void resetCancel() {
        cancelled = false;
        lastRunInterrupted = false;
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
        // 请求级回调优先；未提供时回退到持久回调。解析一次后本轮统一使用，
        // 避免下游各处重复判空又各自回退。
        RequestCallbacks callbacks = requestCallbacks != null ? requestCallbacks
                : new RequestCallbacks(onToolEvent, onThinkingContent, onPermissionRequest, onToken);
        toolExecutor.setRequestCallbacks(callbacks);

        List<ToolCallback> springCallbacks = toolRegistry.toCallbacks(toolContext);
        // 工具只作为「定义」传给模型，执行由本类的循环接管（权限确认、Hook、取消
        // 都挂在那里）。Spring AI 1.x/2.0 里程碑需要显式
        // internalToolExecutionEnabled(false) 来关掉 ChatModel 的自动执行；
        // 2.0 GA 起该选项已移除，ChatModel.call()/stream() 本就只做单次往返、
        // 原样返回带 toolCalls 的响应，因此无需再声明。
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(springCallbacks)
                .build();

        int iteration = 0;
        String lastAssistantText = "";

        while (iteration < MAX_ITERATIONS) {
            // 检查取消标志
            if (cancelled) {
                log.info("Agent loop cancelled by user at iteration {}", iteration);
                lastRunInterrupted = true;
                lastAssistantText += "\n\n" + PromptI18n.t(PromptI18n.KEY_LOOP_INTERRUPTED, DEFAULT_LOOP_INTERRUPTED);
                break;
            }

            iteration++;
            log.info("[LOOP] === Iteration {} start ({}) ===", iteration, streaming ? "streaming" : "blocking");

            Prompt prompt = new Prompt(List.copyOf(messageHistory), options);

            // 调用 AI 并获取结果
            IterationResult result;
            if (streaming) {
                result = streamIteration(prompt, onToken, callbacks.onThinkingContent());
            } else {
                result = blockingIteration(prompt, callbacks.onThinkingContent());
            }

            log.info("[LOOP] Iteration {} API call done, hasText={}, hasToolCalls={}",
                    iteration,
                    result.assistant.getText() != null && !result.assistant.getText().isBlank(),
                    result.assistant.hasToolCalls());

            // 检查取消标志（API调用后）
            if (cancelled) {
                log.info("Agent loop cancelled by user after API call at iteration {}", iteration);
                lastRunInterrupted = true;
                break;
            }

            // 记录 Token 使用量（含缓存读写，缺一项就会让成本估算失真）
            if (result.hasUsage()) {
                tokenTracker.recordUsage(result.promptTokens, result.completionTokens,
                        result.cacheReadTokens, result.cacheWriteTokens);
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
            // 传 supplier 而非 cancelled 的当前值：工具批次可能长时间运行，
            // 期间到达的 cancel() 应当在下一个工具前就被感知到。
            var toolResponseMsg = toolExecutor.executeToolCalls(
                    result.assistant.getToolCalls(), springCallbacks, () -> cancelled);
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
    private IterationResult blockingIteration(Prompt prompt, Consumer<String> onThinking) {
        ChatResponse response = chatModel.call(prompt);
        TokenUsage usage = extractUsage(response);

        // 尝试提取 thinking 内容（Anthropic extended thinking）
        extractThinkingContent(response, onThinking);

        return new IterationResult(response.getResult().getOutput(),
                usage.promptTokens(), usage.completionTokens(),
                usage.cacheReadTokens(), usage.cacheWriteTokens());
    }

    /** 一次 API 调用报告的四项用量。 */
    private record TokenUsage(long promptTokens, long completionTokens,
                              long cacheReadTokens, long cacheWriteTokens) {
        static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);
    }

    /**
     * 从响应中提取用量，缺失字段按 0 处理。
     * <p>
     * 缓存字段（{@code getCacheReadInputTokens} / {@code getCacheWriteInputTokens}）
     * 在 Spring AI 的 {@code Usage} 接口上是 default 方法，未实现的 provider 返回
     * {@code null} —— 因此每一项都要判空，不能直接拆箱。
     */
    private static TokenUsage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return TokenUsage.NONE;
        }
        var usage = response.getMetadata().getUsage();
        return new TokenUsage(
                orZero(usage.getPromptTokens()),
                orZero(usage.getCompletionTokens()),
                orZero(usage.getCacheReadInputTokens()),
                orZero(usage.getCacheWriteInputTokens()));
    }

    private static long orZero(Number value) {
        return value != null ? value.longValue() : 0L;
    }

    /** 流式模式：调用 chatModel.stream() 逐 token 输出，累积完整响应 */
    private IterationResult streamIteration(Prompt prompt, Consumer<String> onToken,
                                            Consumer<String> onThinking) {
        StringBuilder textBuffer = new StringBuilder();
        // 工具调用按 ID 去重累积（流式分片可能多次发送同一工具调用）
        Map<String, AssistantMessage.ToolCall> toolCallMap = new LinkedHashMap<>();
        // [promptTokens, completionTokens, cacheRead, cacheWrite]
        long[] tokenUsage = {0, 0, 0, 0};
        boolean[] firstToken = {true};
        long streamStartTime = System.currentTimeMillis();

        try {
            log.info("[STREAM] Starting stream iteration, messageHistory size={}", messageHistory.size());
            Flux<ChatResponse> flux = chatModel.stream(prompt);

            flux.doOnNext(chunk -> {
                // 记录 token 使用量（通常出现在最后一个 chunk）。
                // 逐项取最大值而非直接覆盖：中间 chunk 可能只带部分字段，
                // 用 0 覆盖已收到的值会丢掉用量。
                TokenUsage chunkUsage = extractUsage(chunk);
                tokenUsage[0] = Math.max(tokenUsage[0], chunkUsage.promptTokens());
                tokenUsage[1] = Math.max(tokenUsage[1], chunkUsage.completionTokens());
                tokenUsage[2] = Math.max(tokenUsage[2], chunkUsage.cacheReadTokens());
                tokenUsage[3] = Math.max(tokenUsage[3], chunkUsage.cacheWriteTokens());

                if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                AssistantMessage output = chunk.getResult().getOutput();

                // Extended thinking 分片 —— Anthropic 把它作为独立 chunk 发出，
                // 用 metadata 的 thinking 标记区分，正文与思考过程不能混流。
                if (isThinkingChunk(output)) {
                    if (onThinking != null && !cancelled) {
                        String thinkingText = output.getText();
                        if (thinkingText != null && !thinkingText.isEmpty()) {
                            onThinking.accept(thinkingText);
                        }
                    }
                    return;
                }

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

                // 按 ID 收集工具调用。两个 provider 都在模型层就把分片合成了完整的
                // tool call（Anthropic 用 StreamingState.appendToolJson 累积
                // input_json_delta，OpenAI 用 ChunkMerger），因此同一 ID 通常只出现
                // 一次。这里用 put 覆盖而非 putIfAbsent：万一某个 provider 仍分片下发，
                // 首片的 arguments 往往是空串，保留第一个等于永久丢掉真实参数。
                if (output.hasToolCalls()) {
                    log.info("[STREAM] Tool calls detected in chunk: count={}", output.getToolCalls().size());
                    for (var tc : output.getToolCalls()) {
                        log.info("[STREAM] Tool call: id={}, name={}, args={}",
                                tc.id(), tc.name(), tc.arguments());
                        if (tc.id() != null) {
                            toolCallMap.put(tc.id(), tc);
                        }
                    }
                }
            }).blockLast();

            long elapsed = System.currentTimeMillis() - streamStartTime;
            log.info("[STREAM] Stream completed in {}ms, textBuffer length={}, toolCalls={}",
                    elapsed, textBuffer.length(), toolCallMap.size());

        } catch (Exception e) {
            // 流式调用失败 → 降级到阻塞模式（thinking 回调需一并传下去，
            // 否则降级后思考内容静默丢失）
            log.warn("[STREAM] Streaming call failed, falling back to blocking mode: {}", e.getMessage(), e);
            return blockingIteration(prompt, onThinking);
        }

        // 使用 Builder 构建 AssistantMessage（构造器是 protected 的）
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>(toolCallMap.values());
        AssistantMessage assistant = AssistantMessage.builder()
                .content(textBuffer.toString())
                .toolCalls(toolCalls)
                .build();

        return new IterationResult(assistant, tokenUsage[0], tokenUsage[1],
                tokenUsage[2], tokenUsage[3]);
    }

    /**
     * 返回消息历史的线程安全快照（不可变副本），用于外部只读查询。
     * <p>
     * 只提供快照、不提供视图：{@code unmodifiableList} 包装的仍是活列表，
     * 调用方遍历期间 Agent 循环追加消息就会抛
     * {@link java.util.ConcurrentModificationException}。
     */
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

    /**
     * 单次迭代结果。
     * <p>
     * 缓存 token 单独承载而非并入 promptTokens：缓存读取的单价约为普通输入的
     * 1/10，混在一起会让 {@link TokenTracker#estimateCost()} 把命中缓存的部分
     * 按全价计费，长会话里可高估数倍。
     */
    private record IterationResult(AssistantMessage assistant, long promptTokens, long completionTokens,
                                   long cacheReadTokens, long cacheWriteTokens) {

        /** 无缓存信息的结果（流式降级、provider 未报告缓存用量等场景）。 */
        IterationResult(AssistantMessage assistant, long promptTokens, long completionTokens) {
            this(assistant, promptTokens, completionTokens, 0, 0);
        }

        /** 本轮是否有任何用量需要记账。 */
        boolean hasUsage() {
            return promptTokens > 0 || completionTokens > 0
                    || cacheReadTokens > 0 || cacheWriteTokens > 0;
        }
    }

    /** Anthropic 在思考分片的 metadata 上打的标记键（值为 {@code Boolean.TRUE}）。 */
    private static final String THINKING_MARKER = "thinking";

    /** Anthropic 在最终消息的 metadata 上存放思考内容列表的键。 */
    private static final String THINKING_CONTENTS_KEY = "anthropicThinkingContents";

    /**
     * 判断一个流式分片是否是 extended thinking 分片。
     * <p>
     * Anthropic 把思考过程作为<b>独立的</b> chunk 发出，仅在 metadata 里打
     * {@code thinking=TRUE} 标记。必须据此分流，否则思考内容会被当作普通
     * token 混入正文缓冲与 onToken 回调。
     */
    private static boolean isThinkingChunk(AssistantMessage output) {
        var metadata = output.getMetadata();
        return metadata != null && Boolean.TRUE.equals(metadata.get(THINKING_MARKER));
    }

    /**
     * 从阻塞响应中提取 thinking 内容并回调。
     * <p>
     * Anthropic 把完整的思考内容挂在最终 AssistantMessage 的 metadata 上，键为
     * {@code anthropicThinkingContents}，值是一个列表。此处只读该契约，不再
     * 沿用早先那套「先看 ChatResponseMetadata 是不是 Map、再取 thinking 字段」
     * 的猜测式探测 —— {@code ChatResponseMetadata} 从不实现 {@code Map}，
     * 那段分支恒不成立，导致回调始终静默。
     */
    private void extractThinkingContent(ChatResponse response, Consumer<String> thinkingCb) {
        if (thinkingCb == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return;
        }

        try {
            var metadata = response.getResult().getOutput().getMetadata();
            if (metadata == null) return;

            Object contents = metadata.get(THINKING_CONTENTS_KEY);
            if (contents instanceof List<?> list && !list.isEmpty()) {
                // 元素类型由 provider 决定（AnthropicThinkingContent），此处不引入
                // provider 专有类型，直接取其文本表示交给 UI。
                for (Object item : list) {
                    if (item == null) continue;
                    String text = item.toString();
                    if (!text.isBlank()) {
                        thinkingCb.accept(text);
                    }
                }
            }
        } catch (Exception e) {
            // thinking 提取失败不影响主流程
            log.debug("Thinking content extraction exception (can be ignored): {}", e.getMessage());
        }
    }

    /**
     * 权限确认请求 —— 由 {@link AgentToolExecutor} 在规则引擎判定为 ASK 时构造。
     * <p>
     * <b>携带 {@code riskLevel} 与 {@code parsedArguments} 是安全要求，不是便利性设计。</b>
     * 请求到达回调时，规则引擎已用工具的真实风险等级评估过一次并得出「需要询问」；
     * 回调若因拿不到这些信息而自行重新评估，就只能猜一个风险等级 —— 猜低了会让
     * CRITICAL 工具命中「风险等级自动放行」而跳过用户确认。回调的职责是<b>询问</b>，
     * 不是<b>重新判定</b>；需要判定依据时直接读 {@link #decision()}。
     *
     * @param toolName            工具名
     * @param arguments           原始参数 JSON 文本（用于展示）
     * @param parsedArguments     已解析的参数（不可变，避免回调再解一遍 JSON）
     * @param activityDescription 人类可读的活动描述
     * @param riskLevel           工具声明的真实风险等级
     * @param decision            规则引擎给出的原始判定（含 ASK 原因，如危险命令告警）
     */
    public record PermissionRequest(
            String toolName,
            String arguments,
            Map<String, Object> parsedArguments,
            String activityDescription,
            Tool.RiskLevel riskLevel,
            PermissionDecision decision
    ) {
        /** 防御性包装 parsedArguments：请求会跨线程递给 UI 回调，不能让其看到后续变更。 */
        public PermissionRequest {
            parsedArguments = parsedArguments == null ? Map.of() : Map.copyOf(parsedArguments);
        }

        /** 无风险等级信息的构造（仅用于传统回调模式，此时规则引擎不参与判定）。 */
        public PermissionRequest(String toolName, String arguments, String activityDescription) {
            this(toolName, arguments, Map.of(), activityDescription, null, null);
        }
    }

    /** 工具事件，用于 UI 展示 */
    public record ToolEvent(String toolName, Phase phase, String arguments, String result) {
        public enum Phase { START, PROGRESS, END }
    }
}
