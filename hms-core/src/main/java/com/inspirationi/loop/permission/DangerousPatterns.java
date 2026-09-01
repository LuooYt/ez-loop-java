package com.inspirationi.loop.permission;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shell 命令风险检测器 —— 专为 Bash/PowerShell 等命令执行场景设计。
 * <p>
 * 实现 {@link RiskDetector} 接口，提供危险的 Unix/Windows Shell 命令和
 * 代码执行模式的检测。
 * <p>
 * <b>注意：此检测器仅适用于编码助手/运维等注册了 Shell 工具的场景。</b>
 * 以下场景应使用自定义 {@link RiskDetector} 实现：
 * <ul>
 *   <li>纯 Web SDK（数据库操作、消息推送）—— 无需注册此检测器</li>
 *   <li>SQL 场景 —— 使用 SQL 注入检测器</li>
 *   <li>API 网关场景 —— 使用 SSRF/参数注入检测器</li>
 * </ul>
 * <p>
 * 注册方式：{@code permissionRuleEngine.addRiskDetector(new DangerousPatterns());}
 */
public final class DangerousPatterns implements RiskDetector {

    /** 危险 Shell 命令前缀（不区分大小写匹配） */
    private static final List<String> DANGEROUS_BASH_PREFIXES = List.of(
            "rm -rf /",
            "rm -rf ~",
            "rm -rf .",
            "rm -r /",
            "rmdir /s",
            "del /f /s /q",
            "format ",
            "mkfs.",
            "dd if=",
            "> /dev/sda",
            "chmod -R 777 /",
            "chown -R",
            ":(){:|:&};:"       // fork bomb
    );

    /** 危险代码执行模式 */
    private static final List<String> CODE_EXECUTION_PATTERNS = List.of(
            "eval ",
            "exec ",
            "python -c",
            "python3 -c",
            "node -e",
            "ruby -e",
            "perl -e",
            "| sh",
            "| bash",
            "| zsh",
            "| powershell",
            "| pwsh",
            "curl | sh",
            "wget | sh",
            "Invoke-Expression",
            "iex ",
            "Start-Process",
            "Add-Type"
    );

    /** 在规则匹配中应自动拒绝的工具级通配符 */
    private static final Set<String> DANGEROUS_TOOL_WILDCARDS = Set.of(
            "Bash",         // 不应允许所有 bash 命令
            "Bash(*)",
            "PowerShell",
            "PowerShell(*)"
    );

    /**
     * 检测 Shell 命令文本是否包含危险模式。
     */
    public static String detectShellDanger(String command) {
        if (command == null || command.isBlank()) return null;
        // 统一转小写并去除首尾空格，便于大小写不敏感的匹配
        String lower = command.toLowerCase().trim();

        // ① 匹配危险命令前缀（如 rm -rf /、format、mkfs. 等）
        for (String prefix : DANGEROUS_BASH_PREFIXES) {
            if (lower.startsWith(prefix.toLowerCase()) || lower.contains(prefix.toLowerCase())) {
                return "Dangerous command detected: " + prefix.trim();
            }
        }

        // ② 匹配代码执行模式（如 eval、curl | sh、Invoke-Expression 等）
        for (String pattern : CODE_EXECUTION_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return "Code execution pattern detected: " + pattern.trim();
            }
        }

        return null;
    }

    /**
     * 检测是否为危险的工具级通配符规则。
     */
    @Override
    public boolean isDangerousWildcard(String ruleStr) {
        return DANGEROUS_TOOL_WILDCARDS.contains(ruleStr);
    }

    /**
     * 获取危险原因的简短描述。
     */
    public static String getDangerLevel(String command) {
        String reason = detectShellDanger(command);
        if (reason == null) return "LOW";
        if (reason.contains("Dangerous command")) return "HIGH";
        return "MEDIUM";
    }

    // ── RiskDetector 接口实现 ──

    /**
     * 实现 {@link RiskDetector#detect} —— 仅对 Bash/PowerShell 工具执行
     * Shell 命令风险检测，其他工具视为无风险返回 null。
     */
    @Override
    public String detect(String toolName, Map<String, Object> input) {
        // 仅为 Bash/PowerShell 工具检测命令风险
        if (!("Bash".equalsIgnoreCase(toolName) || "PowerShell".equalsIgnoreCase(toolName))) {
            return null;
        }
        if (input == null) return null;
        String command = (String) input.get("command");
        return detectShellDanger(command);
    }
}
