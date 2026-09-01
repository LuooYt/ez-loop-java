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
 */
public interface HmsSessionManager {

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
}
