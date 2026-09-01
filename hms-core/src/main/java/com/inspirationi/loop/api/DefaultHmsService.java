package com.inspirationi.loop.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.core.TokenTracker;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.tool.ToolContext;

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

    /**
     * 构造单会话服务（使用默认调用超时）。
     *
     * @param agentLoop    AgentLoop 实例
     * @param tokenTracker Token 统计追踪器
     */
    public DefaultHmsService(AgentLoop agentLoop, TokenTracker tokenTracker) {
        this(agentLoop, tokenTracker, DEFAULT_CALL_TIMEOUT_SECONDS);
    }

    /**
     * 构造单会话服务。
     *
     * @param agentLoop           AgentLoop 实例
     * @param tokenTracker        Token 统计追踪器
     * @param callTimeoutSeconds  单次调用的超时秒数（<=0 时回退到默认值）
     */
    public DefaultHmsService(AgentLoop agentLoop, TokenTracker tokenTracker, long callTimeoutSeconds) {
        this.agentLoop = agentLoop;
        this.tokenTracker = tokenTracker;
        this.toolContext = agentLoop.getToolContext();
        this.callTimeoutSeconds = callTimeoutSeconds > 0 ? callTimeoutSeconds : DEFAULT_CALL_TIMEOUT_SECONDS;

        agentLoop.setOnPermissionRequest(req -> {
            log.info("[API] Permission auto-allowed: {}", req.toolName());
            return PermissionChoice.ALLOW_ONCE;
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
            var future = CompletableFuture.supplyAsync(() -> {
                String result = agentLoop.run(userMessage);
                return new Object[]{result, agentLoop.getLastToolCallCount()};
            });
            var outcome = future.get(callTimeoutSeconds, TimeUnit.SECONDS);
            String result = (String) outcome[0];
            int toolCalls = (int) outcome[1];
            long inputTokens = tokenTracker.getInputTokens();
            long outputTokens = tokenTracker.getOutputTokens();
            return HmsResponse.ok(result, toolCalls, inputTokens, outputTokens);
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
            var future = CompletableFuture.supplyAsync(() -> {
                String result = agentLoop.runStreaming(userMessage, onToken);
                return new Object[]{result, agentLoop.getLastToolCallCount()};
            });
            var outcome = future.get(callTimeoutSeconds, TimeUnit.SECONDS);
            String result = (String) outcome[0];
            int toolCalls = (int) outcome[1];
            long inputTokens = tokenTracker.getInputTokens();
            long outputTokens = tokenTracker.getOutputTokens();
            return HmsResponse.ok(result, toolCalls, inputTokens, outputTokens);
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
            // 注册 AskUser 回调链：同步阻塞 → 异步 → ToolContext 回退
            toolContext.set(
                    com.inspirationi.loop.tool.impl.AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                    (BiFunction<String, java.util.List<String>, String>) (question, options) -> {
                        String answer = callbacks.onAskUser(question, options);
                        if (answer != null && !answer.isBlank()) return answer;
                        try {
                            String asyncAnswer = callbacks.onAskUserAsync(question, options)
                                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
                            if (asyncAnswer != null && !asyncAnswer.isBlank()) return asyncAnswer;
                        } catch (Exception e) { /* fall through */ }
                        return null;
                    });
            toolContext.set(
                    com.inspirationi.loop.tool.impl.AskUserQuestionTool.USER_INPUT_CALLBACK,
                    (java.util.function.Function<String, String>) prompt -> {
                        String answer = callbacks.onAskUser(prompt, null);
                        if (answer != null && !answer.isBlank()) return answer;
                        try {
                            String asyncAnswer = callbacks.onAskUserAsync(prompt, null)
                                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
                            if (asyncAnswer != null && !asyncAnswer.isBlank()) return asyncAnswer;
                        } catch (Exception e) { /* fall through */ }
                        return null;
                    });

            // 构建请求级回调（不污染 AgentLoop 持久状态）
            AgentLoop.RequestCallbacks requestCallbacks = new AgentLoop.RequestCallbacks(
                    event -> callbacks.onToolUse(event.toolName(), event.arguments(), event.result()),
                    callbacks::onThinking,
                    req -> {
                        String choice = callbacks.onPermissionRequest(
                                req.toolName(), req.activityDescription() != null ? req.activityDescription() : "");
                        if ("allow".equalsIgnoreCase(choice)) return PermissionChoice.ALLOW_ONCE;
                        if ("deny".equalsIgnoreCase(choice)) return PermissionChoice.DENY_ONCE;
                        try {
                            String asyncChoice = callbacks.onPermissionRequestAsync(
                                    req.toolName(), req.activityDescription())
                                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
                            if ("allow".equalsIgnoreCase(asyncChoice)) return PermissionChoice.ALLOW_ONCE;
                        } catch (Exception e) { /* fall through */ }
                        return PermissionChoice.DENY_ONCE;
                    },
                    callbacks::onToken
            );

            String result = agentLoop.runStreaming(userMessage, callbacks::onToken, requestCallbacks);
            long inputTokens = tokenTracker.getInputTokens();
            long outputTokens = tokenTracker.getOutputTokens();

            HmsResponse response = HmsResponse.ok(result, agentLoop.getLastToolCallCount(),
                    inputTokens, outputTokens);
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
            processing.set(false);
        }
    }

    /** 取消当前正在执行的 Agent 循环（非阻塞，当前请求会尽快中断）。 */
    @Override
    public void cancel() {
        agentLoop.cancel();
    }

    /** 获取当前会话累计的 Token 使用统计。 */
    @Override
    public TokenStats getTokenStats() {
        return TokenStats.of(tokenTracker.getInputTokens(), tokenTracker.getOutputTokens());
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
