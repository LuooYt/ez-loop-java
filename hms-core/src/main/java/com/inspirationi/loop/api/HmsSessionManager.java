package com.inspirationi.loop.api;

import com.inspirationi.loop.tool.ToolRegistry;

import java.util.List;
import java.util.function.Consumer;

/**
 * 会话隔离管理器 —— HMS Core SDK 唯一的对外集成入口。
 * <p>
 * 管理 {@code sessionId → AgentLoop} 映射，确保不同会话的消息历史和上下文完全隔离。
 * <p>
 * 集成方典型用法：
 * <pre>{@code
 * @Autowired
 * private HmsSessionManager sessionManager;
 *
 * String sid = sessionManager.createSession();
 * HmsResponse r = sessionManager.send(sid, "你好");
 * sessionManager.destroySession(sid);
 * }</pre>
 * <p>
 * 实现 {@link AutoCloseable}：作为 Spring Bean 时容器会在关闭阶段自动调用
 * {@link #close()}；手动构造时应自行调用，以停止内部的清理调度线程。
 */
public interface HmsSessionManager extends AutoCloseable {

    // ==================== 会话生命周期 ====================

    /** 创建新会话（使用默认会话提示词 + 全局提示词）。 */
    String createSession();

    /** 创建带自定义提示词的会话（覆盖默认会话提示词）。 */
    String createSession(String customSessionPrompt);

    /** 销毁会话并释放所有资源。 */
    void destroySession(String sessionId);

    /** 暂停会话（保留上下文但拒绝新消息）。 */
    void pauseSession(String sessionId);

    /** 恢复暂停的会话。 */
    void resumeSession(String sessionId);

    /** 检查会话是否被暂停。 */
    boolean isPaused(String sessionId);

    /** 检查会话是否存在。 */
    boolean sessionExists(String sessionId);

    // ==================== 消息发送 ====================

    /**
     * 同步调用 —— 向指定会话发送用户消息，等待完整 AI 回复后返回。
     */
    HmsResponse send(String sessionId, String userMessage);

    /**
     * 流式调用 —— 向指定会话发送用户消息，每个文本 token 实时通过回调输出。
     */
    HmsResponse sendStreaming(String sessionId, String userMessage, Consumer<String> onToken);

    /**
     * 带完整回调的调用 —— 支持 token 流、工具事件、thinking、AskUser、权限请求等所有回调。
     */
    HmsResponse send(String sessionId, String userMessage, HmsCallbacks callbacks);

    // ==================== 会话控制 ====================

    /** 取消指定会话正在执行的请求。 */
    void cancel(String sessionId);

    /** 获取指定会话累计的 Token 使用统计。 */
    TokenStats getSessionTokenStats(String sessionId);

    /**
     * 更新指定会话的会话级提示词，并同步刷新该会话 AgentLoop 的系统提示词。
     * <p>
     * 在实现类内部完成对 LoopSession 的写回，调用方无需了解内部结构。
     *
     * @param sessionId     会话 ID
     * @param sessionPrompt 新的会话级提示词（不含全局前缀）
     * @throws IllegalArgumentException 会话不存在
     */
    void updateSessionPrompt(String sessionId, String sessionPrompt);

    /** 获取指定会话的工具注册中心（用于会话级工具增删）。 */
    ToolRegistry getSessionToolRegistry(String sessionId);

    // ==================== 扩展点 ====================

    /**
     * 获取指定会话的 Hook 管理器 —— 注册工具调用的前后拦截器。
     * <p>
     * 两个时机（见 {@link com.inspirationi.loop.core.HookManager.HookType}）：
     * <ul>
     *   <li>{@code PRE_TOOL_USE} —— 返回 {@code ABORT} 阻止执行，或原地改写
     *       {@code getArguments()} 调整入参</li>
     *   <li>{@code POST_TOOL_USE} —— {@code setResult(...)} 改写回传给模型的结果</li>
     * </ul>
     * <pre>{@code
     * sessionManager.getSessionHooks(sessionId).register(
     *         HookType.PRE_TOOL_USE, "block-prod-writes", ctx -> {
     *     Object path = ctx.getArguments().get("file_path");
     *     return String.valueOf(path).startsWith("/prod/")
     *             ? HookResult.ABORT : HookResult.CONTINUE;
     * });
     * }</pre>
     * 钩子是<b>会话级</b>的，随会话销毁一同失效；跨会话的策略需在每个会话上注册。
     *
     * @param sessionId 会话 ID
     * @return 该会话的 Hook 管理器
     * @throws IllegalArgumentException 会话不存在
     */
    com.inspirationi.loop.core.HookManager getSessionHooks(String sessionId);

    /**
     * 获取指定会话的权限拒绝追踪器 —— 观测拒绝次数并在越过阈值时收到通知。
     * <p>
     * 连续拒绝或累计拒绝达阈值后，{@code AgentToolExecutor} 会自动拒绝后续需确认
     * 的工具（熔断）。注册回调可据此发告警、写审计日志，或自行销毁会话：
     * <pre>{@code
     * sessionManager.getSessionDenials(sessionId).addDenialCallback(
     *         (consecutive, total) -> auditLog.warn(
     *                 "session {} hit denial threshold: {}/{}", sessionId, consecutive, total));
     * }</pre>
     * 回调只能<b>观测</b>，不改变放行/拒绝的决定 —— 决定权在
     * {@link com.inspirationi.loop.permission.PermissionRuleEngine} 与权限回调。
     *
     * @param sessionId 会话 ID
     * @return 该会话的拒绝追踪器
     * @throws IllegalArgumentException 会话不存在
     */
    com.inspirationi.loop.permission.DenialTracker getSessionDenials(String sessionId);

    // ==================== 信息查询 ====================

    /** 获取单个会话的完整信息。 */
    SessionInfo getSessionInfo(String sessionId);

    /** 获取指定会话的历史消息（按时间顺序，返回不可变副本）。 */
    List<ChatMessage> getSessionMessages(String sessionId);

    /** 列出所有活跃/暂停的会话。 */
    List<SessionInfo> listSessions();

    /** 获取当前活跃会话数。 */
    int getActiveSessionCount();

    // ==================== 运维 ====================

    /** 清理空闲超过指定秒数的会话，返回被清理的会话数量。 */
    int cleanupIdleSessions(long idleTimeoutSeconds);

    /** 获取指定会话的指标收集器。 */
    com.inspirationi.loop.telemetry.MetricsCollector getSessionMetrics(String sessionId);

    /**
     * 释放管理器持有的资源 —— 停止内部调度线程并销毁所有存活会话。
     * <p>
     * 覆写为不抛检查异常，集成方无需 try-catch。
     */
    @Override
    void close();
}
