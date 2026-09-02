package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.hmsweb.model.CompactResponse;
import com.inspirationi.hmsweb.model.PromptUpdateRequest;
import com.inspirationi.hmsweb.model.SessionCreateRequest;
import com.inspirationi.loop.api.ChatMessage;
import com.inspirationi.loop.api.HmsSessionManager;
import com.inspirationi.loop.api.PromptManager;
import com.inspirationi.loop.api.SessionInfo;
import com.inspirationi.loop.web.HmsSseBridge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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

    /** 提示词管理器：读取全局/会话提示词（由 hms-core 自动装配） */
    @Autowired
    private PromptManager promptManager;

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
     * 手动触发上下文压缩。
     * <p>
     * 并发保护全部下沉到 hms-core：会话不存在抛 {@code IllegalArgumentException}，
     * 正在执行请求抛 {@code IllegalStateException}，两者由
     * {@link ApiExceptionHandler} 统一转成 400。
     * <p>
     * 注意 {@code compacted=false} 与 HTTP 200 并不矛盾 —— 「历史太短、没什么可压」
     * 是正常结果而非错误。
     */
    @PostMapping("/{sessionId}/compact")
    public ApiResponse<CompactResponse> compactSession(@PathVariable String sessionId) {
        return ApiResponse.ok(CompactResponse.from(sessionManager.compactNow(sessionId)));
    }

    /**
     * 重置该会话自动压缩的熔断器。
     * <p>
     * 摘要通路连续失败 3 次即熔断，而熔断是永久的 —— 一次偶发限流就能让该会话此后
     * 再不自动压缩，上下文一路涨到被上游拒绝。没有这个端点，用户唯一的出路是销毁
     * 会话、丢掉全部上下文重来。
     * <p>
     * {@code wasBroken=false} 同样返回 200：本就没熔断时重置属空操作，不是错误。
     */
    @PostMapping("/{sessionId}/compact/reset-circuit-breaker")
    public ApiResponse<Map<String, Boolean>> resetCompactionCircuitBreaker(
            @PathVariable String sessionId) {
        boolean wasBroken = sessionManager.resetCompactionCircuitBreaker(sessionId);
        return ApiResponse.ok(Map.of("wasBroken", wasBroken));
    }

    /**
     * 获取会话提示词（连同全局提示词一并返回，便于前端展示完整的生效内容）。
     */
    @GetMapping("/{sessionId}/prompt")
    public ApiResponse<Map<String, Object>> getSessionPrompt(@PathVariable String sessionId) {
        // 必须前置检查：PromptManager 对不存在的会话返回 null 而不抛异常，
        // 少了这一步前端会拿到「200 + null」，无从区分「会话没了」和「提示词为空」。
        if (!sessionManager.sessionExists(sessionId)) {
            return ApiResponse.fail("会话不存在: " + sessionId);
        }
        // 用 HashMap 而非 Map.of：会话以默认提示词创建时 getSessionPrompt() 返回
        // null，而 Map.of 拒绝 null value 会直接 500。
        Map<String, Object> body = new HashMap<>();
        body.put("sessionPrompt", promptManager.getSessionPrompt(sessionId));
        body.put("globalPrompt", promptManager.getGlobalPrompt());
        return ApiResponse.ok(body);
    }

    /**
     * 热更新会话提示词：只替换系统提示词，保留既有对话历史。
     * <p>
     * 建议在无请求执行时调用 —— 该操作不参与会话级互斥，与正在进行的对话并发时
     * 会改动其系统提示词。
     */
    @PutMapping("/{sessionId}/prompt")
    public ApiResponse<String> updateSessionPrompt(@PathVariable String sessionId,
                                                  @RequestBody(required = false) PromptUpdateRequest request) {
        if (request == null || request.sessionPrompt() == null || request.sessionPrompt().isBlank()) {
            return ApiResponse.fail("会话提示词不能为空");
        }
        // 会话不存在时由 SDK 抛 IllegalArgumentException，交给 ApiExceptionHandler
        sessionManager.updateSessionPrompt(sessionId, request.sessionPrompt());
        return ApiResponse.ok("会话提示词已更新: " + sessionId);
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
