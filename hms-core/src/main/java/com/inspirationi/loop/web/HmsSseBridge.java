package com.inspirationi.loop.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inspirationi.loop.api.EventBridgeCallbacks;
import com.inspirationi.loop.api.HmsCallbacks;
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
 *
 * @see HmsEvent
 * @see EventBridgeCallbacks
 */
public class HmsSseBridge {

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

    /** sessionId → SseEmitter。 */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

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
            return completedWithError("会话不存在: " + sessionId);
        }

        SseEmitter emitter = register(sessionId);

        // 记录是否已推送过 error 事件：send 失败时回调链通常已推过一条，
        // 兜底不能再推第二条，否则前端会看到重复的错误提示。
        AtomicBoolean errorSent = new AtomicBoolean(false);
        HmsCallbacks callbacks = new EventBridgeCallbacks(event -> {
            if (event instanceof HmsEvent.Error) {
                errorSent.set(true);
            }
            send(sessionId, event);
        }, pending, sessionId);

        executor.execute(() -> {
            try {
                sessionManager.send(sessionId, message, callbacks);
                emitter.complete();
            } catch (Exception e) {
                log.error("Chat failed for session {}: {}", sessionId, e.getMessage(), e);
                // 兜底：send 也可能在回调链之外失败（如会话状态非法），此时需补一条 error
                if (errorSent.compareAndSet(false, true)) {
                    send(sessionId, new HmsEvent.Error(
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
                emitter.complete();
            } finally {
                // 执行结束后不再有人回答提问，释放仍在等待的 Future
                pending.clear(sessionId);
            }
        });

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
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 连接可能已被容器关闭，忽略
            }
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

    // ==================== 内部实现 ====================

    /** 注册发射器，并接管其生命周期回调；同一会话的旧连接会被顶掉。 */
    private SseEmitter register(String sessionId) {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
        emitter.onCompletion(() -> emitters.remove(sessionId, emitter));
        emitter.onTimeout(() -> {
            log.info("SSE emitter timed out for session {}", sessionId);
            emitters.remove(sessionId, emitter);
        });
        emitter.onError(e -> {
            log.warn("SSE emitter error for session {}: {}", sessionId, e.getMessage());
            emitters.remove(sessionId, emitter);
        });

        SseEmitter previous = emitters.put(sessionId, emitter);
        if (previous != null) {
            try {
                previous.complete();
            } catch (Exception ignored) {
                // 旧连接可能已失效，忽略
            }
        }
        return emitter;
    }

    /** 序列化并推送一个事件；连接已断开则丢弃。 */
    private void send(String sessionId, HmsEvent event) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event.eventName())
                    .data(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            // 前端断开连接是常态，降级为 debug 并清理
            log.debug("SSE send failed for session {} ({}), dropping emitter: {}",
                    sessionId, event.eventName(), e.getMessage());
            emitters.remove(sessionId, emitter);
        } catch (Exception e) {
            log.warn("Failed to serialize SSE event {} for session {}: {}",
                    event.eventName(), sessionId, e.getMessage());
        }
    }

    /** 构造一个只含 error 事件的已完成连接。 */
    private SseEmitter completedWithError(String message) {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(new HmsEvent.Error(message))));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
