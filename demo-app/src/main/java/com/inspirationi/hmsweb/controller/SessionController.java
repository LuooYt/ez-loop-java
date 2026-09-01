package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.hmsweb.model.SessionCreateRequest;
import com.inspirationi.loop.api.ChatMessage;
import com.inspirationi.loop.api.HmsSessionManager;
import com.inspirationi.loop.api.SessionInfo;
import com.inspirationi.loop.web.HmsSseBridge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话管理 API。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    /** 会话管理器：负责会话的增删查改与生命周期控制 */
    @Autowired
    private HmsSessionManager sessionManager;

    /** SSE 桥接门面：销毁/取消会话时用于释放 SSE 连接与等待中的请求 */
    @Autowired
    private HmsSseBridge sseBridge;

    /**
     * 创建新会话。
     */
    @PostMapping
    public ApiResponse<Map<String, String>> createSession(@RequestBody(required = false) SessionCreateRequest request) {
        // 带会话提示词则创建带提示词的会话，否则创建默认会话
        String sessionId;
        if (request != null && request.sessionPrompt() != null && !request.sessionPrompt().isBlank()) {
            sessionId = sessionManager.createSession(request.sessionPrompt());
        } else {
            sessionId = sessionManager.createSession();
        }
        return ApiResponse.ok(Map.of("sessionId", sessionId));
    }

    /**
     * 列出所有会话。
     */
    @GetMapping
    public ApiResponse<List<SessionInfo>> listSessions() {
        return ApiResponse.ok(sessionManager.listSessions());
    }

    /**
     * 获取单个会话信息。
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionInfo> getSession(@PathVariable String sessionId) {
        if (!sessionManager.sessionExists(sessionId)) {
            return ApiResponse.fail("会话不存在: " + sessionId);
        }
        return ApiResponse.ok(sessionManager.getSessionInfo(sessionId));
    }

    /**
     * 销毁会话。
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<String> destroySession(@PathVariable String sessionId) {
        sseBridge.release(sessionId);
        sessionManager.destroySession(sessionId);
        return ApiResponse.ok("会话已销毁: " + sessionId);
    }

    /**
     * 暂停会话。
     */
    @PostMapping("/{sessionId}/pause")
    public ApiResponse<String> pauseSession(@PathVariable String sessionId) {
        sessionManager.pauseSession(sessionId);
        return ApiResponse.ok("会话已暂停: " + sessionId);
    }

    /**
     * 恢复会话。
     */
    @PostMapping("/{sessionId}/resume")
    public ApiResponse<String> resumeSession(@PathVariable String sessionId) {
        sessionManager.resumeSession(sessionId);
        return ApiResponse.ok("会话已恢复: " + sessionId);
    }

    /**
     * 取消当前执行。
     */
    @PostMapping("/{sessionId}/cancel")
    public ApiResponse<String> cancelExecution(@PathVariable String sessionId) {
        sessionManager.cancel(sessionId);
        // 只释放等待中的请求，保留 SSE 连接以继续接收后续事件
        sseBridge.cancelPending(sessionId);
        return ApiResponse.ok("已取消当前执行: " + sessionId);
    }

    /**
     * 清理空闲会话。
     */
    @PostMapping("/cleanup")
    public ApiResponse<Map<String, Integer>> cleanupSessions(@RequestParam(defaultValue = "1800") long idleSeconds) {
        int cleaned = sessionManager.cleanupIdleSessions(idleSeconds);
        return ApiResponse.ok(Map.of("cleaned", cleaned));
    }

    /**
     * 获取 Token 统计。
     */
    @GetMapping("/{sessionId}/tokens")
    public ApiResponse<Map<String, Object>> getTokenStats(@PathVariable String sessionId) {
        var stats = sessionManager.getSessionTokenStats(sessionId);
        return ApiResponse.ok(Map.of(
                "inputTokens", stats.inputTokens(),
                "outputTokens", stats.outputTokens(),
                "totalTokens", stats.totalTokens()
        ));
    }

    /**
     * 获取会话历史消息（回显历史对话）。
     */
    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<ChatMessage>> getSessionMessages(@PathVariable String sessionId) {
        if (!sessionManager.sessionExists(sessionId)) {
            return ApiResponse.fail("会话不存在: " + sessionId);
        }
        return ApiResponse.ok(sessionManager.getSessionMessages(sessionId));
    }
}
