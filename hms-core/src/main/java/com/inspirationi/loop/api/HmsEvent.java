package com.inspirationi.loop.api;

import com.inspirationi.loop.core.compact.CompactionResult;

import java.util.List;

/**
 * Agent 执行过程中产生的事件 —— 传输中立的事件模型。
 * <p>
 * 这是 {@link HmsCallbacks} 的「数据化」形态：回调是推给方法，事件是推给
 * {@code Consumer<HmsEvent>}。集成方拿到事件后可以推 SSE、WebSocket、消息队列，
 * 或直接落库，不必再为每种传输重写一遍桥接逻辑。
 * <p>
 * 每个子类型的字段名即对外 JSON 字段名（由 Jackson 直接序列化 record 组件），
 * 事件名由 {@link #eventName()} 给出。改动字段名等同于改动对外契约。
 * <p>
 * 配合 {@link EventBridgeCallbacks} 使用：
 * <pre>{@code
 * HmsCallbacks callbacks = new EventBridgeCallbacks(
 *         event -> myTransport.push(event.eventName(), toJson(event)),
 *         pendingResponses, sessionId);
 * sessionManager.send(sessionId, message, callbacks);
 * }</pre>
 *
 * @see EventBridgeCallbacks
 * @see PendingResponses
 */
public sealed interface HmsEvent {

    /**
     * 事件名 —— 用作 SSE 的 {@code event:} 字段、消息队列的 routing key 等。
     *
     * @return 事件名（如 {@code "token"}、{@code "tool_use"}）
     */
    String eventName();

    /** 增量输出 token。 */
    record Token(String token) implements HmsEvent {
        @Override
        public String eventName() {
            return "token";
        }
    }

    /**
     * 工具调用的一个阶段 —— 含工具名、阶段、入参与执行结果。
     * <p>
     * <b>同一次工具调用会推送多次</b>：{@code START}（刚开始，{@code result} 为 null）、
     * {@code PROGRESS}（工具吐出的进度行，可 0 到多次）、{@code END}（完成，带结果）。
     * 消费方要按 {@code phase} 分流 —— 把每条都当独立调用会让工具用量翻几倍。
     *
     * @param toolName 工具名
     * @param phase    阶段：{@code START} / {@code PROGRESS} / {@code END}。用字符串
     *                 而非枚举，使新增阶段不破坏对外 JSON 契约
     * @param input    工具入参（JSON 文本）
     * @param result   执行结果；{@code START} 阶段为 null，{@code PROGRESS} 阶段是进度行
     */
    record ToolUse(String toolName, String phase, String input, String result) implements HmsEvent {
        @Override
        public String eventName() {
            return "tool_use";
        }
    }

    /** AI 思考过程片段。 */
    record Thinking(String thinking) implements HmsEvent {
        @Override
        public String eventName() {
            return "thinking";
        }
    }

    /**
     * 运行时活动状态变化 —— 「此刻正在做什么」。
     * <p>
     * 只在状态真正切换时推送。一次请求的活动事件必然以 {@code IDLE} 之外的状态开始，
     * 但<b>不保证以 IDLE 结束</b>：SSE 通道在 {@code complete} 后即关闭，收尾的
     * {@code IDLE} 往往已无接收端。消费方应把 {@code complete} / {@code error} 自身
     * 视作「回到空闲」的信号。
     *
     * @param activity 状态枚举名（{@code CALLING_MODEL} / {@code THINKING} /
     *                 {@code RESPONDING} / {@code USING_TOOL} / {@code WAITING_USER} /
     *                 {@code IDLE}）。用字符串而非枚举，使新增状态不破坏对外 JSON 契约
     * @param label    可直接展示的中文文案，由后端提供
     * @param detail   补充信息，如工具名；无则为 null
     */
    record Activity(String activity, String label, String detail) implements HmsEvent {
        @Override
        public String eventName() {
            return "activity";
        }
    }

    /**
     * AI 向用户提问 —— 需要集成方回传答案。
     *
     * @param question 问题文本
     * @param options  可选答案列表；空列表表示自由文本回答
     */
    record AskUser(String question, List<String> options) implements HmsEvent {
        @Override
        public String eventName() {
            return "ask_user";
        }
    }

    /** 工具执行前的权限确认请求 —— 需要集成方回传 allow/deny。 */
    record Permission(String toolName, String description) implements HmsEvent {
        @Override
        public String eventName() {
            return "permission";
        }
    }

    /**
     * 上下文被自动压缩 —— 消息历史已被改写。
     * <p>
     * 压缩会静默摘要或裁剪旧消息，使用方常需据此提示「上下文已压缩」，或统计
     * 压缩频次与层级分布（频繁触发 {@code FULL} 说明单轮上下文用量该调小了）。
     * <p>
     * {@code layer} 取字符串而非枚举，与本接口其余事件一致 —— 保证对外 JSON
     * 契约不随 {@code CompactLayer} 增减常量而变化。
     *
     * @param layer          压缩层级名（{@code MICRO} / {@code SESSION_MEMORY} /
     *                       {@code FULL} / {@code MANUAL}）
     * @param messagesBefore 压缩前消息数
     * @param messagesAfter  压缩后消息数
     * @param reason         结果描述
     */
    record Compaction(String layer, int messagesBefore, int messagesAfter,
                      String reason) implements HmsEvent {

        /** 从 {@link CompactionResult} 构建压缩事件。 */
        public static Compaction from(CompactionResult result) {
            return new Compaction(
                    result.layer() != null ? result.layer().name() : "UNKNOWN",
                    result.messagesBefore(), result.messagesAfter(), result.reason());
        }

        @Override
        public String eventName() {
            return "compaction";
        }
    }

    /**
     * 本轮执行完成。
     * <p>
     * 字段是从 {@link HmsResponse} 摊平出来的，而非直接内嵌 response：
     * 保证对外 JSON 结构不随 {@code HmsResponse} 增减组件而变化。
     */
    record Complete(String content, long totalTokens, int toolCallsCount,
                    boolean interrupted) implements HmsEvent {

        /** 从 {@link HmsResponse} 构建完成事件。 */
        public static Complete from(HmsResponse response) {
            return new Complete(response.content(), response.totalTokens(),
                    response.toolCallsCount(), response.interrupted());
        }

        @Override
        public String eventName() {
            return "complete";
        }
    }

    /** 执行过程中发生错误。 */
    /**
     * 执行过程中发生错误。
     *
     * @param message 面向人的错误描述
     * @param code    结构化错误码（如 {@code 6003} 认证失败、{@code 6004} 配额超限）——
     *                前端据此分支处理，不必去解析 message 文本。上游 SDK 的原始消息
     *                常是一串 JSON（{@code 403: {"error":{...}}}），既不便展示也无法
     *                稳定匹配。
     */
    record Error(String message, int code) implements HmsEvent {

        /** 由错误码构建 —— message 取该码的默认描述。 */
        public static Error of(HmsErrorCode errorCode, String message) {
            return new Error(
                    message != null && !message.isBlank() ? message : errorCode.defaultMessage(),
                    errorCode.code());
        }

        /** 从上游异常归类构建，见 {@link HmsErrorCode#classifyUpstream}。 */
        public static Error fromUpstream(Throwable error) {
            HmsErrorCode code = HmsErrorCode.classifyUpstream(error);
            String message = error != null ? error.getMessage() : null;
            if (message == null || message.isBlank()) {
                message = error != null ? error.getClass().getSimpleName() : "Unknown error";
            }
            return new Error(message, code.code());
        }

        @Override
        public String eventName() {
            return "error";
        }
    }
}
