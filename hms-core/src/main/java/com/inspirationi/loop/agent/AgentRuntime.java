package com.inspirationi.loop.agent;

import com.inspirationi.loop.api.DefaultHmsSessionManager;
import com.inspirationi.loop.api.HmsResponse;
import com.inspirationi.loop.api.SessionActivity;
import com.inspirationi.loop.api.SessionInfo;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个智能体的运行时实例 —— 「定义」与「活着的会话」之间的那一层。
 * <p>
 * 持有该 Agent 专属的装配物：私有 {@link ToolRegistry}、私有 {@link PermissionSettings}
 * 与规则引擎、记忆、工作队列、Agent 级 {@link ToolContext}，以及一个
 * <b>专属的</b> {@link DefaultHmsSessionManager}（该 Agent 的所有会话都由它托管）。
 * <p>
 * <b>为什么是「每 Agent 一个 manager」而非「一个 manager 内按 agentId 分桶」</b>：
 * manager 里那些实例级并发不变量 —— per-session 互斥、{@code beginRequest/endRequest}
 * 配对、AskUser 回调注册与摘除的顺序、{@code compactNow} 的双重检查 —— 在分桶方案下
 * 每一条都要重新论证「对每个桶是否仍成立」，而收益为零。独占实例下这些代码一行不改。
 * <p>
 * <b>本类不负责调度</b>：周期心跳由 {@link AgentHeartbeatScheduler} 驱动，本类只提供
 * 幂等的 {@link #triggerHeartbeatOnce()} 让它调用。
 */
public final class AgentRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    /** 主动 Agent 后台会话的会话级提示词。 */
    public static final String DEFAULT_BACKEND_SESSION_PROMPT =
            "主动智能体后台会话 —— 由周期心跳驱动；产物留在本会话历史中，并沉淀进 Agent 记忆。";

    /** 降频指数的上限 —— 防止 {@code idleBackoffFactor^n} 随空闲次数指数爆炸。 */
    private static final int MAX_BACKOFF_EXPONENT = 10;
    /** 单次跳过的 tick 数上限。 */
    private static final int MAX_BACKOFF_SKIP = 64;

    private final AgentDefinition definition;
    private final ToolRegistry toolRegistry;
    private final PermissionSettings permissionSettings;
    private final PermissionRuleEngine permissionEngine;
    /** Agent 级记忆；未启用记忆时为 {@code null}。 */
    private final AgentMemory memory;
    private final AgentWorkQueue workQueue;
    private final ToolContext agentToolContext;
    private final DefaultHmsSessionManager sessionManager;
    private final Instant createdAt;

    /** 已下线标记 —— 下线后的运行时应被丢弃，不再接受任何驱动。 */
    private volatile boolean closed;
    /** 心跳是否被暂停（自动暂停或显式暂停）。 */
    private volatile boolean heartbeatPaused;
    /** 后台会话 id（懒创建，创建后长期复用）。 */
    private volatile String backendSessionId;

    /** 心跳重入闸 —— 一次心跳尚未结束时到达的下一次直接放弃。 */
    private final AtomicBoolean ticking = new AtomicBoolean();
    /** 自上次 {@code resumeHeartbeat} 以来累计的心跳轮数。 */
    private final AtomicInteger consecutiveRuns = new AtomicInteger();
    /** 连续无产出轮数 —— 降频依据。 */
    private final AtomicInteger idleRuns = new AtomicInteger();
    /** 降频取模计数器。 */
    private final AtomicInteger backoffTicks = new AtomicInteger();
    /** 累计心跳轮数（含被跳过的 tick 之外的所有实际执行）。 */
    private final AtomicInteger totalRuns = new AtomicInteger();

    /**
     * @param definition         该 Agent 的定义
     * @param toolRegistry       本 Agent 专属的工具注册中心（已按 {@link ToolSelect} 筛过）
     * @param permissionSettings 本 Agent 专属权限设置（默认 Agent 复用全局 Bean）
     * @param permissionEngine   基于上述设置的规则引擎
     * @param memory             记忆实现；{@code null} 表示该 Agent 未启用记忆
     * @param workQueue          工作队列
     * @param agentToolContext   Agent 级工具上下文（已覆盖 PERMISSION_SETTINGS 等键）
     * @param sessionManager     托管本 Agent 全部会话的会话管理器
     */
    public AgentRuntime(AgentDefinition definition,
                        ToolRegistry toolRegistry,
                        PermissionSettings permissionSettings,
                        PermissionRuleEngine permissionEngine,
                        AgentMemory memory,
                        AgentWorkQueue workQueue,
                        ToolContext agentToolContext,
                        DefaultHmsSessionManager sessionManager) {
        this.definition = definition;
        this.toolRegistry = toolRegistry;
        this.permissionSettings = permissionSettings;
        this.permissionEngine = permissionEngine;
        this.memory = memory;
        this.workQueue = workQueue;
        this.agentToolContext = agentToolContext;
        this.sessionManager = sessionManager;
        this.createdAt = Instant.now();
    }

    // ==================== 访问器 ====================

    public AgentDefinition definition() {
        return definition;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public PermissionSettings permissionSettings() {
        return permissionSettings;
    }

    public PermissionRuleEngine permissionEngine() {
        return permissionEngine;
    }

    /** Agent 记忆；未启用时为 {@code null}。 */
    public AgentMemory memory() {
        return memory;
    }

    public AgentWorkQueue workQueue() {
        return workQueue;
    }

    public ToolContext agentToolContext() {
        return agentToolContext;
    }

    public DefaultHmsSessionManager sessionManager() {
        return sessionManager;
    }

    /** 后台会话 id；尚未创建时为 {@code null}。 */
    public String backendSessionId() {
        return backendSessionId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isHeartbeatPaused() {
        return heartbeatPaused;
    }

    /** 累计心跳执行轮数。 */
    public int totalRuns() {
        return totalRuns.get();
    }

    /** 连续无产出轮数。 */
    public int idleRuns() {
        return idleRuns.get();
    }

    /** 本 Agent 名下的用户会话数（不含后台会话）。 */
    public int userSessionCount() {
        return sessionManager.getUserSessionCount();
    }

    // ==================== 委派执行 ====================

    /**
     * 用本 Agent 的配置一次性执行一段任务并返回结果文本 —— 供其他 Agent 委派调用。
     * <p>
     * 走一次性会话：跑完即销毁，不留历史。目标 Agent 的提示词、工具集、权限都来自
     * <b>它自己</b>的装配，不继承委派者的任何上下文 —— 否则「委派」就成了一条绕过
     * 调用方权限约束的通道。该一次性会话不注册子 Agent 工厂，因此不存在委派递归。
     *
     * @param prompt 完整任务提示词
     * @return 目标 Agent 的最终回复
     * @throws IllegalStateException 已下线，或该 Agent 声明为不可被委派
     */
    public String runOnce(String prompt) {
        if (closed) {
            throw new IllegalStateException("Agent is not registered: " + definition.id());
        }
        if (!definition.delegatable()) {
            throw new IllegalStateException("Agent is not delegatable: " + definition.id());
        }
        log.info("Agent [{}] invoked via delegation ({} chars)", definition.id(),
                prompt == null ? 0 : prompt.length());
        return sessionManager.runEphemeral(prompt);
    }

    // ==================== 心跳 ====================

    /**
     * 执行一次心跳 —— 幂等、可重入安全。
     * <p>
     * 顺序：暂停/上限判定 → 降频判定 → 重入闸 → 后台会话装配 → 取工作项（空则自省模板）
     * → 发送 → 统计产出。
     *
     * @return 本轮是否真的执行了一次（被跳过/未到时机时返回 {@code false}）
     */
    public boolean triggerHeartbeatOnce() {
        if (closed || !definition.isProactive()) {
            return false;
        }
        HeartbeatSpec spec = definition.heartbeat();
        if (spec == null || heartbeatPaused) {
            return false;
        }
        if (consecutiveRuns.get() >= spec.maxConsecutiveRuns()) {
            heartbeatPaused = true;
            log.info("Agent [{}] heartbeat auto-paused: reached maxConsecutiveRuns={}",
                    definition.id(), spec.maxConsecutiveRuns());
            return false;
        }
        if (shouldSkipForBackoff(spec)) {
            return false;
        }
        // 重入闸：上一次心跳若还在跑（长工具链），本次直接放弃而不是排队 ——
        // 固定周期心跳叠加排队会退化成「一跑完就立刻再跑」的忙循环。
        if (!ticking.compareAndSet(false, true)) {
            log.debug("Agent [{}] heartbeat skipped: previous tick still running", definition.id());
            return false;
        }
        try {
            return doHeartbeat(spec);
        } catch (RuntimeException e) {
            log.warn("Agent [{}] heartbeat failed: {}", definition.id(), e.getMessage());
            return false;
        } finally {
            ticking.set(false);
        }
    }

    /** 心跳主体 —— 已持重入闸。 */
    private boolean doHeartbeat(HeartbeatSpec spec) {
        String sessionId = ensureBackendSession();
        if (sessionId == null) {
            return false;
        }
        // 后台会话正在执行（上一轮未结束、或用户手动向它发过消息）→ 跳过本轮，不打断它
        SessionInfo info = sessionManager.getSessionInfo(sessionId);
        if (info != null && info.activity() != null && info.activity() != SessionActivity.IDLE) {
            log.debug("Agent [{}] heartbeat skipped: backend session is {}", definition.id(),
                    info.activity());
            return false;
        }
        if (sessionManager.isPaused(sessionId)) {
            sessionManager.resumeSession(sessionId);
        }

        AgentWorkQueue.WorkItem item = workQueue.poll();
        String prompt = item != null ? item.prompt() : spec.heartbeatPrompt();

        HmsResponse response = sessionManager.send(sessionId, prompt);
        boolean produced = item != null
                || (response != null && response.isSuccess() && response.toolCallsCount() > 0);

        consecutiveRuns.incrementAndGet();
        totalRuns.incrementAndGet();
        if (produced) {
            idleRuns.set(0);
            backoffTicks.set(0);
        } else {
            idleRuns.incrementAndGet();
        }
        log.debug("Agent [{}] heartbeat run #{} done (source={}, produced={})",
                definition.id(), totalRuns.get(), item != null ? item.source() : "introspection", produced);

        // 记忆清洗：放在心跳里而非 send() 的同步路径上 —— 它会调模型，绝不能挡用户请求。
        if (memory != null && memory.spec().cleanup()) {
            int merged = memory.consolidate();
            if (merged > 0) {
                log.info("Agent [{}] memory consolidated: {} entries merged", definition.id(), merged);
            }
        }
        return true;
    }

    /** 懒创建后台会话；失败返回 {@code null}。 */
    private String ensureBackendSession() {
        String existing = backendSessionId;
        if (existing != null && sessionManager.sessionExists(existing)) {
            return existing;
        }
        try {
            String created = sessionManager.createBackendSession(DEFAULT_BACKEND_SESSION_PROMPT);
            backendSessionId = created;
            log.info("Agent [{}] backend session created: {}", definition.id(), created);
            return created;
        } catch (RuntimeException e) {
            log.warn("Agent [{}] cannot create backend session: {}", definition.id(), e.getMessage());
            return null;
        }
    }

    /**
     * 空闲降频判定：连续 {@link HeartbeatSpec#maxIdleRuns()} 轮无产出后，
     * 每 {@code idleBackoffFactor^n} 个 tick 才真正执行一次。
     */
    private boolean shouldSkipForBackoff(HeartbeatSpec spec) {
        int idle = idleRuns.get();
        if (idle < spec.maxIdleRuns() || spec.idleBackoffFactor() <= 1.0) {
            return false;
        }
        int exponent = Math.min(idle - spec.maxIdleRuns(), MAX_BACKOFF_EXPONENT);
        int skip = (int) Math.min(Math.pow(spec.idleBackoffFactor(), exponent), MAX_BACKOFF_SKIP);
        if (skip <= 1) {
            return false;
        }
        return backoffTicks.incrementAndGet() % skip != 0;
    }

    /** 暂停心跳（保留队列与后台会话，等待显式恢复）。 */
    public void pauseHeartbeat() {
        heartbeatPaused = true;
        log.info("Agent [{}] heartbeat paused", definition.id());
    }

    /** 恢复心跳，并重置自动暂停与降频所依据的计数器。 */
    public void resumeHeartbeat() {
        heartbeatPaused = false;
        consecutiveRuns.set(0);
        idleRuns.set(0);
        backoffTicks.set(0);
        log.info("Agent [{}] heartbeat resumed", definition.id());
    }

    // ==================== 记忆刷新 ====================

    /**
     * 把当前记忆摘要刷新进本 Agent <b>所有</b>会话的 system prompt。
     * <p>
     * <b>默认不自动做</b>（见 {@link MemorySpec#autoRefreshDigest()}）：system prompt 是
     * 消息历史的第 0 条，改写它等于让整个 prompt cache 失效 —— 代价明确，所以要显式调用。
     * 日常取新记忆应走 {@code RecallAgentMemory} 工具（追加在尾部，不击穿前缀）。
     *
     * @return 被刷新的会话数
     */
    public int refreshMemoryDigest() {
        if (memory == null) {
            return 0;
        }
        memory.digest();  // 先取一次，确保缓存已按最新版本重建
        int refreshed = 0;
        for (SessionInfo info : sessionManager.listSessions()) {
            try {
                sessionManager.updateSessionPrompt(info.sessionId(), info.sessionPrompt());
                refreshed++;
            } catch (RuntimeException e) {
                log.debug("Cannot refresh prompt for session {}: {}", info.sessionId(), e.getMessage());
            }
        }
        return refreshed;
    }

    // ==================== 下线 ====================

    /**
     * 释放运行时资源：关闭会话管理器、清空工作队列。
     * <p>
     * <b>不负责取消心跳任务</b> —— 那是 {@link AgentHeartbeatScheduler#stop(String)} 的
     * 职责（它持有 {@code ScheduledFuture}），由 {@link AgentRegistry#unregister} 按
     * 「先停心跳、再释放运行时」的顺序调用。幂等。
     * <p>
     * 会话管理器一并关闭：它持有本 Agent 全部会话与一个清理任务。共享调度池不会被
     * 关闭（{@code ownsCleanupScheduler=false}），所以这一步不会误伤其他 Agent。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        workQueue.clear();
        try {
            sessionManager.close();
        } catch (RuntimeException e) {
            log.debug("Error closing session manager of agent [{}]: {}",
                    definition.id(), e.getMessage());
        }
        log.info("Agent runtime closed: {} (totalHeartbeatRuns={})", definition.id(), totalRuns.get());
    }

    /** 本 Agent 全部会话 id（含后台会话）—— 供下线时强制清理。 */
    public List<String> allSessionIds() {
        return sessionManager.listSessions().stream().map(SessionInfo::sessionId).toList();
    }

    @Override
    public String toString() {
        return "AgentRuntime[" + definition.id()
                + (definition.isProactive() ? ", " + definition.activationMode() + ")" : ")");
    }
}
