package com.inspirationi.hmsweb.service;

import com.inspirationi.loop.api.HmsCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 会话桥接服务 —— 负责 SSE 推送和 HmsCallbacks 异步化。
 * <p>
 * 核心功能：
 * <ul>
 *   <li>管理每个 session 的 {@link SseEmitter}（推送流式事件到前端）</li>
 *   <li>管理 onAskUserAsync / onPermissionRequestAsync 的 {@link CompletableFuture}（等待前端用户回答）</li>
 *   <li>为每个 session 构建 {@link HmsCallbacks} 实例，桥接 HMS Core 事件到 SSE</li>
 * </ul>
 * <p>
 * 利用 HMS Core 内置的异步回调模式（{@code onAskUserAsync} / {@code onPermissionRequestAsync}），
 * 避免线程阻塞问题。
 */
@Service
public class SessionBridgeService {

    private static final Logger log = LoggerFactory.getLogger(SessionBridgeService.class);

    /** SSE 发射器映射：sessionId → SseEmitter */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** 用户提问等待映射：sessionId → CompletableFuture（等待前端用户回答） */
    private final Map<String, CompletableFuture<String>> askUserFutures = new ConcurrentHashMap<>();

    /** 权限确认等待映射：sessionId → CompletableFuture */
    private final Map<String, CompletableFuture<String>> permissionFutures = new ConcurrentHashMap<>();

    private static final long USER_RESPONSE_TIMEOUT_SECONDS = 300; // 5 分钟超时

    // ==================== SSE Emitter 管理 ====================

    /**
     * 注册一个新的 SSE 发射器（前端连接时调用）。
     */
    public SseEmitter createEmitter(String sessionId) {
        SseEmitter old = emitters.remove(sessionId);
        if (old != null) {
            try { old.complete(); } catch (Exception ignored) {}
        }

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 分钟超时
        emitter.onCompletion(() -> {
            log.info("SSE emitter completed for session {}", sessionId);
            emitters.remove(sessionId);
        });
        emitter.onTimeout(() -> {
            log.info("SSE emitter timed out for session {}", sessionId);
            emitters.remove(sessionId);
        });
        emitter.onError(e -> {
            log.warn("SSE emitter error for session {}: {}", sessionId, e.getMessage());
            emitters.remove(sessionId);
        });

        emitters.put(sessionId, emitter);
        log.info("SSE emitter created for session {}", sessionId);
        return emitter;
    }

    /**
     * 移除会话的 SSE 发射器。
     */
    public void removeEmitter(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    /**
     * 发送 SSE 事件。
     */
    private void sendEvent(String sessionId, String eventName, String data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException e) {
            log.warn("Failed to send SSE event {} for session {}: {}", eventName, sessionId, e.getMessage());
            removeEmitter(sessionId);
        }
    }

    // ==================== 用户回答管理 ====================

    /**
     * 注册一个等待用户回答的 Future。
     */
    private CompletableFuture<String> createAskUserFuture(String sessionId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        askUserFutures.put(sessionId, future);
        future.orTimeout(USER_RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> "skip");
        return future;
    }

    /**
     * 注册一个等待权限确认的 Future。
     */
    private CompletableFuture<String> createPermissionFuture(String sessionId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        permissionFutures.put(sessionId, future);
        future.orTimeout(USER_RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> "deny");
        return future;
    }

    /**
     * 前端提交用户回答（用于 onAskUser 回调）。
     */
    public void submitAskUserResponse(String sessionId, String response) {
        CompletableFuture<String> future = askUserFutures.remove(sessionId);
        if (future != null && !future.isDone()) {
            future.complete(response);
            log.info("AskUser response submitted for session {}: {}", sessionId, response);
        }
    }

    /**
     * 前端提交权限确认（用于 onPermissionRequest 回调）。
     */
    public void submitPermissionResponse(String sessionId, String response) {
        CompletableFuture<String> future = permissionFutures.remove(sessionId);
        if (future != null && !future.isDone()) {
            future.complete(response);
            log.info("Permission response submitted for session {}: {}", sessionId, response);
        }
    }

