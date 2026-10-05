package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentRegistry;
import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.agent.AgentWorkQueue;
import com.inspirationi.loop.core.HookManager;
import com.inspirationi.loop.core.compact.CompactionResult;
import com.inspirationi.loop.permission.DenialTracker;
import com.inspirationi.loop.telemetry.MetricsCollector;
import com.inspirationi.loop.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 多智能体会话管理门面 —— 容器里唯一的 {@link HmsSessionManager} Bean。
 * <p>
 * <b>它自己不管理任何会话</b>，只做一件事：按 {@code sessionId} 找到归属的
 * {@link DefaultHmsSessionManager}，把调用原样转发过去。每个 Agent 各有一个
 * 独立的 manager 实例（在 {@code AgentRuntime} 里），会话级的互斥、活动状态、
 * 压缩、回调注册全都留在那边，一行没动。
 * <p>
 * <b>为什么用「门面 + 每 Agent 一个实例」而不是「一个 manager 内按 agentId 分桶」</b>：
 * manager 里那些并发不变量（per-session 互斥、{@code beginRequest/endRequest} 配对、
 * AskUser 回调的注册与摘除顺序、{@code compactNow} 的双重检查）都是<b>实例级</b>的。
 * 分桶意味着每一条都要重新论证「对每个桶是否仍成立」，而收益为零。独占实例下它们
 * 自然成立。代价是路由本身 —— 就是本类。
 * <p>
 * <b>未知 sessionId 的异常文本逐字符保持</b>：路由不到时回落到默认 manager，
 * 由它抛出与改造前完全相同的 {@code IllegalArgumentException("Session not found: …")}。
 * 若在本类里另造一句错误，既有集成方按消息文本做的判断会静默失效。
 */
