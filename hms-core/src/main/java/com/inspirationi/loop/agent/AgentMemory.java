package com.inspirationi.loop.agent;

import java.util.List;

/**
 * Agent 级共享记忆 —— 跨该 Agent 所有会话可读可写、随 Agent 存活。
 * <p>
 * 与「会话记忆」的区别：会话记忆是单个会话的消息历史（在 {@code AgentLoop} 里，
 * 由压缩层管理）；本记忆是**该 Agent 下所有会话共享**的一段可累积内容，其中一个会话
 * 写入的事实，此后新建的会话都能通过注入的 digest 看到，既有会话也能经 recall 取回。
 * <p>
 * 并发语义：实现须保证「读取者永远看到某个写入前缀的一致视图」，不会读到半条记忆。
 */
public interface AgentMemory {

    /**
     * ToolContext 中存放本记忆实例的键名 —— 由 {@code AgentRuntimeFactory} 写入
     * Agent 级上下文，记忆工具据此取用。定义在此而非工具类里，是为了让「键与它承载的
     * 类型」待在一起：两边各写一个字符串常量，改一处漏一处就会静默失效。
     */
    String CONTEXT_KEY = "AGENT_MEMORY";

    /**
     * 写入一条记忆（写入期做归一化与去重）。
     *
     * @param content 记忆正文（空白内容被忽略）
     * @param source  来源标识（仅追溯用）
     * @param tags    标签（可为 null/空）
     * @return 记忆 ID；内容为空而未被写入时返回 {@code null}
     */
    String remember(String content, String source, List<String> tags);

    /** 按 ID 删除一条记忆；不存在返回 {@code false}。 */
    boolean forget(String memoryId);

    /** 清空全部记忆。 */
    void clear();

    /** 全部记忆的不可变快照（时间升序）。 */
    List<AgentMemoryEntry> entries();

    /**
     * 按关键字检索（大小写不敏感的包含匹配，命中 content 或 tags）。
     *
     * @param query 关键字；空白表示「不过滤」，返回最新的若干条
     * @param limit 最多返回条数
     * @return 命中的记忆（时间倒序，最新在前）
     */
    List<AgentMemoryEntry> recall(String query, int limit);

    /**
     * 生成注入 system prompt 的摘要。
     *
     * @return 摘要文本；<b>无记忆时返回空字符串</b>（调用方据此跳过该段）
     */
    String digest();

    /** digest 的版本号 —— 每次记忆变更自增，供调用方判断是否需要刷新。 */
    long digestVersion();

    /** 当前条目数。 */
    int size();

    /** 是否为空。 */
    boolean isEmpty();

    /** 记忆规格。 */
    MemorySpec spec();

    /**
     * 触发一次 AI 清洗（把最旧一批记忆合并成少数几条精炼记忆）。
     * <p>
     * 由外部按阈值择机调用（见 {@code AgentHeartbeatScheduler} / {@code AgentRuntime}），
     * <b>绝不在 send() 的同步路径上调用</b>。未配置清洗器或未越阈值时为空操作。
     *
     * @return 实际被合并掉的条目数（0 表示未清洗）
     */
    int consolidate();
}
