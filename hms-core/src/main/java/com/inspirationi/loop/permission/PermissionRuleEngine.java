package com.inspirationi.loop.permission;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.permission.PermissionTypes.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限规则引擎 —— 基于风险等级的通用权限评估。
 * <p>
 * 决策流程：
 * <ol>
 *   <li>检查全局模式 —— BYPASS / STRICT 等直接决定</li>
 *   <li>检查 ToolContext 中的模式覆盖</li>
 *   <li>检查风险等级 —— 根据模式自动放行低于阈值的操作</li>
 *   <li>检查 alwaysDeny 规则 → 匹配则 DENY</li>
 *   <li>检查 alwaysAllow 规则 → 匹配则 ALLOW</li>
 *   <li>调用 RiskDetector 检测风险 → 有风险强制 ASK</li>
 *   <li>默认 → ASK</li>
 * </ol>
 * <p>
 * 与 Claude Code CLI 的 key differences（SDK 化）：
 * <ul>
 *   <li>不再硬编码工具名（Bash、Write、Edit 等），改用可配置的命令提取映射</li>
 *   <li>STRICT 模式通过 {@link Tool.RiskLevel#READ_ONLY} 统一判断，不再特判 PLAN.md</li>
 *   <li>RiskDetector 可扩展，内置 DangerousPatterns 可在 Web 场景中移除</li>
 * </ul>
 */
public class PermissionRuleEngine {

    /** 风险检测器列表（支持多个） */
    private final List<RiskDetector> riskDetectors;

    /** 权限设置 —— 提供当前模式与持久化规则 */
    private final PermissionSettings settings;

    /**
     * 命令提取映射：toolName → 参数中提取"命令内容"的 key 名。
     * <p>
     * 用于规则匹配（如 "Bash(git:*)" 匹配以 git 开头的命令）
     * 和风险检测器（检测危险的命令内容）。
     * 默认为空 —— 纯 Web SDK 场景默认不提取命令。
     * SDK 调用方可按需通过 {@link #setCommandExtractors(Map)} 配置。
     */
    private Map<String, String> commandExtractors = Map.of();

    /**
     * 构造权限规则引擎。
     *
     * @param settings 权限设置（当前模式与规则的来源）
     */
    public PermissionRuleEngine(PermissionSettings settings) {
        this.settings = settings;
        this.riskDetectors = new ArrayList<>();
        // 默认不注册任何风险检测器。SDK 调用方按场景通过 addRiskDetector() 注入：
        //   - Shell 命令场景：new DangerousPatterns()
        //   - 数据库场景：new SqlInjectionDetector()
        //   - 自定义场景：实现 RiskDetector 接口
    }

    /**
     * 注册额外的风险检测器。
     * SDK 集成方可通过此方法注入场景特定的检测逻辑（如 SQL 注入、XSS 等）。
     */
    public void addRiskDetector(RiskDetector detector) {
        if (detector != null) {
            this.riskDetectors.add(detector);
        }
    }

    /**
     * 移除风险检测器。
     */
    public void removeRiskDetector(RiskDetector detector) {
        this.riskDetectors.remove(detector);
    }

    /**
     * 清除所有风险检测器（包括默认的 DangerousPatterns）。
     * 适用于不使用 Shell 工具的纯 Web SDK 场景。
     */
    public void clearRiskDetectors() {
        this.riskDetectors.clear();
    }

    /**
     * 设置命令提取映射。
     * <p>
     * 示例：
     * <pre>{@code
     * // 编码助手场景
     * engine.setCommandExtractors(Map.of(
     *     "Bash", "command",
     *     "PowerShell", "command",
     *     "Write", "file_path",
     *     "Edit", "file_path"
     * ));
     *
     * // 数据库操作 SDK 场景
     * engine.setCommandExtractors(Map.of(
     *     "ExecuteSQL", "sql",
     *     "AdminAPI", "endpoint"
     * ));
     * }</pre>
     *
     * @param extractors toolName → paramKey 映射表，key 为工具名，value 为参数名
     */
    public void setCommandExtractors(Map<String, String> extractors) {
        this.commandExtractors = extractors != null
                ? Map.copyOf(extractors)
                : Map.of();
    }

    /**
     * 批量注册命令提取器（便捷方法，逐个添加）。
     */
    public void addCommandExtractor(String toolName, String paramKey) {
        var mutable = new HashMap<>(commandExtractors);
        mutable.put(toolName, paramKey);
        this.commandExtractors = Map.copyOf(mutable);
    }

    // ── 权限评估入口 ──

    /**
     * 评估工具调用的权限（使用工具的风险等级）。
     */
    public PermissionDecision evaluate(String toolName, Map<String, Object> input,
                                       Tool.RiskLevel riskLevel) {
        return evaluate(toolName, input, riskLevel, null);
    }

    /**
     * 评估工具调用的权限（带 ToolContext）。
     */
    public PermissionDecision evaluate(String toolName, Map<String, Object> input,
                                       Tool.RiskLevel riskLevel, Object toolContext) {
        PermissionMode mode = settings.getCurrentMode();

        // ① BYPASS 模式：全部允许
        if (mode == PermissionMode.BYPASS) {
            return PermissionDecision.allow("Bypass mode enabled");
        }

        // ② STRICT 模式：仅允许 READ_ONLY 风险等级
        if (mode == PermissionMode.STRICT) {
            if (riskLevel == Tool.RiskLevel.READ_ONLY) {
                return PermissionDecision.allow("Read-only tool allowed in strict mode");
            }
            return PermissionDecision.deny(
                    "Strict mode: only READ_ONLY tools are allowed. "
                    + toolName + " has risk level " + riskLevel);
        }

        // ③ 从 toolContext 中读取上下文相关的规则覆盖
        if (toolContext instanceof com.inspirationi.loop.tool.ToolContext ctx) {
            Object modeOverride = ctx.get("PERMISSION_MODE_OVERRIDE");
            if (modeOverride instanceof PermissionMode m) {
                mode = m;
            }
        }

        // ④ 风险等级自动放行
        if (PermissionTypes.isAutoAllowed(riskLevel, mode)) {
            return PermissionDecision.allow("Auto-allowed by risk level: " + riskLevel
                    + " (mode: " + mode + ")");
        }

        // ⑤ 提取命令内容（用于规则匹配和风险检测）
        String command = extractCommand(toolName, input);

        // ⑥ 检查持久化规则
        List<PermissionRule> rules = settings.getAllRules();

        // alwaysDeny
        for (var rule : rules) {
            if (rule.behavior() == PermissionBehavior.DENY && matchesRule(rule, toolName, command)) {
                return PermissionDecision.deny("Denied by rule: " + PermissionSettings.formatRule(rule),
                        toolName, extractCommandPrefix(command));
            }
        }

        // alwaysAllow
        for (var rule : rules) {
            if (rule.behavior() == PermissionBehavior.ALLOW && matchesRule(rule, toolName, command)) {
                return PermissionDecision.allow("Allowed by rule: " + PermissionSettings.formatRule(rule));
            }
        }

        // ⑦ 风险检测器检查（仅当有命令内容可检测时）
        String danger = null;
        for (RiskDetector detector : riskDetectors) {
            String d = detector.detect(toolName, input);
            if (d != null) {
                danger = d;
                break;
            }
        }
        if (danger != null) {
            String prefix = extractCommandPrefix(command);
            return new PermissionDecision(
                    PermissionBehavior.ASK,
                    "⚠ DANGEROUS: " + danger,
                    toolName, prefix, List.of()
            );
        }

        // ⑧ 默认：需要用户确认
        String prefix = extractCommandPrefix(command);
        return PermissionDecision.ask(toolName, prefix);
    }

    /**
     * 根据用户选择应用权限变更。
     */
    public void applyChoice(PermissionChoice choice, String toolName, String command) {
        String prefix = extractCommandPrefix(command);
        switch (choice) {
            case ALWAYS_ALLOW -> {
                var rule = prefix != null
                        ? PermissionRule.forCommand(toolName, prefix, PermissionBehavior.ALLOW)
                        : PermissionRule.forTool(toolName, PermissionBehavior.ALLOW);
                String ruleStr = PermissionSettings.formatRule(rule);
                // 若该规则为危险通配符（如 "Bash(*)"），则不持久化，避免永久放行
                boolean dangerous = false;
                for (RiskDetector detector : riskDetectors) {
                    if (detector.isDangerousWildcard(ruleStr)) {
                        dangerous = true;
                        break;
                    }
                }
                if (!dangerous) {
                    // 仅持久化非危险的放行规则
                    settings.addUserRule(rule);
                }
            }
            case ALWAYS_DENY -> {
                var rule = prefix != null
                        ? PermissionRule.forCommand(toolName, prefix, PermissionBehavior.DENY)
                        : PermissionRule.forTool(toolName, PermissionBehavior.DENY);
                // 拒绝规则直接持久化（不做危险通配符检查）
                settings.addUserRule(rule);
            }
            case ALLOW_ONCE, DENY_ONCE -> {
                // 单次操作，不持久化
            }
        }
    }

    // ── 内部方法 ──

    /** 检查规则是否匹配当前工具和命令 */
    boolean matchesRule(PermissionRule rule, String toolName, String command) {
        if (!rule.toolName().equalsIgnoreCase(toolName)) return false;

        String content = rule.ruleContent();
        if ("*".equals(content)) return true;

        // 前缀匹配：npm:* 匹配以 "npm" 开头的命令
        if (content.endsWith(":*") && command != null) {
            String prefix = content.substring(0, content.length() - 2);
            return command.toLowerCase().startsWith(prefix.toLowerCase());
        }

        // 精确匹配
        return content.equalsIgnoreCase(command);
    }

    /**
     * 从工具参数中提取命令文本（供规则匹配和风险检测使用）。
     * <p>
     * 使用可配置的 {@link #commandExtractors} 映射表，不再硬编码工具名。
     */
    private String extractCommand(String toolName, Map<String, Object> input) {
        if (input == null || commandExtractors.isEmpty()) return null;
        String paramKey = commandExtractors.get(toolName);
        if (paramKey == null) return null;
        Object value = input.get(paramKey);
        return value instanceof String s ? s : null;
    }

    /**
     * 从工具名和参数中提取命令前缀（供外部 {@code applyChoice} 使用）。
     */
    public String extractCommandPrefixForTool(String toolName, Map<String, Object> input) {
        String command = extractCommand(toolName, input);
        return extractCommandPrefix(command);
    }

    /** 提取命令前缀（第一个空格前的部分） */
    private String extractCommandPrefix(String command) {
        if (command == null || command.isBlank()) return null;
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }
}