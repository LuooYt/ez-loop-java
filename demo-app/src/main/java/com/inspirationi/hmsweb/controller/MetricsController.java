package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.loop.api.HmsSessionManager;
import com.inspirationi.loop.telemetry.MetricsCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 指标查询 API。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    /** 会话管理器：用于获取会话列表、会话指标与 Token 统计 */
    @Autowired
    private HmsSessionManager sessionManager;

    /**
     * 获取全局概览。
     */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverview() {
        var sessions = sessionManager.listSessions();
        // 累加所有会话的输入/输出 Token 总数
        long totalInputTokens = 0;
        long totalOutputTokens = 0;

        for (var s : sessions) {
            totalInputTokens += s.inputTokens();
            totalOutputTokens += s.outputTokens();
        }

        return ApiResponse.ok(Map.of(
                "activeSessionCount", sessionManager.getActiveSessionCount(),
                "totalSessions", sessions.size(),
                "totalInputTokens", totalInputTokens,
                "totalOutputTokens", totalOutputTokens,
                "totalTokens", totalInputTokens + totalOutputTokens
        ));
    }

    /**
     * 获取会话指标详情。
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<Map<String, Object>> getSessionMetrics(@PathVariable String sessionId) {
        // 获取会话的指标收集器与会话基本信息
        MetricsCollector metrics = sessionManager.getSessionMetrics(sessionId);
        var info = sessionManager.getSessionInfo(sessionId);

        // 用 HashMap 而非 Map.of：summary() 等字段可能为 null，而 Map.of 拒绝 null value
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", sessionId);
        body.put("status", info.status().name());
        body.put("createdAt", info.createdAt().toString());
        body.put("idleSeconds", info.idleSeconds());
        body.put("messageCount", info.messageCount());
        body.put("inputTokens", info.inputTokens());
        body.put("outputTokens", info.outputTokens());
        body.put("totalTokens", info.totalTokens());
        // null 表示该模型定价未知 —— 前端据此显示「定价未知」而非 $0.00
        body.put("cost", info.cost());
        body.put("pricingModel", info.pricingModel());
        body.put("metricsSummary", metrics.summary());
        body.put("metricsMap", metrics.toMap());
        return ApiResponse.ok(body);
    }
}
