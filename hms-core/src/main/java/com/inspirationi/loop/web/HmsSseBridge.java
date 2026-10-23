package com.inspirationi.loop.web;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inspirationi.loop.api.EventBridgeCallbacks;
import com.inspirationi.loop.api.HmsCallbacks;
import com.inspirationi.loop.api.HmsErrorCode;
import com.inspirationi.loop.api.HmsEvent;
import com.inspirationi.loop.api.HmsSessionManager;
import com.inspirationi.loop.api.PendingResponses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 集成门面 —— 把一次「发消息 + 流式推送 + 等用户回答」收敛成一个方法调用。
 * <p>
 * 集成方通常只需要三行代码：
 * <pre>{@code
 * @GetMapping(value = "/{sessionId}/stream", produces = TEXT_EVENT_STREAM_VALUE)
 * public SseEmitter stream(@PathVariable String sessionId, @RequestParam String message) {
 *     return sseBridge.stream(sessionId, message);
 * }
 * }</pre>
 * 加上两个转发 {@link #submitAskResponse} / {@link #submitPermissionResponse} 的 POST 端点，
 * 以及会话销毁时的 {@link #release}，即可完成全部对接。
 * <p>
 * 本类负责：SSE 发射器生命周期、{@link HmsEvent} 的 JSON 序列化、
 * Agent 执行的线程调度（默认虚拟线程，不占用容器线程）、以及异常兜底推送。
 * <p>
 * 事件名与 JSON 字段名由 {@link HmsEvent} 定义，构成对前端的稳定契约。
 * 线程安全。
 * <p>
 * 实现 {@link AutoCloseable}：容器关闭时释放 SSE 连接与内部线程池。
 *
 * @see HmsEvent
 * @see EventBridgeCallbacks
 */
public class HmsSseBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HmsSseBridge.class);

    /** SSE 连接的空闲超时（毫秒）—— 需长于单轮 Agent 执行的预期耗时。 */
    private final long emitterTimeoutMillis;

    /** 会话管理器，用于发送消息。 */
    private final HmsSessionManager sessionManager;

    /** 悬挂请求登记处，用于等待/交付用户回答。 */
    private final PendingResponses pending;

    /** 执行 Agent 循环的线程池（{@code send} 是阻塞调用，不能占用容器线程）。 */
    private final ExecutorService executor;

    /** 事件序列化器。 */
    private final ObjectMapper objectMapper;

    /** sessionId → 当前连接。 */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /**
     * 一次 SSE 连接，同时代表「这一轮对话」。
     * <p>
     * 必须把发射器<b>绑定到发起它的那一轮执行</b>，而不是每次推送时按 sessionId
     * 现查。否则用户刷新页面后，上一轮仍在执行的事件（包括它发出的权限确认请求）
     * 会推送到新连接上 —— 前端看到的是「上一轮的回答」混进「这一轮」，
     * 甚至对已作废的工具调用做确认。
     */
    private static final class Connection {

        /** 本轮的 SSE 发射器。 */
        private final SseEmitter emitter;

        /** 是否已被同会话的新连接顶替（或被 release 关闭）。 */
        private final AtomicBoolean superseded = new AtomicBoolean(false);

        Connection(SseEmitter emitter) {
            this.emitter = emitter;
        }
    }

    /**
     * @param sessionManager       会话管理器
     * @param pending              悬挂请求登记处
     * @param executor             执行 Agent 循环的线程池
     * @param objectMapper         事件序列化器
     * @param emitterTimeoutMillis SSE 连接空闲超时（毫秒）
     */
    public HmsSseBridge(HmsSessionManager sessionManager, PendingResponses pending,
                        ExecutorService executor, ObjectMapper objectMapper,
                        long emitterTimeoutMillis) {
        this.sessionManager = sessionManager;
        this.pending = pending;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.emitterTimeoutMillis = emitterTimeoutMillis;
    }

    // ==================== 流式对话 ====================

    /**
     * 建立 SSE 连接并在后台线程执行一轮对话，全过程事件推送到该连接。
     * <p>
     * 会话不存在时不抛异常，而是返回一个只含 {@code error} 事件的已完成连接 ——
     * SSE 端点已经开始响应，此时抛异常前端只会看到连接中断。
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return 已注册的 SSE 发射器，可直接作为 Controller 返回值
     */
    public SseEmitter stream(String sessionId, String message) {
        if (!sessionManager.sessionExists(sessionId)) {
            return completedWithError(HmsErrorCode.SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }

        Connection connection = register(sessionId);
        SseEmitter emitter = connection.emitter;

        // 记录是否已推送过 error 事件：send 失败时回调链通常已推过一条，
        // 兜底不能再推第二条，否则前端会看到重复的错误提示。
        AtomicBoolean errorSent = new AtomicBoolean(false);
        HmsCallbacks callbacks = new EventBridgeCallbacks(event -> {
            if (event instanceof HmsEvent.Error) {
                errorSent.set(true);
            }
            sendTo(sessionId, connection, event);
        }, pending, sessionId);

        try {
            executor.execute(() -> {
                try {
                    sessionManager.send(sessionId, message, callbacks);
                } catch (Exception e) {
                    log.error("Chat failed for session {}: {}", sessionId, e.getMessage(), e);
                    // 兜底：send 也可能在回调链之外失败（如会话状态非法），此时需补一条 error
                    if (errorSent.compareAndSet(false, true)) {
                        sendTo(sessionId, connection, HmsEvent.Error.fromUpstream(e));
                    }
                } finally {
                    completeQuietly(connection.emitter);
                    // 只清理「自己的」等待请求：本轮已被新连接顶替时，登记表里的
                    // Future 属于新那一轮，清掉会让新连接的用户回答无处可去。
                    if (!connection.superseded.get()) {
                        pending.clear(sessionId);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池已关闭（容器停机中）：这一轮永远不会执行，也就不会有任何事件，
            // 连接必须当场收尾，否则前端会一直挂着等超时。
            log.warn("Executor rejected chat for session {} (shutting down)", sessionId);
            sendTo(sessionId, connection, HmsEvent.Error.fromUpstream(e));
            completeQuietly(connection.emitter);
        }

        return emitter;
    }

    // ==================== 用户回答转发 ====================

    /**
     * 交付用户对 AI 提问的回答。
     *
     * @return 是否成功交付（无人等待或已超时则为 {@code false}）
     */
    public boolean submitAskResponse(String sessionId, String response) {
        return pending.submitAskUser(sessionId, response);
    }

    /**
     * 交付用户对权限请求的确认。
     *
     * @param response {@code "allow"} 或 {@code "deny"}
     * @return 是否成功交付（无人等待或已超时则为 {@code false}）
     */
    public boolean submitPermissionResponse(String sessionId, String response) {
        return pending.submitPermission(sessionId, response);
    }

    // ==================== 生命周期 ====================

    /**
     * 释放会话占用的 SSE 连接与等待中的请求。
     * <p>
     * 应在销毁会话时调用。仅取消执行时用 {@link #cancelPending}，以保留 SSE 连接。
     *
     * @param sessionId 会话 ID
     */
    public void release(String sessionId) {
        pending.clear(sessionId);
        Connection connection = connections.remove(sessionId);
        if (connection != null) {
            // 标记作废：仍在执行的那一轮不得再把事件推给已释放的连接
            connection.superseded.set(true);
            completeQuietly(connection.emitter);
        }
    }

    /**
     * 释放等待中的请求但保留 SSE 连接 —— 用于「取消当前执行」。
     *
     * @param sessionId 会话 ID
     */
    public void cancelPending(String sessionId) {
        pending.clear(sessionId);
    }

    /**
     * 关闭桥接器 —— 结束所有 SSE 连接并停止 Agent 执行线程池。
     * <p>
     * 作为 Spring Bean 时由容器在关闭阶段自动调用（{@code @Bean} 的
     * {@code destroyMethod} 默认推断 {@code close}）。executor 由本类独占持有，
     * 因此在此关闭是安全的。
     */
    @Override
    public void close() {
        for (String sessionId : Set.copyOf(connections.keySet())) {
            release(sessionId);
        }
        executor.shutdownNow();
        log.info("HmsSseBridge closed");
    }

    // ==================== 内部实现 ====================

    /** 注册发射器，并接管其生命周期回调；同一会话的旧连接会被顶掉。 */
    private Connection register(String sessionId) {
        Connection connection = new Connection(createEmitter());
        SseEmitter emitter = connection.emitter;
        // remove(key, value)：只有仍是当前连接时才摘除，避免顶替之后
        // 旧连接的回调把新连接的登记项删掉。
        emitter.onCompletion(() -> connections.remove(sessionId, connection));
        emitter.onTimeout(() -> {
            log.info("SSE emitter timed out for session {}", sessionId);
            connections.remove(sessionId, connection);
        });
        emitter.onError(e -> {
            log.warn("SSE emitter error for session {}: {}", sessionId, e.getMessage());
            connections.remove(sessionId, connection);
        });

        Connection previous = connections.put(sessionId, connection);
        if (previous != null) {
            // 先把旧连接标记作废再关闭：作废标记让还在跑的那一轮停止推送，
            // 关闭只是让浏览器端立刻收到结束信号。
            previous.superseded.set(true);
            completeQuietly(previous.emitter);
        }
        return connection;
    }

    /**
     * 创建发射器。
     * <p>
     * 抽成可覆写的方法，是为了让测试能换成可记录事件的实现 —— 串台问题只有在
     * 「两条连接同时存在」时才会暴露，而发射器是内部构造的，不给缝就没法测。
     */
    protected SseEmitter createEmitter() {
        return new SseEmitter(emitterTimeoutMillis);
    }

    /**
     * 序列化并推送一个事件到<b>本轮自己的</b>连接。
     * <p>
     * 连接已断开或被顶替时静默丢弃：前端刷新后仍在执行的旧一轮不该把事件
     * 灌进新连接。
     */
    private void sendTo(String sessionId, Connection connection, HmsEvent event) {
        if (connection.superseded.get()) {
            log.debug("Dropping {} event of superseded connection for session {}",
                    event.eventName(), sessionId);
            return;
        }
        try {
            connection.emitter.send(SseEmitter.event()
                    .name(event.eventName())
                    .data(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            // 前端断开连接是常态，降级为 debug 并清理
            log.debug("SSE send failed for session {} ({}), dropping connection: {}",
                    sessionId, event.eventName(), e.getMessage());
            connections.remove(sessionId, connection);
        } catch (Exception e) {
            log.warn("Failed to serialize SSE event {} for session {}: {}",
                    event.eventName(), sessionId, e.getMessage());
        }
    }

    /** 关闭发射器，忽略「已经关了」之类的异常。 */
    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 连接可能已被容器关闭，忽略
        }
    }

    /** 构造一个只含 error 事件的已完成连接。 */
    private SseEmitter completedWithError(HmsErrorCode errorCode, String message) {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(HmsEvent.Error.of(errorCode, message))));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
