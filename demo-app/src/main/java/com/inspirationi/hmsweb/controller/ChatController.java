package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.hmsweb.model.ChatRequest;
import com.inspirationi.hmsweb.model.UserResponseRequest;
import com.inspirationi.loop.api.HmsResponse;
import com.inspirationi.loop.api.HmsSessionManager;
import com.inspirationi.loop.web.HmsSseBridge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 对话 API — 支持同步和 SSE 流式两种模式。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** 会话管理器：负责会话的创建、发送消息等核心操作 */
    @Autowired
    private HmsSessionManager sessionManager;

    /** SSE 桥接门面（hms-core 提供）：负责事件推送、线程调度与用户回答的等待 */
    @Autowired
    private HmsSseBridge sseBridge;

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
        return sseBridge.stream(sessionId, message);
    }

    /**
     * 前端提交权限确认回答。
     * <p>
     * 提交为「尽力交付」语义：无待确认请求（如已超时）时同样返回成功，
     * 交付结果仅记入 hms-core 的 debug 日志，前端无需处理这种竞态。
     */
    @PostMapping("/{sessionId}/permission-response")
    public ApiResponse<String> submitPermissionResponse(
            @PathVariable String sessionId,
            @RequestBody UserResponseRequest request) {
        sseBridge.submitPermissionResponse(sessionId, request.response());
        return ApiResponse.ok("已提交权限确认");
    }

    /**
     * 前端提交用户提问回答。
     * <p>
     * 与 {@link #submitPermissionResponse} 同为「尽力交付」语义。
     */
    @PostMapping("/{sessionId}/ask-response")
    public ApiResponse<String> submitAskResponse(
            @PathVariable String sessionId,
            @RequestBody UserResponseRequest request) {
        sseBridge.submitAskResponse(sessionId, request.response());
        return ApiResponse.ok("已提交回答");
    }
}
