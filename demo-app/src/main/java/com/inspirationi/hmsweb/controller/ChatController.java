package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.hmsweb.model.ChatRequest;
import com.inspirationi.hmsweb.model.UserResponseRequest;
import com.inspirationi.hmsweb.service.SessionBridgeService;
import com.inspirationi.loop.api.HmsCallbacks;
import com.inspirationi.loop.api.HmsResponse;
import com.inspirationi.loop.api.HmsSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话 API — 支持同步和 SSE 流式两种模式。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 会话管理器：负责会话的创建、发送消息等核心操作 */
    @Autowired
    private HmsSessionManager sessionManager;

    /** 会话桥接服务：负责 SSE 事件推送与前端回答的异步等待 */
    @Autowired
    private SessionBridgeService bridgeService;

    /** 流式对话使用的虚拟线程执行器，避免阻塞容器线程 */
    private final ExecutorService chatExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 同步对话（非流式，等待完整回复）。
     */
    @PostMapping("/{sessionId}")
    public ApiResponse<Map<String, Object>> chatSync(
            @PathVariable String sessionId,
            @RequestBody ChatRequest request) {
        if (!sessionManager.sessionExists(sessionId)) {
            return ApiResponse.fail("会话不存在: " + sessionId);
        }

        HmsResponse response = sessionManager.send(sessionId, request.message());
        return ApiResponse.ok(Map.of(
                "content", response.content(),
                "totalTokens", response.totalTokens(),
                "toolCallsCount", response.toolCallsCount(),
                "interrupted", response.interrupted()
        ));
    }

    /**
     * SSE 流式对话（主交互方式）。
     * <p>
     * 客户端通过 EventSource 连接此端点，通过 query param 或 request body 发送消息。
     * 这里采用 query parameter 方式简化前端 EventSource 调用。
     */
    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @PathVariable String sessionId,
            @RequestParam("message") String message) {

        if (!sessionManager.sessionExists(sessionId)) {
            SseEmitter errorEmitter = new SseEmitter();
            try {
                errorEmitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"会话不存在: " + sessionId + "\"}"));
                errorEmitter.complete();
            } catch (Exception ignored) {}
            return errorEmitter;
        }

        // 创建 SSE 发射器并构建回调，把 HMS Core 事件桥接到前端
        SseEmitter emitter = bridgeService.createEmitter(sessionId);
        HmsCallbacks callbacks = bridgeService.buildCallbacks(sessionId);

        // 在虚拟线程中异步发送消息，避免阻塞请求线程
        chatExecutor.execute(() -> {
            try {
                sessionManager.send(sessionId, message, callbacks);
            } catch (Exception e) {
                log.error("Chat error for session {}: {}", sessionId, e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"message\":" + escapeJson(e.getMessage()) + "}"));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 前端提交权限确认回答。
     */
    @PostMapping("/{sessionId}/permission-response")
    public ApiResponse<String> submitPermissionResponse(
            @PathVariable String sessionId,
            @RequestBody UserResponseRequest request) {
        bridgeService.submitPermissionResponse(sessionId, request.response());
        return ApiResponse.ok("已提交权限确认");
    }

    /**
     * 前端提交用户提问回答。
     */
    @PostMapping("/{sessionId}/ask-response")
    public ApiResponse<String> submitAskResponse(
            @PathVariable String sessionId,
            @RequestBody UserResponseRequest request) {
        bridgeService.submitAskUserResponse(sessionId, request.response());
        return ApiResponse.ok("已提交回答");
    }

    /**
     * 将普通字符串转义为 JSON 字符串字面量（处理反斜杠、引号、换行等），用于拼接 SSE 错误消息。
     */
    private static String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
