package com.inspirationi.loop.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.core.TokenTracker;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.telemetry.TokenPricing;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.impl.AskUserQuestionTool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单会话无状态服务 —— 直接封装 AgentLoop，适用于不需要 sessionId 的简单单会话场景。
 * <p>
 * 注意：对于需要会话隔离的 Web 应用，应使用 {@link HmsSessionManager}。
 * 此实现仅维护单个 AgentLoop 实例，无历史隔离。
 * <p>
 * 所有 API 调用均带超时保护，默认超时可通过环境变量 {@code HMS_CORE_CALL_TIMEOUT_SECONDS} 配置。
 */
public class DefaultHmsService implements HmsService {

    private static final Logger log = LoggerFactory.getLogger(DefaultHmsService.class);

    /** 默认 API 调用超时（秒） */
    private static final long DEFAULT_CALL_TIMEOUT_SECONDS = 300; // 5 分钟

    /** 底层的 AgentLoop 实例，负责执行 Agent 循环。 */
    private final AgentLoop agentLoop;
    /** Token 消耗追踪器，累计输入/输出 Token。 */
    private final TokenTracker tokenTracker;
    /** 工具上下文，用于向 AskUser 等工具注册回调。 */
    private final ToolContext toolContext;
    /** 并发保护标志：同一时刻仅允许一个请求执行。 */
    private final AtomicBoolean processing = new AtomicBoolean(false);
    /** 单次 API 调用的超时秒数（非法值回退到默认 300s）。 */
    private final long callTimeoutSeconds;
    /** 回调解析器 —— 同步优先、异步回退的协议实现，与多会话管理器共用。 */
    private final CallbackResolver callbackResolver;
    /** Token 计费策略（可为 null —— 此时费用一律呈现为「未知」）。 */
    private final TokenPricing tokenPricing;

    /**
     * 构造单会话服务（使用默认调用超时，不计费）。
     *
     * @param agentLoop    AgentLoop 实例
     * @param tokenTracker Token 统计追踪器
     */
    public DefaultHmsService(AgentLoop agentLoop, TokenTracker tokenTracker) {
        this(agentLoop, tokenTracker, DEFAULT_CALL_TIMEOUT_SECONDS);
    }

    /**
     * 构造单会话服务（不计费 —— {@link TokenStats#cost()} 恒为 {@code null}）。
     *
     * @param agentLoop           AgentLoop 实例
     * @param tokenTracker        Token 统计追踪器
     * @param callTimeoutSeconds  单次调用的超时秒数（<=0 时回退到默认值）
     */
    public DefaultHmsService(AgentLoop agentLoop, TokenTracker tokenTracker, long callTimeoutSeconds) {
        this(agentLoop, tokenTracker, callTimeoutSeconds, null);
    }

    /**
     * 构造单会话服务，指定计费策略。
     *
     * @param agentLoop           AgentLoop 实例
     * @param tokenTracker        Token 统计追踪器
     * @param callTimeoutSeconds  单次调用的超时秒数（<=0 时回退到默认值）
     * @param tokenPricing        计费策略；{@code null} 表示不计费，此时
     *                            {@link TokenStats#cost()} 恒为 {@code null}
     *                            （呈现为「定价未知」），token 计数不受影响
     */
    public DefaultHmsService(AgentLoop agentLoop, TokenTracker tokenTracker,
                             long callTimeoutSeconds, TokenPricing tokenPricing) {
        this.agentLoop = agentLoop;
        this.tokenTracker = tokenTracker;
        this.toolContext = agentLoop.getToolContext();
        this.callTimeoutSeconds = callTimeoutSeconds > 0 ? callTimeoutSeconds : DEFAULT_CALL_TIMEOUT_SECONDS;
        this.callbackResolver = new CallbackResolver(this.callTimeoutSeconds, "[API]");
        this.tokenPricing = tokenPricing;

        // Headless 兜底 —— 仅当调用方未提供 HmsCallbacks 时生效；提供了回调时
        // call(userMessage, callbacks) 会用请求级回调覆盖它，真正去问用户。
        // 无人可问时只放行本就无需确认的低风险操作，其余一律拒绝：拒绝会作为工具
        // 结果回传给模型，它可以换一种方式继续，而不是静默越权执行。
        agentLoop.setOnPermissionRequest(req -> {
            Tool.RiskLevel risk = req.riskLevel();
            if (risk != null && risk.ordinal() <= Tool.RiskLevel.LOW.ordinal()) {
                log.debug("[API] Permission auto-allowed (headless, risk={}): {}", risk, req.toolName());
                return PermissionChoice.ALLOW_ONCE;
            }
            log.info("[API] Permission denied (headless, no callback to ask; risk={}): {}",
                    risk, req.toolName());
            return PermissionChoice.DENY_ONCE;
        });
    }

