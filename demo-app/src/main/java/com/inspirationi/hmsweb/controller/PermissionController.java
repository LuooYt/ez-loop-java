package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.hmsweb.model.PermissionConfigRequest;
import com.inspirationi.loop.permission.DangerousPatterns;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.permission.PermissionTypes;
import com.inspirationi.loop.permission.PermissionTypes.PermissionBehavior;
import com.inspirationi.loop.permission.PermissionTypes.PermissionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 权限管理 API。
 * <p>
 * <b>本控制器没有任何鉴权</b>：能访问本端口的人就能切换权限模式、增删规则。
 * 因此两个最危险的动作额外加了开关 —— 见 {@link #setMode} 与 {@link #addRule}。
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private static final Logger log = LoggerFactory.getLogger(PermissionController.class);

    /** 权限设置：管理权限模式与用户自定义规则 */
    @Autowired
    private PermissionSettings permissionSettings;

    /**
     * 是否允许切到 BYPASS。默认关闭。
     * <p>
     * BYPASS 意味着「工具调用不再询问」，配合无鉴权的 HTTP 接口，
     * 效果就是任意能访问该端口的人可以远程执行任意命令。
     */
    @Value("${hms.demo.allow-bypass:false}")
    private boolean allowBypass;

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
     * <p>
     * {@code BYPASS} 需要 {@code hms.demo.allow-bypass=true}，否则返回 403。
     * 默认关闭的理由：本服务无鉴权，任何能连上端口的人都能把模式切到 BYPASS，
     * 之后模型（或伪装成模型的请求）发起的工具调用就不再有任何确认环节。
     * 拒绝时用 403 而不是 200+success=false：这不是「参数写错了」，
     * 而是这项能力在本部署里被管理员关掉了，HTTP 语义应当如实反映。
     */
    @PutMapping("/mode")
    public ResponseEntity<ApiResponse<String>> setMode(@RequestBody PermissionConfigRequest request) {
        String raw = request.mode();
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("mode 不能为空"));
        }
        try {
            var mode = PermissionTypes.PermissionMode.valueOf(raw.toUpperCase());
            if (mode == PermissionTypes.PermissionMode.BYPASS && !allowBypass) {
                log.warn("Refused BYPASS mode switch: hms.demo.allow-bypass is false");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(
                        "BYPASS 模式已被禁用：该模式会让所有工具调用跳过确认，"
                        + "而本 demo 没有鉴权。如确需演示，请以 hms.demo.allow-bypass=true"
                        + "（或环境变量 HMS_DEMO_ALLOW_BYPASS=true）重启服务。"));
            }
            permissionSettings.setCurrentMode(mode);
            return ResponseEntity.ok(ApiResponse.ok("权限模式已切换为: " + mode.name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("无效的权限模式: " + raw
                    + "，有效值: STRICT, SAFE, DEFAULT, TRUSTED, BYPASS"));
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
        if (request.action() == null || request.action().isBlank()) {
            return ApiResponse.fail("action 不能为空，有效值: ALLOW, DENY, ASK");
        }
        try {
            var behavior = PermissionBehavior.valueOf(request.action().toUpperCase());
            String prefix = request.description();
            boolean toolWide = prefix == null || prefix.isBlank() || "*".equals(prefix);

            // 拒绝为 Shell 类工具落「工具级 / 危险前缀」的 ALLOW 规则。
            // 这类规则一旦写进用户级配置，就是永久的免确认开关：例如
            // Bash(*) 等于此后所有命令都不再询问。DENY 与 ASK 不受影响 ——
            // 它们只会让行为更保守，不存在被滥用提权的风险。
            if (behavior == PermissionBehavior.ALLOW
                    && DangerousPatterns.isDangerousWildcard(request.toolName(), prefix)) {
                log.warn("Refused dangerous ALLOW rule: {}({})", request.toolName(), prefix);
                return ApiResponse.fail("拒绝添加规则：" + request.toolName() + "("
                        + (prefix == null ? "" : prefix) + ") 会让该工具的任意调用免确认。"
                        + "请改用具体命令前缀（如 npm:*），或改添加 DENY 规则。");
            }

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
