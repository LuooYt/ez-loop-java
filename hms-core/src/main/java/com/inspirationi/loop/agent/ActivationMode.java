package com.inspirationi.loop.agent;

/**
 * 智能体的激活模式 —— 决定它是「被叫才动」还是「自己会动」。
 * <p>
 * 两个维度的差别不在能力，而在**谁来发起一轮执行**：
 * <ul>
 *   <li>{@link #PASSIVE} —— 由外部消息驱动。{@code send()} / {@code sendStreaming()}
 *       是唯一的触发源，没有消息就没有消耗。这是 SDK 一直以来的形态，也是默认值。</li>
 *   <li>{@link #PROACTIVE} —— 由一个固定周期的心跳驱动，即便没有外部消息也会被唤醒
 *       （队列空时走自省提示词）。因为会自主消耗 token，它必须配 {@link HeartbeatSpec}
 *       的护栏，见 {@link AgentHeartbeatScheduler}。</li>
 * </ul>
 */
public enum ActivationMode {
    /** 被动：仅在收到消息时执行。 */
    PASSIVE,
    /** 主动：按固定周期心跳自主执行。 */
    PROACTIVE
}
