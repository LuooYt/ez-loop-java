package com.inspirationi.loop.agent;

import com.inspirationi.loop.api.HmsErrorCode;
import com.inspirationi.loop.api.HmsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体注册中心 —— 运行时的插拔点，唯一的「有哪些 Agent」权威来源。
 * <p>
 * 纯内存、随进程存活。注册即上线（启动心跳、可被委派、可开新会话），
 * 注销即下线（停心跳、清队列、可选销毁会话）。
 * <p>
 * <b>Agent 只能由人通过 API 注册，模型不能自主创建 Agent</b> —— 因此本类
 * 不暴露任何工具入口。这是防止提示词注入凭空造出一个高危 Agent 的硬边界：
 * 模型能做的只有「发现已有 Agent 并委派」，不能改变可用 Agent 的集合本身。
 */
public final class AgentRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    /** 默认 Agent 的 id —— 与改造前的单 Agent 行为逐字节等价，不可下线。 */
    public static final String DEFAULT_AGENT_ID = "default";

    private final ConcurrentHashMap<String, AgentRuntime> runtimes = new ConcurrentHashMap<>();
    private final AgentHeartbeatScheduler heartbeatScheduler;

    public AgentRegistry(AgentHeartbeatScheduler heartbeatScheduler) {
        this.heartbeatScheduler = Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    }

    // ==================== 注册 ====================

    /**
     * 注册一个新 Agent。
     * <p>
     * 用 {@code putIfAbsent} 抢占 id：并发的两个注册请求里恰有一个成功，
     * 败者关闭自己的运行时并抛 {@link HmsErrorCode#AGENT_ALREADY_EXISTS}。
     * 用「先查后放」的写法会在两次操作之间留下窗口，让两个 Agent 同时上线
     * 而其中一个的会话永远被路由到另一个身上。
     *
     * @return 注册成功的运行时
     * @throws HmsException id 已被占用
     */
    public AgentRuntime register(AgentRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        String agentId = runtime.definition().id();
        AgentRuntime existing = runtimes.putIfAbsent(agentId, runtime);
        if (existing != null) {
            runtime.close();   // 败者立即释放，别留下一个无人引用的活运行时
            throw new HmsException(HmsErrorCode.AGENT_ALREADY_EXISTS,
                    "Agent already registered: " + agentId
                            + ". Use registerOrReplace to swap its definition.");
        }
        heartbeatScheduler.start(runtime);
        log.info("Agent registered: [{}] (tools={}, memory={}, activation={})",
                agentId, runtime.toolRegistry().size(),
                runtime.memory() != null, runtime.definition().activationMode());
        return runtime;
    }

    /**
     * 注册或就地替换 —— 已存在同 id 时用新定义顶替。
     * <p>
     * 旧 Agent 仍有存活会话时<b>拒绝替换</b>：那会让既有会话继续跑在旧装配上，
     * 而新会话跑在新装配上，「同一个 agentId 两套行为」是排查不清的。
     *
     * @return 被替换掉的旧运行时；首次注册时为 {@code null}
     * @throws HmsException 旧 Agent 仍有用户会话
     */
    public AgentRuntime registerOrReplace(AgentRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        String agentId = runtime.definition().id();
        AgentRuntime previous = runtimes.get(agentId);
        if (previous != null && previous.userSessionCount() > 0) {
            runtime.close();
            throw new HmsException(HmsErrorCode.AGENT_BUSY,
                    "Agent " + agentId + " has " + previous.userSessionCount()
                            + " live session(s); unregister(agentId, true) before replacing it.");
        }
        AgentRuntime replaced = runtimes.put(agentId, runtime);
        if (replaced != null) {
            heartbeatScheduler.stop(agentId);
            replaced.close();
            log.info("Agent replaced: [{}]", agentId);
        } else {
            log.info("Agent registered: [{}]", agentId);
        }
        heartbeatScheduler.start(runtime);
        return replaced;
    }

    // ==================== 查询 ====================

    /** 按 id 取运行时；不存在返回 {@code null}。 */
    public AgentRuntime get(String agentId) {
        return agentId == null ? null : runtimes.get(agentId);
    }

    /**
     * 按 id 取运行时，不存在即抛错。
     *
     * @throws HmsException {@link HmsErrorCode#AGENT_NOT_FOUND}
     */
    public AgentRuntime require(String agentId) {
        AgentRuntime runtime = get(agentId);
        if (runtime == null) {
            throw new HmsException(HmsErrorCode.AGENT_NOT_FOUND,
                    "Agent not found: " + agentId + ". Known agents: " + ids());
        }
        return runtime;
    }

    public boolean contains(String agentId) {
        return agentId != null && runtimes.containsKey(agentId);
    }

    /** 全部运行时（id 升序，输出稳定）。 */
    public List<AgentRuntime> list() {
        return runtimes.values().stream()
                .sorted(java.util.Comparator.comparing(rt -> rt.definition().id()))
                .toList();
    }

    /** 全部 id（升序）。 */
    public List<String> ids() {
        return runtimes.keySet().stream().sorted().toList();
    }

    /** 可被委派的 Agent（{@code delegatable=true}），供 ListAgents 工具渲染清单。 */
    public List<AgentRuntime> listDelegatable() {
        return list().stream().filter(rt -> rt.definition().delegatable()).toList();
    }

    /** 已注册 Agent 数量。 */
    public int size() {
        return runtimes.size();
    }

    /** 不可变视图（仅供只读遍历）。 */
    public Map<String, AgentRuntime> snapshot() {
        return Map.copyOf(runtimes);
    }

    // ==================== 下线 ====================

    /**
     * 下线一个 Agent。
     * <p>
     * 「先停心跳，再释放运行时」的顺序不可颠倒：反过来的话，正在执行的
     * 一次心跳会在运行时标记已关闭之后继续往会话里写 —— 那是一个既不在注册表里、
     * 又还在消耗 token 的幽灵 Agent。
     *
     * @param agentId Agent id
     * @param force   {@code true} 时无视存活会话、强制销毁；{@code false} 时有会话即拒绝
     * @return 是否确有该 Agent（不存在返回 {@code false}）
     * @throws HmsException 默认 Agent 不可下线；或 {@code force=false} 而仍有用户会话
     */
    public boolean unregister(String agentId, boolean force) {
        if (DEFAULT_AGENT_ID.equals(agentId)) {
            throw new HmsException(HmsErrorCode.AGENT_BUSY,
                    "The default agent cannot be unregistered.");
        }
        AgentRuntime runtime = runtimes.get(agentId);
        if (runtime == null) {
            return false;
        }
        int live = runtime.userSessionCount();
        if (!force && live > 0) {
            throw new HmsException(HmsErrorCode.AGENT_BUSY,
                    "Agent " + agentId + " has " + live
                            + " live session(s). Call unregister(agentId, true) to force.");
        }

        runtimes.remove(agentId);
        heartbeatScheduler.stop(agentId);
        runtime.pauseHeartbeat();

        if (force) {
            for (String sessionId : runtime.allSessionIds()) {
                try {
                    runtime.sessionManager().destroySession(sessionId);
                } catch (RuntimeException e) {
                    log.debug("Cannot destroy session {} of agent [{}]: {}",
                            sessionId, agentId, e.getMessage());
                }
            }
        }
        runtime.close();
        log.info("Agent unregistered: [{}] (force={}, destroyedSessions={})",
                agentId, force, force ? "all" : "none");
        return true;
    }

    /**
     * 关闭注册中心 —— 取消全部心跳并释放全部运行时。
     * <p>
     * 不销毁会话：会话归属各自的 {@code DefaultHmsSessionManager}，
     * 由容器按 Bean 的 {@code destroyMethod} 独立关闭。
     */
    @Override
    public void close() {
        for (AgentRuntime runtime : list()) {
            heartbeatScheduler.stop(runtime.definition().id());
            runtime.pauseHeartbeat();
            runtime.close();
        }
        runtimes.clear();
        log.info("AgentRegistry closed");
    }
}