    /**
     * 同步调用 —— 发送用户消息并等待完整回复。
     * <p>
     * 使用 CAS 标志保证并发互斥；超时后自动取消底层 AgentLoop。
     *
     * @param userMessage 用户输入文本
     * @return 完整响应（成功或超时错误）
     * @throws HmsException 服务忙或执行失败时抛出
     */
    @Override
    public HmsResponse send(String userMessage) {
        if (!processing.compareAndSet(false, true)) {
            throw new HmsException(HmsErrorCode.SERVICE_BUSY,
                    "Service is busy. Check isBusy() or cancel() first.");
        }
        try {
            // 基线取自调用前 —— 之后的差值才是本轮用量，见 buildResponse
            long inputBefore = tokenTracker.getInputTokens();
            long outputBefore = tokenTracker.getOutputTokens();
            var future = CompletableFuture.supplyAsync(() -> {
                String result = agentLoop.run(userMessage);
                return new Object[]{result, agentLoop.getLastToolCallCount()};
            });
            var outcome = future.get(callTimeoutSeconds, TimeUnit.SECONDS);
            String result = (String) outcome[0];
            int toolCalls = (int) outcome[1];
            return buildResponse(result, toolCalls, inputBefore, outputBefore);
        } catch (TimeoutException e) {
            agentLoop.cancel();
            log.error("[API] Sync call timed out after {}s", callTimeoutSeconds);
            return HmsResponse.error(HmsErrorCode.REQUEST_TIMEOUT,
                    "Request timed out after " + callTimeoutSeconds + "s");
        } catch (HmsException e) {
            throw e;
        } catch (Exception e) {
            log.error("[API] Sync call failed", e);
            if (e.getCause() instanceof HmsException he) throw he;
            throw new HmsException(HmsErrorCode.EXECUTION_FAILED,
                    "Execution failed: " + e.getMessage(), e);
        } finally {
            processing.set(false);
        }
    }

    /**
     * 流式调用 —— 每个文本 token 实时回调，结束后返回聚合响应。
     *
     * @param userMessage 用户输入文本
     * @param onToken     每个 token 的实时回调
     * @return 完整响应（成功或超时错误）
     * @throws HmsException 服务忙或执行失败时抛出
     */
    @Override
    public HmsResponse sendStreaming(String userMessage, Consumer<String> onToken) {
        if (!processing.compareAndSet(false, true)) {
            throw new HmsException(HmsErrorCode.SERVICE_BUSY, "Service is busy.");
        }
        try {
            long inputBefore = tokenTracker.getInputTokens();
            long outputBefore = tokenTracker.getOutputTokens();
            var future = CompletableFuture.supplyAsync(() -> {
                String result = agentLoop.runStreaming(userMessage, onToken);
                return new Object[]{result, agentLoop.getLastToolCallCount()};
            });
            var outcome = future.get(callTimeoutSeconds, TimeUnit.SECONDS);
            String result = (String) outcome[0];
            int toolCalls = (int) outcome[1];
            return buildResponse(result, toolCalls, inputBefore, outputBefore);
        } catch (TimeoutException e) {
            agentLoop.cancel();
            log.error("[API] Streaming call timed out after {}s", callTimeoutSeconds);
            return HmsResponse.error(HmsErrorCode.REQUEST_TIMEOUT,
                    "Streaming request timed out after " + callTimeoutSeconds + "s");
        } catch (HmsException e) {
            throw e;
        } catch (Exception e) {
            log.error("[API] Streaming call failed", e);
            throw new HmsException(HmsErrorCode.EXECUTION_FAILED,
                    "Streaming failed: " + e.getMessage(), e);
        } finally {
            processing.set(false);
        }
    }

    /**
     * 带完整回调的调用 —— 支持 token 流、工具事件、thinking、AskUser、权限请求等回调。
     * <p>
     * 通过 ToolContext 注册 AskUser 回调链（同步 → 异步 → 回退），
     * 并使用请求级回调避免污染 AgentLoop 的持久状态。
     *
     * @param userMessage 用户输入文本
     * @param callbacks   回调集合
     * @return 完整响应
     * @throws HmsException 执行失败时抛出
     */
    @Override
    public HmsResponse send(String userMessage, HmsCallbacks callbacks) {
        if (!processing.compareAndSet(false, true)) {
            throw new HmsException(HmsErrorCode.SERVICE_BUSY, "Service is busy.");
        }
        try {
            // 注册 AskUser 回调链：同步阻塞 → 异步 → ToolContext 回退。
            // 请求结束后由 finally 清除，见 registerAskUserCallbacks 的说明。
            registerAskUserCallbacks(callbacks);

            // 构建请求级回调（不污染 AgentLoop 持久状态）
            AgentLoop.RequestCallbacks requestCallbacks = new AgentLoop.RequestCallbacks(
                    event -> {
                        callbacks.onToolUse(event.toolName(), event.phase().name(),
                                event.arguments(), event.result());
                        // 活动状态跟随工具阶段（与多会话侧保持一致）
                        if (event.phase() == AgentLoop.ToolEvent.Phase.START) {
                            callbacks.onActivity(SessionActivity.USING_TOOL, event.toolName());
                        }
                    },
                    callbacks::onThinking,
                    req -> callbackResolver.resolvePermission(callbacks, req),
                    callbacks::onToken,
                    callbacks::onCompaction,
                    callbacks::onActivity
            );

            long inputBefore = tokenTracker.getInputTokens();
            long outputBefore = tokenTracker.getOutputTokens();

            String result = agentLoop.runStreaming(userMessage, callbacks::onToken, requestCallbacks);

            HmsResponse response = buildResponse(result, agentLoop.getLastToolCallCount(),
                    inputBefore, outputBefore);
            callbacks.onComplete(response);
            return response;
        } catch (Exception e) {
            log.error("[API] Call with callbacks failed", e);
            String action = callbacks.onError(e);
            if ("retry".equalsIgnoreCase(action)) {
                log.info("[API] User requested retry after error");
            }
            throw new HmsException(HmsErrorCode.EXECUTION_FAILED,
                    "Execution failed: " + e.getMessage(), e);
        } finally {
            // 顺序要紧：先摘掉回调再清 processing，否则在两步之间到达的调用
            // 仍可能看到上一个请求的回调。
            clearAskUserCallbacks();
            processing.set(false);
        }
    }

