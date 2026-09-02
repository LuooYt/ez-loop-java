package com.inspirationi.loop.api;

/**
 * 会话状态。
 */
public enum SessionStatus {
    /** 活跃：可接收消息 */
    ACTIVE,
    /** 暂停：拒绝新消息，保留上下文 */
    PAUSED,
    /**
     * 空闲：超过阈值未活动。
     * <p>
     * <b>当前实现从不设置该状态</b>：空闲会话由清理线程直接 {@code destroy()} 并移出
     * 会话映射，没有「先标记空闲、再回收」的中间态。因此 {@link SessionInfo#status()}
     * 永远不会返回它，调用方无需为这个分支写处理逻辑。
     * <p>
     * 保留常量是为了不破坏已按枚举全集做穷举的集成方代码（switch 覆盖、状态映射表）；
     * {@code LoopSession.pause()} 也仍容许从它转入 PAUSED，以便将来引入软空闲标记时
     * 不必改动状态机。
     */
    IDLE,
    /** 已销毁 */
    DESTROYED
}