    /**
     * 清理会话的所有等待 Future。
     */
    public void clearFutures(String sessionId) {
        CompletableFuture<String> askFuture = askUserFutures.remove(sessionId);
        if (askFuture != null && !askFuture.isDone()) {
            askFuture.complete("skip");
        }
        CompletableFuture<String> permFuture = permissionFutures.remove(sessionId);
        if (permFuture != null && !permFuture.isDone()) {
            permFuture.complete("deny");
        }
    }

    // ==================== HmsCallbacks 构建 ====================

    /**
     * 为指定会话构建 HmsCallbacks，将所有事件桥接到 SSE。
     * 使用 HMS Core 内置的异步回调模式（onAskUserAsync / onPermissionRequestAsync），
     * 避免同步阻塞。
     */
    public HmsCallbacks buildCallbacks(String sessionId) {
        return new HmsCallbacks() {

            /** 推送增量 token 到前端。 */
            @Override
            public void onToken(String token) {
                sendEvent(sessionId, "token", "{\"token\":" + jsonEscape(token) + "}");
            }

            /** 推送工具调用信息（工具名、入参、结果）到前端，结果超长时截断。 */
            @Override
            public void onToolUse(String toolName, String input, String result) {
                sendEvent(sessionId, "tool_use",
                        "{\"toolName\":" + jsonEscape(toolName)
                                + ",\"input\":" + jsonEscape(input)
                                + ",\"result\":" + jsonEscape(truncate(result, 5000))
                                + "}");
            }

            /** 推送思考过程片段到前端，超长时截断。 */
            @Override
            public void onThinking(String thinking) {
                sendEvent(sessionId, "thinking",
                        "{\"thinking\":" + jsonEscape(truncate(thinking, 2000)) + "}");
            }

            /**
             * 向用户提问：推送 ask_user 事件，并返回等待前端回答的 Future；
             * 前端通过 submitAskUserResponse 完成该 Future。
             */
            @Override
            public CompletableFuture<String> onAskUserAsync(String question, java.util.List<String> options) {
                String optionsJson = options != null && !options.isEmpty()
                        ? "\"options\":" + toJsonArray(options)
                        : "\"options\":[]";
                sendEvent(sessionId, "ask_user",
                        "{\"question\":" + jsonEscape(question) + "," + optionsJson + "}");

                log.info("Waiting for user answer on ask_user for session {}", sessionId);
                return createAskUserFuture(sessionId);
            }

            /**
             * 请求权限确认：推送 permission 事件，并返回等待前端确认的 Future；
             * 前端通过 submitPermissionResponse 完成该 Future。
             */
            @Override
            public CompletableFuture<String> onPermissionRequestAsync(String toolName, String description) {
                sendEvent(sessionId, "permission",
                        "{\"toolName\":" + jsonEscape(toolName)
                                + ",\"description\":" + jsonEscape(description) + "}");

                log.info("Waiting for permission confirmation for session {}, tool: {}", sessionId, toolName);
                return createPermissionFuture(sessionId);
            }

            /** 推送完成事件，包含最终回复内容与 Token 统计。 */
            @Override
            public void onComplete(com.inspirationi.loop.api.HmsResponse response) {
                sendEvent(sessionId, "complete",
                        "{\"content\":" + jsonEscape(response.content())
                                + ",\"totalTokens\":" + response.totalTokens()
                                + ",\"toolCallsCount\":" + response.toolCallsCount()
                                + ",\"interrupted\":" + response.interrupted() + "}");
            }

            /** 推送错误事件，并返回 "abort" 指示中止当前执行。 */
            @Override
            public String onError(Throwable error) {
                String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                sendEvent(sessionId, "error", "{\"message\":" + jsonEscape(msg) + "}");
                return "abort";
            }
        };
    }

    // ==================== 工具方法 ====================

    /** 将字符串转义为 JSON 字符串字面量（处理反斜杠、引号、换行等），用于拼接 SSE 事件数据。 */
    private static String jsonEscape(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    /** 截断超长文本，超过 maxLen 的部分以省略号结尾，避免 SSE 负载过大。 */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...[truncated]";
    }

    /** 将字符串列表序列化为 JSON 数组字符串（元素会进行引号转义）。 */
    private static String toJsonArray(java.util.List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        return sb.append("]").toString();
    }
}
