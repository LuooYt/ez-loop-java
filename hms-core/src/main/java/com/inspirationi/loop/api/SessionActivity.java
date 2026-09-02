package com.inspirationi.loop.api;

/**
 * 会话的运行时活动状态 —— 「此刻正在做什么」。
 * <p>
 * 与 {@link SessionStatus} 是<b>正交的两个维度</b>，各自独立演进：
 * <ul>
 *   <li>{@code SessionStatus} 管「能否接收消息」（生命周期）</li>
 *   <li>{@code SessionActivity} 管「正在做什么」（单轮请求内的阶段）</li>
 * </ul>
 * 一个 {@code PAUSED} 会话的活动状态必然是 {@link #IDLE}，但反之不成立 ——
 * {@code ACTIVE} 会话既可能空闲，也可能正在调模型。
 * <p>
 * <b>状态机</b>（单轮请求内，箭头为正常流转）：
 * <pre>
 *   IDLE → CALLING_MODEL ⇄ THINKING → RESPONDING → IDLE
 *                ↑              ↓
 *                └── USING_TOOL ⇄ WAITING_USER
 * </pre>
 * 无论正常结束、异常、取消还是撞上迭代上限，都必须回到 {@link #IDLE} ——
 * 承载状态的 {@code AgentLoop} 是会话级持久对象，漏掉复位会污染该会话此后
 * 所有请求（复位由 {@code AgentLoop.executeLoop} 的 finally 保证）。
 */
public enum SessionActivity {

    /** 空闲：没有请求在执行 */
    IDLE("空闲"),

    /**
     * 正在等待模型响应。
     * <p>
     * 覆盖「请求已发出、首个内容尚未到达」这段等待期。没有这一态，未开启
     * extended thinking 或走阻塞路径时整段等待会没有任何状态可显示。
     */
    CALLING_MODEL("思考中"),

    /**
     * 模型正在深度思考（extended thinking）。
     * <p>
     * 仅在流式且开启了 thinking 时才具备实时性；阻塞路径下 thinking 内容随响应
     * 一次性返回，此时该状态只是一闪而过（详见 {@code AgentLoop.blockingIteration}）。
     */
    THINKING("深度思考中"),

    /** 正在输出回答正文（首个非 thinking token 已到达） */
    RESPONDING("回复中"),

    /** 正在执行工具 */
    USING_TOOL("调用工具"),

    /** 正在等待用户回答提问或确认权限 */
    WAITING_USER("待你确认");

    private final String label;

    SessionActivity(String label) {
        this.label = label;
    }

    /**
     * 面向用户的中文展示文案。
     * <p>
     * 由后端提供而非前端映射：新增状态时前端无需同步改动，未识别的状态也有
     * 可读文案兜底 —— 与 {@code HmsEvent.Compaction.layer} 用 String 传枚举名
     * 同一考虑。
     */
    public String label() {
        return label;
    }
}