public class MultiAgentSessionManager implements HmsSessionManager, AgentSessionManager {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentSessionManager.class);

    private final AgentRegistry registry;
    /** 默认 Agent 的会话管理器 —— 未知会话的回落目标，也是异常文本的权威来源。 */
    private final DefaultHmsSessionManager defaultManager;

    /**
     * sessionId → agentId 的路由缓存。
     * <p>
     * 只是缓存：命中后仍要校验会话确实存在，失效即删除并回落全扫。
     * 会话被销毁后 id 不会复用（UUID），所以不存在「旧映射指向新会话」的问题；
     * 唯一的失效场景是并发销毁，那时的校验会拦住它。
     */
    private final ConcurrentHashMap<String, String> ownerCache = new ConcurrentHashMap<>();

    public MultiAgentSessionManager(AgentRegistry registry, DefaultHmsSessionManager defaultManager) {
        this.registry = registry;
        this.defaultManager = defaultManager;
    }

    // ==================== 路由 ====================

    /** 找到会话归属的 manager；找不到时回落默认 manager（由它抛出既有的异常文本）。 */
    private HmsSessionManager owner(String sessionId) {
        if (sessionId == null) {
            return defaultManager;
        }
        String cached = ownerCache.get(sessionId);
        if (cached != null) {
            HmsSessionManager manager = managerOf(cached, sessionId);
            if (manager != null) {
                return manager;
            }
            ownerCache.remove(sessionId, cached);
        }
        for (AgentRuntime runtime : registry.list()) {
            if (runtime.sessionManager().sessionExists(sessionId)) {
                ownerCache.put(sessionId, runtime.definition().id());
                return runtime.sessionManager();
            }
        }
        return defaultManager;
    }

    /** 缓存命中后的校验：Agent 还在、且会话确实归它管。 */
    private HmsSessionManager managerOf(String agentId, String sessionId) {
        AgentRuntime runtime = registry.get(agentId);
        if (runtime == null) {
            return null;
        }
        DefaultHmsSessionManager manager = runtime.sessionManager();
        return manager.sessionExists(sessionId) ? manager : null;
    }

    // ==================== AgentSessionManager ====================

    @Override
    public AgentRegistry registry() {
        return registry;
    }

    @Override
    public AgentRuntime runtimeOf(String agentId) {
        return registry.require(agentId);
    }

    @Override
    public String createAgentSession(String agentId) {
        return createAgentSession(agentId, null);
    }

    @Override
    public String createAgentSession(String agentId, String sessionPrompt) {
        AgentRuntime runtime = registry.require(agentId);
        String sessionId = sessionPrompt == null
                ? runtime.sessionManager().createSession()
                : runtime.sessionManager().createSession(sessionPrompt);
        ownerCache.put(sessionId, agentId);
        return sessionId;
    }

    @Override
    public String agentIdOf(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        String cached = ownerCache.get(sessionId);
        if (cached != null && managerOf(cached, sessionId) != null) {
            return cached;
        }
        for (AgentRuntime runtime : registry.list()) {
            if (runtime.sessionManager().sessionExists(sessionId)) {
                String agentId = runtime.definition().id();
                ownerCache.put(sessionId, agentId);
                return agentId;
            }
        }
        return null;
    }

    @Override
    public AgentWorkQueue.WorkItem enqueueWork(String agentId, String prompt) {
        AgentRuntime runtime = registry.require(agentId);
        AgentWorkQueue.WorkItem item = runtime.workQueue().enqueue(prompt, "external");
        log.info("Work enqueued for agent [{}] (queue size: {})", agentId, runtime.workQueue().size());
        return item;
    }

    @Override
    public boolean pauseHeartbeat(String agentId) {
        AgentRuntime runtime = registry.get(agentId);
        if (runtime == null) {
            return false;
        }
        runtime.pauseHeartbeat();
        return true;
    }

    @Override
    public boolean resumeHeartbeat(String agentId) {
        AgentRuntime runtime = registry.get(agentId);
        if (runtime == null) {
            return false;
        }
        runtime.resumeHeartbeat();
        return true;
    }

    // ==================== 会话生命周期（HmsSessionManager） ====================

    /**
     * 创建默认 Agent 的会话。
     * <p>
     * <b>始终落在默认 Agent 上</b>，而不是「最近注册的」或「第一个」—— 这是既有调用方
     * 行为不变的前提：它们拿到的是一个具备全局提示词、全局工具、全局权限设置的会话。
     */
    @Override
    public String createSession() {
        String sessionId = defaultManager.createSession();
        ownerCache.put(sessionId, registry.get(AgentRegistry.DEFAULT_AGENT_ID) != null
                ? AgentRegistry.DEFAULT_AGENT_ID : "");
        return sessionId;
    }

    @Override
    public String createSession(String customSessionPrompt) {
        String sessionId = defaultManager.createSession(customSessionPrompt);
        ownerCache.put(sessionId, AgentRegistry.DEFAULT_AGENT_ID);
        return sessionId;
    }

    @Override
    public void destroySession(String sessionId) {
        owner(sessionId).destroySession(sessionId);
        ownerCache.remove(sessionId);
    }

    @Override
    public void pauseSession(String sessionId) {
        owner(sessionId).pauseSession(sessionId);
    }

    @Override
    public void resumeSession(String sessionId) {
        owner(sessionId).resumeSession(sessionId);
    }

    @Override
    public boolean isPaused(String sessionId) {
        return owner(sessionId).isPaused(sessionId);
    }

    @Override
    public boolean sessionExists(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        String cached = ownerCache.get(sessionId);
        if (cached != null && managerOf(cached, sessionId) != null) {
            return true;
        }
        return agentIdOf(sessionId) != null;
    }

    // ==================== 消息发送 ====================

    @Override
    public HmsResponse send(String sessionId, String userMessage) {
        return owner(sessionId).send(sessionId, userMessage);
    }

    @Override
    public HmsResponse sendStreaming(String sessionId, String userMessage, Consumer<String> onToken) {
        return owner(sessionId).sendStreaming(sessionId, userMessage, onToken);
    }

    @Override
    public HmsResponse send(String sessionId, String userMessage, HmsCallbacks callbacks) {
        return owner(sessionId).send(sessionId, userMessage, callbacks);
    }

    // ==================== 会话控制 ====================

    @Override
    public void cancel(String sessionId) {
        owner(sessionId).cancel(sessionId);
    }

    @Override
    public TokenStats getSessionTokenStats(String sessionId) {
        return owner(sessionId).getSessionTokenStats(sessionId);
    }

    @Override
    public CompactionResult compactNow(String sessionId) {
        return owner(sessionId).compactNow(sessionId);
    }

    @Override
    public boolean resetCompactionCircuitBreaker(String sessionId) {
        return owner(sessionId).resetCompactionCircuitBreaker(sessionId);
    }

    @Override
    public void updateSessionPrompt(String sessionId, String sessionPrompt) {
        owner(sessionId).updateSessionPrompt(sessionId, sessionPrompt);
    }

    @Override
    public ToolRegistry getSessionToolRegistry(String sessionId) {
        return owner(sessionId).getSessionToolRegistry(sessionId);
    }

    // ==================== 扩展点 ====================

    @Override
    public HookManager getSessionHooks(String sessionId) {
        return owner(sessionId).getSessionHooks(sessionId);
    }

    @Override
    public DenialTracker getSessionDenials(String sessionId) {
        return owner(sessionId).getSessionDenials(sessionId);
    }

    // ==================== 信息查询 ====================

    @Override
    public SessionInfo getSessionInfo(String sessionId) {
        return owner(sessionId).getSessionInfo(sessionId);
    }

    @Override
    public List<ChatMessage> getSessionMessages(String sessionId) {
        return owner(sessionId).getSessionMessages(sessionId);
    }

    /**
     * 列出<b>全部</b> Agent 的会话（含主动 Agent 的后台会话）。
     * <p>
     * 后台会话不做过滤：它承载着主动 Agent 的产物，用户需要能看到并回看，
     * 靠 {@link SessionInfo#agentId()} 与普通会话区分即可。
     */
    @Override
    public List<SessionInfo> listSessions() {
        List<SessionInfo> all = new ArrayList<>();
        for (AgentRuntime runtime : registry.list()) {
            all.addAll(runtime.sessionManager().listSessions());
        }
        return all;
    }

    @Override
    public int getActiveSessionCount() {
        int total = 0;
        for (AgentRuntime runtime : registry.list()) {
            total += runtime.sessionManager().getActiveSessionCount();
        }
        return total;
    }

    @Override
    public MetricsCollector getSessionMetrics(String sessionId) {
        return owner(sessionId).getSessionMetrics(sessionId);
    }

    // ==================== 运维 ====================

    /**
     * 清理各 Agent 的空闲会话并汇总数量。
     * <p>
     * 清完就<b>整体丢弃路由缓存</b>：被清掉的会话 id 无从从返回值里得知（各 manager
     * 只回一个计数），而缓存里的死映射会在下次命中时被校验拦住 —— 那是对的，但
     * 与其留一批必然失效的条目，不如一次清空重来。清空是安全的：路由会自动重建。
     */
    @Override
    public int cleanupIdleSessions(long idleTimeoutSeconds) {
        int cleaned = 0;
        for (AgentRuntime runtime : registry.list()) {
            cleaned += runtime.sessionManager().cleanupIdleSessions(idleTimeoutSeconds);
        }
        if (cleaned > 0) {
            ownerCache.clear();
        }
        return cleaned;
    }

    /**
     * 关闭门面 —— 关闭注册中心（逐个下线 Agent 并关掉它们的会话管理器），
     * 再关闭默认 Agent 的管理器。
     * <p>
     * 默认 Agent 也在注册表里，会被第一次关闭；{@code close()} 幂等，重复调用无副作用。
     * 单独再调一次是为了覆盖「默认 Agent 未被注册」的装配（那时它仍是一个可用的
     * 管理器，不能被漏掉）。
     */
    @Override
    public void close() {
        registry.close();
        defaultManager.close();
        ownerCache.clear();
        log.info("MultiAgentSessionManager closed");
    }
}
