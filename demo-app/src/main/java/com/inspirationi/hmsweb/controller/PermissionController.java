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
     */
    @PostMapping("/rules")
    public ApiResponse<String> addRule(@RequestBody PermissionConfigRequest request) {
        try {
            var behavior = PermissionBehavior.valueOf(request.action().toUpperCase());
            // 生成规则展示 ID 与规则对象，并添加到用户规则中
            String ruleId = request.toolName() + "(" + (request.description() != null ? request.description() : "*") + ")";
            var rule = PermissionRule.forCommand(request.toolName(), request.description(), behavior);
            permissionSettings.addUserRule(rule);
            return ApiResponse.ok("规则已添加: " + ruleId);
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
