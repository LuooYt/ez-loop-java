package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.core.TokenTracker;
import com.inspirationi.loop.telemetry.MetricsCollector;
import com.inspirationi.loop.tool.ToolRegistry;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个会话的内部封装 —— 持有 AgentLoop 实例及其关联资源。
 * <p>
 * 每个 LoopSession 对应一个独立的对话上下文（消息历史、Token 统计、指标收集器）。
 * 支持会话状态管理：ACTIVE → PAUSED → ACTIVE / DESTROYED。
 */
public class LoopSession {

    /** 会话唯一标识。 */
    private final String sessionId;
    /** 该会话的 AgentLoop 实例（承载消息历史与执行循环）。 */
    private final AgentLoop agentLoop;
    /** 该会话的 Token 消耗追踪器。 */
    private final TokenTracker tokenTracker;
    /** 该会话的指标收集器（记录用户消息/API 调用/工具使用）。 */
    private final MetricsCollector metricsCollector;
    /** 会话创建时间。 */
    private final Instant createdAt;
    /** 最后访问时间（用于计算空闲时长，volatile 保证并发可见性）。 */
    private volatile Instant lastAccessTime;

    /** 会话状态 */
    private volatile SessionStatus status;

    /** 会话级提示词（不含全局前缀） */
    private volatile String sessionPrompt;

    /** 该会话的消息计数 */
    private final AtomicInteger messageCount;

    /**
     * 构造会话封装，初始状态为 ACTIVE。
     *
     * @param sessionId        会话唯一标识
     * @param agentLoop        承载执行循环的 AgentLoop
     * @param tokenTracker     会话级 Token 追踪器
     * @param metricsCollector 会话级指标收集器
     * @param sessionPrompt    会话级提示词
     */
    public LoopSession(String sessionId, AgentLoop agentLoop,
                       TokenTracker tokenTracker, MetricsCollector metricsCollector,
                       String sessionPrompt) {
        this.sessionId = sessionId;
        this.agentLoop = agentLoop;
        this.tokenTracker = tokenTracker;
        this.metricsCollector = metricsCollector;
        this.sessionPrompt = sessionPrompt;
        this.createdAt = Instant.now();
        this.lastAccessTime = Instant.now();
        this.status = SessionStatus.ACTIVE;
        this.messageCount = new AtomicInteger(0);
    }

    /** 标记会话被访问（更新最后活跃时间 + 消息计数）。 */
    public void touch() {
        this.lastAccessTime = Instant.now();
        this.messageCount.incrementAndGet();
    }

    /** 销毁会话，释放资源。 */
    public void destroy() {
        this.status = SessionStatus.DESTROYED;
        agentLoop.cancel();
    }

    /** 暂停会话。 */
    public void pause() {
        if (status == SessionStatus.ACTIVE || status == SessionStatus.IDLE) {
            this.status = SessionStatus.PAUSED;
        }
    }

    /** 恢复会话。 */
    public void resume() {
        if (status == SessionStatus.PAUSED) {
            this.status = SessionStatus.ACTIVE;
            this.lastAccessTime = Instant.now();
        }
    }

    /** 会话空闲时长（秒）。 */
    public long idleSeconds() {
        return Instant.now().getEpochSecond() - lastAccessTime.getEpochSecond();
    }

    /** 该会话的 AgentLoop 使用的 ToolRegistry */
    public ToolRegistry getToolRegistry() {
        return agentLoop.getToolRegistry();
    }

    // ==================== Getters / Setters ====================

    public String getSessionId() { return sessionId; }
    public AgentLoop getAgentLoop() { return agentLoop; }
    public TokenTracker getTokenTracker() { return tokenTracker; }
    public MetricsCollector getMetricsCollector() { return metricsCollector; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastAccessTime() { return lastAccessTime; }
    public SessionStatus getStatus() { return status; }
    public int getMessageCount() { return messageCount.get(); }
    public String getSessionPrompt() { return sessionPrompt; }

    /** 更新会话级提示词。 */
    public void setSessionPrompt(String sessionPrompt) {
        this.sessionPrompt = sessionPrompt;
    }
}
