package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.hmsweb.model.PermissionConfigRequest;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.permission.PermissionTypes;
import com.inspirationi.loop.permission.PermissionTypes.PermissionBehavior;
import com.inspirationi.loop.permission.PermissionTypes.PermissionRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 权限管理 API。
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    /** 权限设置：管理权限模式与用户自定义规则 */
    @Autowired
    private PermissionSettings permissionSettings;

    /**
     * 获取当前权限模式和所有规则。
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> getPermissionState() {
        return ApiResponse.ok(Map.of(
                "mode", permissionSettings.getCurrentMode().name(),
                "rules", permissionSettings.getAllRules().stream()
                        .map(r -> Map.of(
                                "toolName", r.toolName(),
                                "commandPattern", r.ruleContent() != null ? r.ruleContent() : "",
                                "behavior", r.behavior().name()
                        ))
                        .toList()
        ));
    }

    /**
     * 切换权限模式。
     */
    @PutMapping("/mode")
    public ApiResponse<String> setMode(@RequestBody PermissionConfigRequest request) {
        try {
            var mode = PermissionTypes.PermissionMode.valueOf(request.mode().toUpperCase());
            permissionSettings.setCurrentMode(mode);
            return ApiResponse.ok("权限模式已切换为: " + mode.name());
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("无效的权限模式: " + request.mode()
                    + "，有效值: STRICT, SAFE, DEFAULT, TRUSTED, BYPASS");
        }
    }

    /**
     * 添加权限规则。
     * <p>
     * {@code description} 为空或 {@code "*"} 时落工具级规则（匹配该工具的所有调用），
     * 否则落命令前缀规则。前端「始终允许 / 始终拒绝」走前者 —— 权限事件只带
     * {@code toolName} 与人类可读的 {@code description}，拿不到可用于匹配的命令前缀。
     */
    @PostMapping("/rules")
    public ApiResponse<String> addRule(@RequestBody PermissionConfigRequest request) {
        if (request.toolName() == null || request.toolName().isBlank()) {
            return ApiResponse.fail("toolName 不能为空");
        }
        try {
            var behavior = PermissionBehavior.valueOf(request.action().toUpperCase());
            String prefix = request.description();
            boolean toolWide = prefix == null || prefix.isBlank() || "*".equals(prefix);

            var rule = toolWide
                    ? PermissionRule.forTool(request.toolName(), behavior)
                    : PermissionRule.forCommand(request.toolName(), prefix, behavior);
            permissionSettings.addUserRule(rule);

            return ApiResponse.ok("规则已添加: " + request.toolName() + "(" + rule.ruleContent() + ")");
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("无效的行为值: " + request.action() + "，有效值: ALLOW, DENY, ASK");
        }
    }

    /**
     * 清除所有规则。
     */
    @DeleteMapping("/rules")
    public ApiResponse<String> clearRules() {
        permissionSettings.clearAll();
        return ApiResponse.ok("所有规则已清除");
    }
}
