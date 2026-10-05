package com.inspirationi.loop.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 主动 Agent 的固定周期心跳调度器。
 * <p>
 * 只做三件事：为 PROACTIVE Agent 注册周期任务、取消任务、跟踪在跑的任务数。
 * 「一次心跳该做什么」全部委派给 {@link AgentRuntime#triggerHeartbeatOnce()} ——
 * 调度与决策分离，于是心跳逻辑可以用可控时钟直接测试，无需依赖真实定时器。
 * <p>
 * <b>不拥有线程池</b>：构造时传入的 {@link ScheduledExecutorService} 由调用方
 * （{@code ApiAutoConfiguration}）持有，与空闲会话清理共用。因此 {@link #close()}
 * 只取消本调度器登记的任务，<b>绝不 shutdown 线程池</b> —— 否则会顺带杀死
 * 会话清理任务。若本类去 shutdown 别人的池，那是把一个「取消订阅」动作
 * 做成了「拆除总线」。
 * <p>
 * 用 {@code scheduleWithFixedDelay} 而非 {@code scheduleAtFixedRate}：前者保证
 * 相邻两次执行的<b>间隔</b>不小于周期，不会被一次跑很久的心跳拖进「补偿式连发」。
 */
public final class AgentHeartbeatScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentHeartbeatScheduler.class);

    private final ScheduledExecutorService scheduler;

    /** agentId → 心跳任务句柄。必须保存句柄，否则无从取消。 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public AgentHeartbeatScheduler(ScheduledExecutorService scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * 为一个主动 Agent 启动心跳（被动 Agent 为空操作）。
     * <p>
     * 同一 agentId 重复启动时，先取消旧任务再登记新任务 —— 否则替换定义后
     * 会有两个心跳同时驱动同一个 Agent，token 消耗翻倍且无从察觉。
     */
    public void start(AgentRuntime runtime) {
        if (runtime == null || !runtime.definition().isProactive()) {
            return;
        }
        HeartbeatSpec spec = runtime.definition().heartbeat();
        if (spec == null) {
            return;
        }
        String agentId = runtime.definition().id();
        long interval = spec.intervalSeconds();

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                runtime.triggerHeartbeatOnce();
            } catch (Throwable t) {
                // 单次失败不能让周期任务整体取消 —— ScheduledExecutorService 会静默吞掉
                // 抛出异常后的所有后续执行，心跳就此永久停摆。
                log.error("Heartbeat tick failed for agent [{}]: {}", agentId, t.toString(), t);
            }
        }, interval, interval, TimeUnit.SECONDS);

        ScheduledFuture<?> previous = tasks.put(agentId, future);
        if (previous != null) {
            previous.cancel(false);
        }
        log.info("Heartbeat started for agent [{}] (interval={}s)", agentId, interval);
    }

    /** 停止并取消指定 Agent 的心跳任务；未登记时为空操作。 */
    public void stop(String agentId) {
        ScheduledFuture<?> future = tasks.remove(agentId);
        if (future != null) {
            future.cancel(false);
            log.info("Heartbeat stopped for agent [{}]", agentId);
        }
    }

    /** 该 Agent 是否已登记心跳任务。 */
    public boolean isRunning(String agentId) {
        return tasks.containsKey(agentId);
    }

    /** 在跑的心跳任务数。 */
    public int size() {
        return tasks.size();
    }

    /** 取消全部心跳任务（不关闭共享线程池）。 */
    @Override
    public void close() {
        for (String agentId : java.util.List.copyOf(tasks.keySet())) {
            stop(agentId);
        }
    }
}
