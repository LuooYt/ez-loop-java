package com.inspirationi.loop.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 把 {@link HmsCallbacks} 桥接成 {@link HmsEvent} 流的标准实现。
 * <p>
 * 集成方不必再手写匿名 {@code HmsCallbacks}：只需提供「事件往哪推」（{@code sink}）
 * 和「用户回答从哪来」（{@link PendingResponses}），本类负责事件建模、超长内容截断、
 * 以及同步/异步回调的正确对接。
 * <p>
 * <b>关于同步与异步回调：</b>本类把 {@link #onAskUser} 与 {@link #onPermissionRequest}
 * 覆写为返回 {@code null}，从而把决定权交给对应的 {@code *Async} 变体。这是必需的 ——
 * 库内部先调同步版，若同步版给出了明确结论就不会再走异步版；而本类的语义天然是异步的
 * （推事件出去、等回答回来），必须让同步版「弃权」。
 * <p>
 * 典型用法：
 * <pre>{@code
 * PendingResponses pending = new PendingResponses(300);
 * HmsCallbacks callbacks = new EventBridgeCallbacks(
 *         event -> transport.push(event.eventName(), json.writeValueAsString(event)),
 *         pending, sessionId);
 * sessionManager.send(sessionId, message, callbacks);
 * }</pre>
 *
 * @see HmsEvent
 * @see PendingResponses
 */
public class EventBridgeCallbacks implements HmsCallbacks {

    /** 工具执行结果的推送长度上限 —— 超出部分截断，避免单条事件过大。 */
    private static final int TOOL_RESULT_MAX_LENGTH = 5000;

    /** 思考内容的推送长度上限。 */
    private static final int THINKING_MAX_LENGTH = 2000;

    /** 截断标记。 */
    private static final String TRUNCATION_SUFFIX = "...[truncated]";

    /** 事件汇聚点 —— 每个事件推送一次。 */
    private final Consumer<HmsEvent> sink;

    /** 悬挂请求登记处 —— 用于等待用户回答。 */
    private final PendingResponses pending;

    /** 本回调所属的会话 ID。 */
    private final String sessionId;

    /**
     * @param sink      事件汇聚点，不可为 {@code null}
     * @param pending   悬挂请求登记处，不可为 {@code null}
     * @param sessionId 会话 ID，不可为 {@code null}
     */
    public EventBridgeCallbacks(Consumer<HmsEvent> sink, PendingResponses pending, String sessionId) {
        if (sink == null || pending == null || sessionId == null) {
            throw new IllegalArgumentException("sink, pending and sessionId must not be null");
        }
        this.sink = sink;
        this.pending = pending;
        this.sessionId = sessionId;
    }

    // ==================== 单向事件 ====================

    @Override
    public void onToken(String token) {
        sink.accept(new HmsEvent.Token(token));
    }

    @Override
    public void onToolUse(String toolName, String input, String result) {
        sink.accept(new HmsEvent.ToolUse(toolName, input, truncate(result, TOOL_RESULT_MAX_LENGTH)));
    }

    @Override
    public void onThinking(String thinking) {
        sink.accept(new HmsEvent.Thinking(truncate(thinking, THINKING_MAX_LENGTH)));
    }

    @Override
    public void onCompaction(com.inspirationi.loop.core.compact.CompactionResult result) {
        sink.accept(HmsEvent.Compaction.from(result));
    }

    @Override
    public void onComplete(HmsResponse response) {
        sink.accept(HmsEvent.Complete.from(response));
    }

    @Override
    public String onError(Throwable error) {
        sink.accept(new HmsEvent.Error(describe(error)));
        return "abort";
    }

    // ==================== 需要用户回答的事件 ====================

    /**
     * 弃权，把提问交给 {@link #onAskUserAsync} 处理。
     *
     * @return 恒为 {@code null}
     */
    @Override
    public String onAskUser(String question, List<String> options) {
        return null;
    }

    /**
     * 推送 {@code ask_user} 事件，返回等待用户回答的 Future。
     * <p>
     * 先登记等待、再推送事件：反过来的话，用户回答可能在登记完成前就到达并被丢弃。
     */
    @Override
    public CompletableFuture<String> onAskUserAsync(String question, List<String> options) {
        CompletableFuture<String> answer = pending.awaitAskUser(sessionId);
        sink.accept(new HmsEvent.AskUser(question, options != null ? options : List.of()));
        return answer;
    }

    /**
     * 弃权，把权限确认交给 {@link #onPermissionRequestAsync} 处理。
     *
     * @return 恒为 {@code null}
     */
    @Override
    public String onPermissionRequest(String toolName, String description) {
        return null;
    }

    /**
     * 推送 {@code permission} 事件，返回等待用户确认的 Future。
     * <p>
     * 与 {@link #onAskUserAsync} 同理，登记先于推送。
     */
    @Override
    public CompletableFuture<String> onPermissionRequestAsync(String toolName, String description) {
        CompletableFuture<String> choice = pending.awaitPermission(sessionId);
        sink.accept(new HmsEvent.Permission(toolName, description != null ? description : ""));
        return choice;
    }

    // ==================== 工具方法 ====================

    /** 截断超长文本，避免单条事件负载过大。 */
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + TRUNCATION_SUFFIX;
    }

    /** 提取可读的错误描述，消息为空时退回异常类名。 */
    private static String describe(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String message = error.getMessage();
        return message != null && !message.isBlank() ? message : error.getClass().getSimpleName();
    }
}
