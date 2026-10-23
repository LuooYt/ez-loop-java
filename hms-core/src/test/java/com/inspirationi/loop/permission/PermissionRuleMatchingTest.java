package com.inspirationi.loop.permission;

import com.inspirationi.loop.permission.PermissionTypes.PermissionBehavior;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.permission.PermissionTypes.PermissionRule;
import com.inspirationi.loop.tool.Tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限规则匹配与「始终允许」持久化的边界。
 * <p>
 * 这里守的是越权防线：一次「始终允许」只应放行用户当时看到的那一条命令，
 * 且不应把匹配范围扩大到同名的其他可执行文件。
 */
class PermissionRuleMatchingTest {

    private PermissionSettings settings;
    private PermissionRuleEngine engine;

    @BeforeEach
    void setUp() {
        settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.DEFAULT);
        engine = new PermissionRuleEngine(settings);
        engine.addCommandExtractor("Bash", "command");
    }

    private PermissionTypes.PermissionDecision bash(String command) {
        // HIGH 风险：绕过 DEFAULT 模式的自动放行，让规则/ASK 分支真正参与决策
        return engine.evaluate("Bash", Map.of("command", command), Tool.RiskLevel.HIGH);
    }

    private List<String> rules() {
        return settings.listRules();
    }

    // ── 前缀匹配的词边界 ──

    @Test
    void prefixMatchesOnWordBoundary() {
        settings.addUserRule(PermissionRule.forCommand("Bash", "git", PermissionBehavior.ALLOW));

        assertTrue(bash("git status").isAllowed(), "git status 应命中 Bash(git:*)");
        assertTrue(bash("git").isAllowed(), "命令恰好等于前缀也应命中");
    }

    @Test
    void prefixDoesNotMatchLongerWord() {
        settings.addUserRule(PermissionRule.forCommand("Bash", "git", PermissionBehavior.ALLOW));

        assertFalse(bash("gitleaks detect").isAllowed(),
                "Bash(git:*) 不得匹配 gitleaks —— 裸 startsWith 会放行完全不同族的命令");
    }

    @Test
    void prefixDoesNotMatchAcrossShellSeparator() {
        settings.addUserRule(PermissionRule.forCommand("Bash", "git", PermissionBehavior.ALLOW));

        assertFalse(bash("git;rm -rf /").isAllowed(),
                "分号不是词边界：把 git;rm -rf / 判给 git:* 就是绕过规则的最短路径");
        assertFalse(bash("git|sh").isAllowed(), "管道同理");
    }

    @Test
    void exactCommandRuleMatchesOnlyItself() {
        settings.addUserRule(PermissionRule.forExactCommand(
                "Bash", "npm install", PermissionBehavior.ALLOW));

        assertTrue(bash("npm install").isAllowed());
        assertFalse(bash("npm install --save").isAllowed(),
                "精确规则不应退化成前缀规则");
        assertFalse(bash("npm uninstall").isAllowed());
    }

    // ── 「始终允许」的存放层级 ──

    @Test
    void alwaysAllowOnSensitiveCommandIsSessionOnly() {
        engine.applyChoice(PermissionChoice.ALWAYS_ALLOW, "Bash", "rm -rf /tmp/x");

        List<String> rules = rules();
        assertTrue(rules.stream().anyMatch(r -> r.startsWith("[sess]") && r.contains("rm -rf /tmp/x")),
                "敏感命令应在会话内生效，实际规则：" + rules);
        assertTrue(rules.stream().noneMatch(r -> r.startsWith("[user]")),
                "敏感命令不得写入用户级规则（会跨会话延续），实际规则：" + rules);

        // 规则本身是精确命令，只对同一条命令生效
        assertTrue(bash("rm -rf /tmp/x").isAllowed(), "已确认过的这条命令不应反复追问");
        assertFalse(bash("rm -rf /").isAllowed(),
                "同族但不同的命令必须重新确认 —— 这正是 Bash(rm:*) 泛化的越权点");
    }

    @Test
    void alwaysAllowOnSafeCommandIsPersisted() {
        engine.applyChoice(PermissionChoice.ALWAYS_ALLOW, "Bash", "npm install");

        List<String> rules = rules();
        assertTrue(rules.stream().anyMatch(r -> r.startsWith("[user]") && r.contains("npm install")),
                "安全命令应写入用户级规则，实际规则：" + rules);
        assertTrue(bash("npm install").isAllowed());
    }

    @Test
    void alwaysAllowOnShellToolWithoutCommandIsDropped() {
        engine.applyChoice(PermissionChoice.ALWAYS_ALLOW, "Bash", null);

        assertTrue(rules().isEmpty(),
                "Bash(*) 等于放行任意命令，只能放行这一次，不得记录任何规则，实际：" + rules());
    }

    @Test
    void alwaysAllowOnNonShellToolLevelIsPersisted() {
        engine.applyChoice(PermissionChoice.ALWAYS_ALLOW, "Read", null);

        assertTrue(rules().stream().anyMatch(r -> r.startsWith("[user]") && r.contains("Read")),
                "非 Shell 工具的工具级放行仍然安全，实际规则：" + rules());
    }

    @Test
    void sensitiveCommandDetectionStripsPathAndSuffix() {
        assertTrue(PermissionRuleEngine.isSensitiveCommand("/usr/bin/rm -rf /tmp"));
        assertTrue(PermissionRuleEngine.isSensitiveCommand("C:\\Windows\\System32\\cmd.exe /c del"));
        assertFalse(PermissionRuleEngine.isSensitiveCommand("npm install"));
        assertFalse(PermissionRuleEngine.isSensitiveCommand(""));
    }

    @Test
    void dangerousWildcardRecognisesWholeFamilyOfShellRules() {
        DangerousPatterns patterns = new DangerousPatterns();

        assertTrue(patterns.isDangerousWildcard("Bash"), "工具级放行");
        assertTrue(patterns.isDangerousWildcard("Bash(*)"), "工具级通配");
        assertTrue(patterns.isDangerousWildcard("Shell(**)"));
        assertTrue(patterns.isDangerousWildcard("Bash(rm:*)"), "危险前缀通配");
        assertTrue(patterns.isDangerousWildcard("PowerShell(sudo:*)"));
        assertFalse(patterns.isDangerousWildcard("Bash(npm:*)"), "受限的安全前缀");
        assertFalse(patterns.isDangerousWildcard("Read(*)"), "非 Shell 工具");
        assertEquals(false, DangerousPatterns.isDangerousWildcard("Bash", "ls"));
    }
}
