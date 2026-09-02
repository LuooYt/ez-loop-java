package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.loop.api.ToolManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工具管理 API。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    /** 工具管理器：提供全局与会话级别的工具名查询及会话工具增删 */
    @Autowired
    private ToolManager toolManager;

    /**
     * 获取全局工具列表。
     */
    @GetMapping
    public ApiResponse<List<String>> getGlobalTools() {
        return ApiResponse.ok(toolManager.getGlobalToolNames());
    }

    /**
     * 获取会话工具列表。
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<List<String>> getSessionTools(@PathVariable String sessionId) {
        return ApiResponse.ok(toolManager.getSessionToolNames(sessionId));
    }

    /**
     * 为会话添加工具（按名称从全局注册中心查找并添加到该会话）。
     * <p>
     * 会话或工具名不存在时由 {@link ApiExceptionHandler} 统一转成失败响应。
     */
    @PostMapping("/{sessionId}/add/{toolName}")
    public ApiResponse<String> addTool(
            @PathVariable String sessionId,
            @PathVariable String toolName) {
        toolManager.addSessionToolByName(sessionId, toolName);
        return ApiResponse.ok("工具已添加到会话: " + toolName);
    }

    /**
     * 从会话移除工具。
     */
    @PostMapping("/{sessionId}/remove/{toolName}")
    public ApiResponse<String> removeTool(
            @PathVariable String sessionId,
            @PathVariable String toolName) {
        toolManager.removeSessionTool(sessionId, toolName);
        return ApiResponse.ok("工具已从会话移除: " + toolName);
    }
}
