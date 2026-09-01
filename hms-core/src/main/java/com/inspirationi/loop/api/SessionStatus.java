package com.inspirationi.loop.api;

/**
 * 会话状态。
 */
public enum SessionStatus {
    /** 活跃：可接收消息 */
    ACTIVE,
    /** 暂停：拒绝新消息，保留上下文 */
    PAUSED,
    /** 空闲：超过阈值未活动 */
    IDLE,
    /** 已销毁 */
    DESTROYED
}