    /**
     * 把本次请求的 AskUser 回调链注册到工具上下文。
     * <p>
     * <b>必须与 {@link #clearAskUserCallbacks()} 成对使用</b>：上下文由 AgentLoop
     * 持有、跨请求存活，而这里的闭包捕获了本次请求的 {@link HmsCallbacks}。残留下来
     * 会让后续不带回调的 {@code send} 把提问打给上一个请求的回调 —— 那个接收端可能
     * 早已失效，提问既送不出也收不回，只能空等到超时才回退。
     */
    private void registerAskUserCallbacks(HmsCallbacks callbacks) {
        toolContext.set(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                (BiFunction<String, List<String>, String>) (question, options) ->
                        callbackResolver.resolveAskUser(callbacks, question, options));
        toolContext.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                (Function<String, String>) prompt ->
                        callbackResolver.resolveAskUser(callbacks, prompt, null));
    }

    /** 摘除请求级 AskUser 回调 —— 只删本地键，不影响父级注册的全局共享对象。 */
    private void clearAskUserCallbacks() {
        toolContext.remove(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK);
        toolContext.remove(AskUserQuestionTool.USER_INPUT_CALLBACK);
    }

    /**
     * 汇总一轮响应，用量按<b>本轮增量</b>（而非会话累计）计算。
     * <p>
     * {@link TokenTracker} 是会话级累计器，直接把它的总量当作单轮用量，会让
     * {@link HmsResponse#promptTokens()} 与其「本轮消耗」的文档语义不符 ——
     * 第 3 轮会把前两轮的用量一并报进来。
     * <p>
     * 因取消而结束的轮次标记 {@link HmsResponse#interrupted()}，并保留已产生的用量：
     * 中断前消耗的 token 一样要计费。没有这个标志，调用方只能去匹配回复末尾的
     * 「[用户已中断]」文本，而那段文本会被 i18n 按系统语言翻译，匹配随时失效。
     */
    private HmsResponse buildResponse(String result, int toolCalls,
                                      long inputBefore, long outputBefore) {
        long inputDelta = tokenTracker.getInputTokens() - inputBefore;
        long outputDelta = tokenTracker.getOutputTokens() - outputBefore;
        return agentLoop.wasLastRunInterrupted()
                ? HmsResponse.interrupted(result, toolCalls, inputDelta, outputDelta)
                : HmsResponse.ok(result, toolCalls, inputDelta, outputDelta);
    }


    /** 取消当前正在执行的 Agent 循环（非阻塞，当前请求会尽快中断）。 */
    @Override
    public void cancel() {
        agentLoop.cancel();
    }

    /**
     * 获取当前会话累计的 Token 使用统计（含费用，定价未知时 {@code cost} 为 null）。
     * <p>
     * 模型名现取自 {@code ChatModel} 的生效选项 —— 运行时换了模型，费用就按新模型算。
     */
    @Override
    public TokenStats getTokenStats() {
        return TokenStats.of(tokenTracker.usageSnapshot(), tokenPricing, resolveModelName());
    }

    /**
     * 从 ChatModel 的生效选项中读取模型名，供计费查价。
     * <p>
     * 与 {@code DefaultHmsSessionManager.resolveModelName} 同理：{@code getOptions()}
     * 的接口默认实现返回空 ChatOptions（{@code getModel()} 为 null），故 null 判断
     * 必需 —— 自定义 ChatModel 未覆写它时会走到那里。
     *
     * @return 模型名；无法解析时为 {@code null}，此时费用呈现为「定价未知」
     */
    private String resolveModelName() {
        try {
            var options = agentLoop.getChatModel().getOptions();
            return options != null ? options.getModel() : null;
        } catch (RuntimeException e) {
            log.debug("[API] Cannot resolve model name from ChatModel: {}", e.getMessage());
            return null;
        }
    }

    /** 重置会话（清除消息历史，通常在开始新话题时调用）。 */
    @Override
    public void reset() {
        agentLoop.reset();
    }

    /** 获取服务是否正在处理请求。 */
    @Override
    public boolean isBusy() {
        return processing.get();
    }
}
