package com.inspirationi.loop.permission;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.permission.PermissionTypes.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限规则引擎 —— 基于风险等级的通用权限评估。
 *
 * <h2>判定优先级（自上而下，先命中先返回）</h2>
 * <table>
 *   <caption>与 {@code evaluate} 内编号一一对应；改顺序必须同时改这张表与
 *   {@code docs/permissions.md}</caption>
 *   <tr><th>#</th><th>规则</th><th>结果</th></tr>
 *   <tr><td>1</td><td>ToolContext 的 {@code PERMISSION_MODE_OVERRIDE}</td><td>替换当前模式</td></tr>
 *   <tr><td>2</td><td>匹配到显式 <b>DENY</b> 规则</td><td>DENY</td></tr>
 *   <tr><td>3</td><td>{@code STRICT} 模式</td><td>READ_ONLY → ALLOW，其余 DENY</td></tr>
 *   <tr><td>4</td><td>匹配到显式 <b>ASK</b> 规则</td><td>ASK</td></tr>
 *   <tr><td>5</td><td>{@code BYPASS} 模式</td><td>ALLOW</td></tr>
 *   <tr><td>6</td><td>风险等级在模式的自动放行阈值内</td><td>ALLOW</td></tr>
 *   <tr><td>7</td><td>匹配到显式 <b>ALLOW</b> 规则</td><td>ALLOW</td></tr>
 *   <tr><td>8</td><td>RiskDetector 检出风险</td><td>ASK（附风险说明）</td></tr>
 *   <tr><td>9</td><td>以上都不命中</td><td>ASK</td></tr>
 * </table>
 * <p>
 * <b>显式规则（DENY / ASK）压过模式。</b>用户亲手设过的规则是明确意图，模式只是
 * 「未说明时如何自动判断」的默认值：BYPASS 的语义是「不再逐次确认」，而不是
 * 「取消我设过的拒绝」；同理，TRUSTED 下一条 ASK 规则仍然要弹确认。反过来，
 * STRICT 排在 ASK 之前 —— 该模式的含义是「非只读一律不行」，把 DENY 改成 ASK
 * 会放松它，而不是收紧。
 * <p>
 * 与 Claude Code CLI 的 key differences（SDK 化）：
 * <ul>
 *   <li>不再硬编码工具名（Bash、Write、Edit 等），改用可配置的命令提取映射</li>
 *   <li>STRICT 模式通过 {@link Tool.RiskLevel#READ_ONLY} 统一判断，不再特判 PLAN.md</li>
 *   <li>RiskDetector 可扩展，内置 DangerousPatterns 可在 Web 场景中移除</li>
 * </ul>
 */
public class PermissionRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(PermissionRuleEngine.class);

    /**
     * 「参数就是一条任意命令」的工具名。它们的工具级放行等于放行一切，
     * 因为命令文本由模型自由决定。
     */
    private static final Set<String> SHELL_TOOL_NAMES = Set.of(
            "bash", "powershell", "pwsh", "shell", "sh", "zsh", "cmd", "terminal",
            "exec", "command", "process", "runcommand");

    /**
     * 敏感命令词 —— 一次「始终允许」不应换来跨会话的长期放行。
     * <p>
     * 挑选标准是「影响面远超这一次」：删改文件、改权限、提权、执行任意脚本、
     * 联网下载、切分支。它们仍然可以在本会话内被记住（见 {@link AllowRuleScope})，
     * 只是不写进用户级规则。
     */
    private static final Set<String> SENSITIVE_ALLOW_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "dd", "mkfs", "chmod", "chown", "chgrp",
            "sudo", "su", "sh", "bash", "zsh", "powershell", "pwsh", "cmd",
            "curl", "wget", "git", "python", "python3", "node", "perl", "ruby",
            "eval", "exec", "kill", "service", "systemctl", "mount", "umount",
            "iptables", "nc", "ncat", "telnet", "ssh", "scp", "find", "xargs");

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

    /**
     * 从另一个引擎继承<b>策略配置</b> —— 风险检测器与命令提取映射。
     * <p>
     * 多智能体装配用：每个 Agent 有自己的引擎（各自的规则与模式），但「怎么识别危险
     * 命令」「从哪个参数取命令文本」属于<b>部署级策略</b>，应由全局引擎统一定义。
     * 不继承的话，Agent 私有引擎会是一个没有命令提取器、也没有危险模式检测器的空壳 ——
     * {@code DangerousPatterns} 这类检测被静默绕过，而权限模式看起来仍「生效」。
     * <p>
     * <b>不继承</b>规则与当前模式：那正是各 Agent 需要独立的部分。
     * <p>
     * 应在构造后、投入使用前调用一次（本方法非线程安全，与其他配置方法一致）。
     *
     * @param source 全局引擎；为 {@code null} 或自身时为空操作
     */
    public void inheritPolicyFrom(PermissionRuleEngine source) {
        if (source == null || source == this) {
            return;
        }
        this.riskDetectors.clear();
        this.riskDetectors.addAll(source.riskDetectors);
        this.commandExtractors = source.commandExtractors;
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

        // ① 先取上下文覆盖 —— 必须早于模式分派，否则「BYPASS 全局 + STRICT 局部」
        //    这类组合里覆盖值根本不参与决策。
        if (toolContext instanceof com.inspirationi.loop.tool.ToolContext ctx) {
            Object modeOverride = ctx.get("PERMISSION_MODE_OVERRIDE");
            if (modeOverride instanceof PermissionMode m) {
                mode = m;
            }
        }

        // 命令内容供规则匹配与风险检测共用（不计入下面的编号）。
        String command = extractCommand(toolName, input);
        List<PermissionRule> rules = settings.getAllRules();

        // ② alwaysDeny。
        // 显式拒绝是用户的明确意图（如点过「始终拒绝」），它必须压过一切自动放行：
        //   - 压过风险等级自动放行（否则低风险工具上的 DENY 永不生效）；
        //   - 压过 BYPASS（BYPASS 是「不再逐次确认」的逃生阀，不是「取消我设过的拒绝」
        //     的开关）。此前 BYPASS 排在最前，用户亲手加的 DENY 规则在旁路模式下
        //     形同虚设 —— README 写的是 DENY 优先，代码做的是相反的事。
        PermissionRule denyRule = firstMatch(rules, PermissionBehavior.DENY, toolName, command);
        if (denyRule != null) {
            return PermissionDecision.deny("Denied by rule: " + PermissionSettings.formatRule(denyRule),
                    toolName, extractCommandPrefix(command));
        }

        // ③ STRICT 模式：仅允许 READ_ONLY 风险等级。
        //    排在 ASK 规则之前：该模式的含义是「非只读一律不行」，把 DENY 降级成
        //    一次确认是放松约束，而不是收紧。
        if (mode == PermissionMode.STRICT) {
            if (riskLevel == Tool.RiskLevel.READ_ONLY) {
                return PermissionDecision.allow("Read-only tool allowed in strict mode");
            }
            return PermissionDecision.deny(
                    "Strict mode: only READ_ONLY tools are allowed. "
                    + toolName + " has risk level " + riskLevel);
        }

        // ④ 显式 ASK 规则 —— 与 DENY 同理：规则是用户的明确意图，宽松模式（BYPASS）
        //    与风险自动放行都不该悄悄把它越过去。此前 ASK 规则被收集进规则表却从不
        //    参与判定，等于一个声明了却无效的行为类型。
        PermissionRule askRule = firstMatch(rules, PermissionBehavior.ASK, toolName, command);
        if (askRule != null) {
            return new PermissionDecision(PermissionBehavior.ASK,
                    "Requires confirmation by rule: " + PermissionSettings.formatRule(askRule),
                    toolName, extractCommandPrefix(command), List.of());
        }

        // ⑤ BYPASS 模式：跳过确认（但已在上面被显式 DENY / ASK 规则拦过）
        if (mode == PermissionMode.BYPASS) {
            return PermissionDecision.allow("Bypass mode enabled");
        }

        // ⑥ 风险等级自动放行
        if (PermissionTypes.isAutoAllowed(riskLevel, mode)) {
            return PermissionDecision.allow("Auto-allowed by risk level: " + riskLevel
                    + " (mode: " + mode + ")");
        }

        // ⑦ alwaysAllow
        PermissionRule allowRule = firstMatch(rules, PermissionBehavior.ALLOW, toolName, command);
        if (allowRule != null) {
            return PermissionDecision.allow("Allowed by rule: " + PermissionSettings.formatRule(allowRule));
        }

        // ⑧ 风险检测器检查（仅当有命令内容可检测时）
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

        // ⑨ 默认：需要用户确认
        String prefix = extractCommandPrefix(command);
        return PermissionDecision.ask(toolName, prefix);
    }

    /**
     * 返回第一条行为匹配且内容匹配的规则。
     * <p>
     * DENY / ASK / ALLOW 三条分支共用它，匹配逻辑只存在于 {@link #matchesRule} 一处 ——
     * 三处各写一个循环时，最容易出现「DENY 用词边界匹配、ALLOW 还留着裸 startsWith」
     * 这类不对称，而它恰好是绕过规则的最短路径。
     */
    private PermissionRule firstMatch(List<PermissionRule> rules, PermissionBehavior behavior,
                                      String toolName, String command) {
        for (PermissionRule rule : rules) {
            if (rule.behavior() == behavior && matchesRule(rule, toolName, command)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 根据用户选择应用权限变更。
     * <p>
     * {@code command} 应为<b>完整命令文本</b>（见 {@link #extractCommandForTool}），
     * 不再接收截断后的前缀。
     * <p>
     * <b>「始终允许」不等于「永远放行这一类命令」</b>：规则按完整命令生成，
     * 再按影响面决定存放层级 ——
     * <ul>
     *   <li>安全命令 → 用户级规则（跨会话保留）；</li>
     *   <li>敏感命令（rm/chmod/sudo/git/curl…）→ 仅本会话的规则，重启即失效；</li>
     *   <li>Shell 类工具的工具级放行（{@code Bash(*)}）→ 不记录任何规则，只放行这一次。</li>
     * </ul>
     * 此前一律截成前缀并持久化：一次「始终允许」{@code rm -rf /tmp/x}，用户得到的
     * 是 {@code Bash(rm:*)} —— 之后任何删除命令都无需确认。
     */
    public void applyChoice(PermissionChoice choice, String toolName, String command) {
        switch (choice) {
            case ALWAYS_ALLOW -> {
                PermissionRule rule = hasCommand(command)
                        ? PermissionRule.forExactCommand(toolName, command, PermissionBehavior.ALLOW)
                        : PermissionRule.forTool(toolName, PermissionBehavior.ALLOW);
                switch (allowRuleScope(rule, command)) {
                    case PERSIST -> settings.addUserRule(rule);
                    case SESSION_ONLY -> {
                        settings.addSessionRule(rule);
                        log.info("ALWAYS_ALLOW for '{}' downgraded to session-only: "
                                + "'{}' is sensitive enough that persisting it would outlive "
                                + "this session", toolName, command);
                    }
                    case DROP -> log.warn("ALWAYS_ALLOW for '{}' not recorded as a rule: "
                            + "a tool-wide allow on a shell tool would approve every command "
                            + "(only this call is allowed)", toolName);
                }
            }
            case ALWAYS_DENY -> {
                // 拒绝规则不需要收窄：DENY 越窄只会少拦，不会越权，所以直接落精确命令
                PermissionRule rule = hasCommand(command)
                        ? PermissionRule.forExactCommand(toolName, command, PermissionBehavior.DENY)
                        : PermissionRule.forTool(toolName, PermissionBehavior.DENY);
                settings.addUserRule(rule);
            }
            case ALLOW_ONCE, DENY_ONCE -> {
                // 单次操作，不持久化
            }
        }
    }

    /** 「始终允许」规则的存放级别 */
    private enum AllowRuleScope {
        /** 写入用户级规则，跨会话保留 */
        PERSIST,
        /** 只写入会话级规则，重启即失效 */
        SESSION_ONLY,
        /** 不记录规则，仅本次放行 */
        DROP
    }

    /**
     * 判断一条 ALLOW 规则可以放到哪一层。
     */
    private AllowRuleScope allowRuleScope(PermissionRule rule, String command) {
        boolean toolWide = "*".equals(rule.ruleContent());
        String formatted = PermissionSettings.formatRule(rule);

        // 风险检测器认定的危险通配符
        for (RiskDetector detector : riskDetectors) {
            if (detector.isDangerousWildcard(formatted)) {
                return toolWide ? AllowRuleScope.DROP : AllowRuleScope.SESSION_ONLY;
            }
        }
        // Shell 类工具的工具级放行 = 放行它收到的每一条命令
        if (toolWide && isShellTool(rule.toolName())) {
            return AllowRuleScope.DROP;
        }
        // 敏感命令：本次会话内记住，但不跨会话延续
        if (hasCommand(command) && isSensitiveCommand(command)) {
            return AllowRuleScope.SESSION_ONLY;
        }
        return AllowRuleScope.PERSIST;
    }

    private static boolean hasCommand(String command) {
        return command != null && !command.isBlank();
    }

    /** 工具名是否属于「参数就是一条命令」的 Shell 类工具。 */
    private static boolean isShellTool(String toolName) {
        return toolName != null && SHELL_TOOL_NAMES.contains(toolName.toLowerCase());
    }

    /**
     * 命令的首个词是否属于「一次确认不该换来长期放行」的敏感命令。
     * <p>
     * 取的是可执行文件名（去掉目录前缀与 .exe 后缀），这样 {@code /usr/bin/rm}
     * 与 {@code C:\Windows\System32\cmd.exe} 同样能被识别。
     */
    static boolean isSensitiveCommand(String command) {
        if (command == null) return false;
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return false;
        String firstToken = trimmed.split("\\s+", 2)[0];
        int slash = Math.max(firstToken.lastIndexOf('/'), firstToken.lastIndexOf('\\'));
        String word = slash >= 0 ? firstToken.substring(slash + 1) : firstToken;
        if (word.toLowerCase().endsWith(".exe")) {
            word = word.substring(0, word.length() - 4);
        }
        return SENSITIVE_ALLOW_COMMANDS.contains(word.toLowerCase());
    }

    // ── 内部方法 ──

    /**
     * 检查规则是否匹配当前工具和命令。
     * <p>
     * 前缀通配符（{@code npm:*}）必须落在<b>词边界</b>上：只承认「命令恰好等于前缀」
     * 或「前缀后紧跟空白」两种情形。裸 {@code startsWith} 会让 {@code Bash(ls:*)} 命中
     * {@code lsfoo}，更危险的是 {@code Bash(git:*)} 命中 {@code git-reset-hard.sh} ——
     * 一条被信任的规则就这样放行了完全不同族的命令。
     * <p>
     * <b>刻意不把 {@code ; & | > < $ ( )} 当作边界</b>：把 {@code git;rm -rf /} 判给
     * {@code git:*} 正是绕过规则的最短路径。
     */
    boolean matchesRule(PermissionRule rule, String toolName, String command) {
        if (!rule.toolName().equalsIgnoreCase(toolName)) return false;

        String content = rule.ruleContent();
        if ("*".equals(content)) return true;
        if (command == null) return false;

        String normalized = command.trim();
        if (content.endsWith(":*")) {
            String prefix = content.substring(0, content.length() - 2).trim();
            if (prefix.isEmpty()) return true;
            if (normalized.length() < prefix.length()) return false;
            if (!normalized.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
            // 词边界：整条命令就是前缀本身，或前缀之后是空白
            return normalized.length() == prefix.length()
                    || Character.isWhitespace(normalized.charAt(prefix.length()));
        }

        // 精确匹配：不加通配符的规则只放行这一条命令
        return content.trim().equalsIgnoreCase(normalized);
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
     * 从工具名和参数中提取<b>完整命令文本</b>。
     * <p>
     * {@link #applyChoice} 需要完整命令来生成精确规则；传前缀会把规则泛化成
     * 整个命令族。
     */
    public String extractCommandForTool(String toolName, Map<String, Object> input) {
        return extractCommand(toolName, input);
    }

    /**
     * 从工具名和参数中提取命令前缀（用于展示与审计，不用于生成规则）。
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