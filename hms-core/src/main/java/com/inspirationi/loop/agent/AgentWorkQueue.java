package com.inspirationi.loop.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Agent 级工作队列 —— 主动智能体的待处理工作项，FIFO。
 * <p>
 * 与 {@code core.TaskManager} 的区别：TaskManager 是**任务跟踪器**（创建、状态流转、
 * 取消），建出来的 PENDING 任务无人取出执行；本队列是**消费者-生产者**语义 ——
 * {@link AgentRuntime#triggerHeartbeatOnce()} 按心跳节拍 {@link #poll()} 取出并交给
 * Agent 执行。因此不复用 TaskManager，避免「谁负责推进状态」的语义混淆。
 * <p>
 * 线程安全：底层 {@link ConcurrentLinkedDeque} 无锁，入队（心跳线程之外的任意发起方）
 * 与出队（心跳线程）可并发；单项 poll 原子，不会同一工作项被执行两次。
 */
public final class AgentWorkQueue {

    /**
     * ToolContext 中存放本队列的键名 —— 由 {@code AgentRuntimeFactory} 写入 Agent 级
     * 上下文，工作队列工具据此取用。
     */
    public static final String CONTEXT_KEY = "AGENT_WORK_QUEUE";

    /**
     * 一个待处理工作项。
     *
     * @param attempts 已经失败的次数（首次入队为 0）。执行失败被放回队列时递增，
     *                 超过上限即丢弃 —— 否则一个必定失败的工作项会在心跳里无限循环
     */
    public record WorkItem(String id, String prompt, String source, Instant createdAt, int attempts) {

        /** 首次入队的便捷构造。 */
        public WorkItem(String id, String prompt, String source, Instant createdAt) {
            this(id, prompt, source, createdAt, 0);
        }

        /** 失败重试的副本 —— 保留 id 与创建时间，尝试次数 +1。 */
        public WorkItem withNextAttempt() {
            return new WorkItem(id, prompt, source, createdAt, attempts + 1);
        }
    }

    private final ConcurrentLinkedDeque<WorkItem> queue = new ConcurrentLinkedDeque<>();

    /** 入队一个工作项。 */
    public void enqueue(WorkItem item) {
        if (item != null) {
            queue.addLast(item);
        }
    }

    /** 便捷入队：以提示词与来源自动生成工作项。 */
    public WorkItem enqueue(String prompt, String source) {
        WorkItem item = new WorkItem(
                UUID.randomUUID().toString().substring(0, 8),
                prompt, source, Instant.now());
        queue.addLast(item);
        return item;
    }

    /**
     * 把工作项放回队首 —— 执行失败时使用，下次心跳先重试它。
     * <p>
     * 必须是队首：工作项是被 poll 出来之后才失败的，插回队尾会让它在整个队列
     * 之后重排一轮，而排在它后面的工作项可能依赖它先完成。
     */
    public void enqueueFirst(WorkItem item) {
        if (item != null) {
            queue.addFirst(item);
        }
    }

    /** 取出队首工作项；队列为空返回 {@code null}。 */
    public WorkItem poll() {
        return queue.pollFirst();
    }

    /** 查看队首但不移除；为空返回 {@code null}。 */
    public WorkItem peek() {
        return queue.peekFirst();
    }

    /** 当前待处理数量。 */
    public int size() {
        return queue.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** 清空队列（下线 Agent 时调用）。 */
    public void clear() {
        queue.clear();
    }

    /** 不可变快照，按入队顺序（用于观测/列队工具）。 */
    public List<WorkItem> snapshot() {
        return List.copyOf(new ArrayList<>(queue));
    }
}
