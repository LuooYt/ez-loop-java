package com.inspirationi.loop.api;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 悬挂请求登记处 —— 管理「AI 提问 / 权限确认」到用户回答之间的等待。
 * <p>
 * Agent 执行线程调用 {@link #awaitAskUser} / {@link #awaitPermission} 拿到一个
 * {@link CompletableFuture} 并阻塞等待；用户回答到达时（HTTP 请求、WebSocket 消息等）
 * 由另一个线程调用 {@link #submitAskUser} / {@link #submitPermission} 完成它。
 * <p>
 * 超时时 Future 以**默认值正常完成**（提问 → {@code "skip"}，权限 → {@code "deny"}），
 * 而非异常完成 —— 这样上游拿到的始终是一个明确的决定。
 * <p>
 * 本类不依赖任何 Web/Servlet API，可用于 SSE、WebSocket、消息队列等任意传输。
 * 线程安全。
 *
 * @see EventBridgeCallbacks
 */
public class PendingResponses {

    private static final Logger log = LoggerFactory.getLogger(PendingResponses.class);

    /** 提问超时后的默认回答 —— 跳过本次提问。 */
    public static final String DEFAULT_ASK_ANSWER = "skip";

    /** 权限确认超时后的默认选择 —— 拒绝执行。 */
    public static final String DEFAULT_PERMISSION_CHOICE = "deny";

    /** 等待中的提问：sessionId → Future。 */
    private final Map<String, CompletableFuture<String>> askFutures = new ConcurrentHashMap<>();

    /** 等待中的权限确认：sessionId → Future。 */
    private final Map<String, CompletableFuture<String>> permissionFutures = new ConcurrentHashMap<>();

    /** 用户回答的等待上限（秒）。 */
    private final long timeoutSeconds;

    /**
     * @param timeoutSeconds 用户回答的等待上限（秒），超时后以默认值完成
     */
    public PendingResponses(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 用户回答的等待上限（秒）—— 供上游对齐自身的阻塞等待时长。 */
    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    // ==================== 等待（Agent 执行线程侧） ====================

    /**
     * 登记一个等待用户回答的提问。
     * <p>
     * 同一会话若已有未完成的提问，旧 Future 会以默认值完成后被替换，避免泄漏。
     *
     * @param sessionId 会话 ID
     * @return 等待用户回答的 Future；超时则以 {@value #DEFAULT_ASK_ANSWER} 完成
     */
    public CompletableFuture<String> awaitAskUser(String sessionId) {
        return register(askFutures, sessionId, DEFAULT_ASK_ANSWER, "askUser");
    }

    /**
     * 登记一个等待用户确认的权限请求。
     *
     * @param sessionId 会话 ID
     * @return 等待用户确认的 Future；超时则以 {@value #DEFAULT_PERMISSION_CHOICE} 完成
     */
    public CompletableFuture<String> awaitPermission(String sessionId) {
        return register(permissionFutures, sessionId, DEFAULT_PERMISSION_CHOICE, "permission");
    }

    /**
     * 该会话是否正在等待用户回答（提问或权限确认）。
     * <p>
     * 两张登记表本身就是这个问题的权威答案：{@code submit} 交付时会先把条目移除，
     * 因此表里有条目就意味着确实有人在等。用于会话信息快照 —— 阻塞在
     * {@code CompletableFuture.get()} 上的线程无法自己更新活动状态。
     */
    public boolean isWaitingForUser(String sessionId) {
        return askFutures.containsKey(sessionId) || permissionFutures.containsKey(sessionId);
    }

    /**
     * 登记 Future 并挂上超时兜底。
     * <p>
     * 注意 {@code completeOnTimeout} 的返回值必须是登记与返回的那一个 Future ——
     * 它作用在 Future 自身而非派生副本上，超时才会真正落到默认值。
     */
    private CompletableFuture<String> register(Map<String, CompletableFuture<String>> registry,
                                               String sessionId, String defaultValue, String kind) {
        CompletableFuture<String> future = new CompletableFuture<String>()
                .completeOnTimeout(defaultValue, timeoutSeconds, TimeUnit.SECONDS);

        CompletableFuture<String> previous = registry.put(sessionId, future);
        if (previous != null && !previous.isDone()) {
            log.warn("Superseding an unanswered {} request for session {}", kind, sessionId);
            previous.complete(defaultValue);
        }
        log.debug("Awaiting {} response for session {} (timeout {}s)", kind, sessionId, timeoutSeconds);
        return future;
    }

    // ==================== 提交（用户回答侧） ====================

    /**
     * 提交用户对 AI 提问的回答。
     *
     * @param sessionId 会话 ID
     * @param response  回答文本
     * @return 是否成功交付（无人等待或已超时则为 {@code false}）
     */
    public boolean submitAskUser(String sessionId, String response) {
        return submit(askFutures, sessionId, response, "askUser");
    }

    /**
     * 提交用户对权限请求的确认。
     *
     * @param sessionId 会话 ID
     * @param response  {@code "allow"} 或 {@code "deny"}
     * @return 是否成功交付（无人等待或已超时则为 {@code false}）
     */
    public boolean submitPermission(String sessionId, String response) {
        return submit(permissionFutures, sessionId, response, "permission");
    }

    private boolean submit(Map<String, CompletableFuture<String>> registry,
                           String sessionId, String response, String kind) {
        CompletableFuture<String> future = registry.remove(sessionId);
        if (future == null) {
            // 常见竞态：用户回答慢于超时，或前端重复提交。属正常情况，非错误。
            log.debug("No pending {} request for session {}, response discarded", kind, sessionId);
            return false;
        }
        boolean delivered = future.complete(response);
        if (delivered) {
            log.debug("Delivered {} response for session {}: {}", kind, sessionId, response);
        } else {
            log.warn("{} request for session {} already timed out, response discarded", kind, sessionId);
        }
        return delivered;
    }

    // ==================== 清理 ====================

    /**
     * 清理会话所有等待中的请求 —— 以默认值完成，让阻塞的执行线程立即继续。
     * <p>
     * 应在会话销毁或取消执行时调用，否则 Agent 线程会一直等到超时。
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        CompletableFuture<String> ask = askFutures.remove(sessionId);
        if (ask != null && !ask.isDone()) {
            ask.complete(DEFAULT_ASK_ANSWER);
        }
        CompletableFuture<String> permission = permissionFutures.remove(sessionId);
        if (permission != null && !permission.isDone()) {
            permission.complete(DEFAULT_PERMISSION_CHOICE);
        }
    }
}
