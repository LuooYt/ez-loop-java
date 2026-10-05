package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentRegistry;
import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.agent.AgentWorkQueue;

/**
 * 多智能体会话管理 —— 在 {@link HmsSessionManager} 之上补出「按 Agent 开会话」的维度。
 * <p>
 * 刻意做成<b>独立接口</b>而不是往 {@link HmsSessionManager} 里塞方法：后者是 SDK
 * 唯一的对外集成入口，已经冻结；把它撑宽会让所有既有实现类一并失效。集成方按需
 * 注入本接口来使用多智能体能力，只用单 Agent 的代码路径一个字都不用改。
 * <p>
 * 默认 Agent 之外的会话都从这里创建 —— {@code HmsSessionManager.createSession()}
 * 始终落在默认 Agent 上，因此既有调用方的行为不变。
 */
public interface AgentSessionManager {

    /** 智能体注册中心 —— 注册、下线、查询 Agent。 */
    AgentRegistry registry();

    /**
     * 取指定 Agent 的运行时。
     *
     * @throws HmsException {@code AGENT_NOT_FOUND}
     */
    AgentRuntime runtimeOf(String agentId);

    /**
     * 为指定 Agent 创建会话（使用默认会话提示词）。
     * <p>
     * <b>方法名里的 {@code Agent} 不能省</b>：{@link HmsSessionManager#createSession(String)}
     * 的同签名方法语义完全不同 —— 那个参数是<b>会话提示词</b>，这个是<b>智能体 id</b>。
     * 两者同名会让同时实现两个接口的类无法编译，而更糟的是：若哪天参数类型也恰好
     * 相容，一个「传错参数」的调用会静默通过编译。
     *
     * @return 会话 id
     * @throws HmsException Agent 不存在，或该 Agent 的会话数已达上限
     */
    String createAgentSession(String agentId);

    /**
     * 为指定 Agent 创建会话（自定义会话提示词）。
     *
     * @throws HmsException Agent 不存在，或该 Agent 的会话数已达上限
     */
    String createAgentSession(String agentId, String sessionPrompt);

    /**
     * 查询某个会话属于哪个 Agent。
     *
     * @return Agent id；会话不存在时返回 {@code null}
     */
    String agentIdOf(String sessionId);

    /**
     * 向主动 Agent 的工作队列投递一项工作 —— 它会在下一次心跳时被执行。
     * <p>
     * 这是「外部可投递」的那一半：另一半是 Agent 自己用 {@code EnqueueWork} 工具排期。
     *
     * @return 排入的工作项
     * @throws HmsException Agent 不存在
     */
    AgentWorkQueue.WorkItem enqueueWork(String agentId, String prompt);

    /**
     * 暂停指定 Agent 的心跳 —— 它会保留工作队列与后台会话，直到显式恢复。
     *
     * @return 该 Agent 是否存在
     */
    boolean pauseHeartbeat(String agentId);

    /**
     * 恢复指定 Agent 的心跳，并重置「连续运行上限」与「空闲降频」所依据的计数器。
     *
     * @return 该 Agent 是否存在
     */
    boolean resumeHeartbeat(String agentId);
}
