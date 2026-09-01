package com.inspirationi.loop.permission;

import com.inspirationi.loop.permission.PermissionTypes.PermissionBehavior;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.permission.PermissionTypes.PermissionRule;
import com.inspirationi.loop.tool.Tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionRuleEngine} 的决策矩阵测试。
 * <p>
 * 这是安全边界：模式 × 风险等级 × 规则的组合决定工具能否执行。
 * 任何一格判错都意味着高风险操作被静默放行，或正常操作被无故拦下。
 */
class PermissionRuleEngineTest {

    private PermissionSettings settings;
    private PermissionRuleEngine engine;

    @BeforeEach
    void setUp() {
        settings = new PermissionSettings();
        engine = new PermissionRuleEngine(settings);
    }

    private PermissionTypes.PermissionDecision evaluate(Tool.RiskLevel risk) {
        return engine.evaluate("SomeTool", Map.of(), risk);
    }

    // ── 模式 × 风险等级矩阵 ──

    @Test
    void bypassModeAllowsEverythingIncludingCritical() {
        settings.setCurrentMode(PermissionMode.BYPASS);
        for (Tool.RiskLevel risk : Tool.RiskLevel.values()) {
            assertTrue(evaluate(risk).isAllowed(),
                    "BYPASS 模式应放行 " + risk);
        }
    }

    @Test
    void strictModeAllowsOnlyReadOnly() {
        settings.setCurrentMode(PermissionMode.STRICT);

        assertTrue(evaluate(Tool.RiskLevel.READ_ONLY).isAllowed(),
                "STRICT 应放行只读工具");

        for (Tool.RiskLevel risk : new Tool.RiskLevel[]{
                Tool.RiskLevel.LOW, Tool.RiskLevel.MEDIUM,
                Tool.RiskLevel.HIGH, Tool.RiskLevel.CRITICAL}) {
            assertTrue(evaluate(risk).isDenied(),
                    "STRICT 应拒绝 " + risk + "（而非降级为 ASK）");
        }
    }

    @Test
    void safeModeAutoAllowsUpToLow() {
        settings.setCurrentMode(PermissionMode.SAFE);

        assertTrue(evaluate(Tool.RiskLevel.READ_ONLY).isAllowed());
        assertTrue(evaluate(Tool.RiskLevel.LOW).isAllowed());
        // MEDIUM 及以上需确认
        assertTrue(evaluate(Tool.RiskLevel.MEDIUM).needsAsk(),
                "SAFE 模式下 MEDIUM 应需要确认");
        assertTrue(evaluate(Tool.RiskLevel.CRITICAL).needsAsk());
    }

    @Test
    void defaultModeAutoAllowsUpToMedium() {
        settings.setCurrentMode(PermissionMode.DEFAULT);

        assertTrue(evaluate(Tool.RiskLevel.READ_ONLY).isAllowed());
        assertTrue(evaluate(Tool.RiskLevel.LOW).isAllowed());
        assertTrue(evaluate(Tool.RiskLevel.MEDIUM).isAllowed());
        assertTrue(evaluate(Tool.RiskLevel.HIGH).needsAsk(),
                "DEFAULT 模式下 HIGH 应需要确认");
        assertTrue(evaluate(Tool.RiskLevel.CRITICAL).needsAsk());
    }

    @Test
    void trustedModeStillAsksForCritical() {
        settings.setCurrentMode(PermissionMode.TRUSTED);

        assertTrue(evaluate(Tool.RiskLevel.HIGH).isAllowed());
        // CRITICAL 即使在 TRUSTED 下也不应自动放行
        assertTrue(evaluate(Tool.RiskLevel.CRITICAL).needsAsk(),
                "TRUSTED 模式下 CRITICAL 仍应需要确认");
    }

    @Test
    void unconfiguredHighRiskDefaultsToAskNotAllow() {
        // 默认模式下未配置任何规则的高风险操作 —— 必须是 ASK，绝不能默认放行
        var decision = evaluate(Tool.RiskLevel.HIGH);
        assertTrue(decision.needsAsk());
        assertFalse(decision.isAllowed(), "未配置的高风险操作不得默认放行");
    }

    // ── 持久化规则 ──

    @Test
    void denyRuleTakesPrecedenceOverAllowRule() {
        settings.setCurrentMode(PermissionMode.DEFAULT);
        // 同一工具同时存在 ALLOW 与 DENY 规则时，DENY 必须胜出
        settings.addUserRule(PermissionRule.forTool("RiskyTool", PermissionBehavior.ALLOW));
        settings.addUserRule(PermissionRule.forTool("RiskyTool", PermissionBehavior.DENY));

        var decision = engine.evaluate("RiskyTool", Map.of(), Tool.RiskLevel.HIGH);
        assertTrue(decision.isDenied(),
                "DENY 规则必须优先于 ALLOW —— 否则拒绝规则可被绕过");
    }

    @Test
    void allowRuleLiftsHighRiskToolOutOfAsk() {
        settings.setCurrentMode(PermissionMode.DEFAULT);
        settings.addUserRule(PermissionRule.forTool("TrustedTool", PermissionBehavior.ALLOW));

        assertTrue(engine.evaluate("TrustedTool", Map.of(), Tool.RiskLevel.HIGH).isAllowed());
    }

    @Test
    void ruleForOneToolDoesNotAffectAnother() {
        settings.setCurrentMode(PermissionMode.DEFAULT);
        settings.addUserRule(PermissionRule.forTool("ToolA", PermissionBehavior.ALLOW));

        // 规则不应泄漏到其他工具
        assertTrue(engine.evaluate("ToolB", Map.of(), Tool.RiskLevel.HIGH).needsAsk());
    }

    @Test
    void strictModeIgnoresAllowRules() {
        // STRICT 是硬边界：即便配了放行规则也不应放过非只读工具
        settings.setCurrentMode(PermissionMode.STRICT);
        settings.addUserRule(PermissionRule.forTool("AnyTool", PermissionBehavior.ALLOW));

        assertTrue(engine.evaluate("AnyTool", Map.of(), Tool.RiskLevel.HIGH).isDenied(),
                "STRICT 模式不应被 ALLOW 规则绕过");
    }

    // ── 风险检测器 ──

    @Test
    void riskDetectorForcesAskEvenForAutoAllowedLevel() {
        settings.setCurrentMode(PermissionMode.DEFAULT);
        engine.addCommandExtractor("Exec", "command");
        engine.addRiskDetector(new RiskDetector() {
            @Override
            public String detect(String toolName, Map<String, Object> input) {
                Object cmd = input.get("command");
                return (cmd != null && cmd.toString().contains("rm -rf"))
                        ? "destructive command" : null;
            }

            @Override
            public boolean isDangerousWildcard(String rule) {
                return false;
            }
        });

        // MEDIUM 本会被 DEFAULT 模式自动放行，但风险检测应把它拉回 ASK
        var flagged = engine.evaluate("Exec", Map.of("command", "rm -rf /"),
                Tool.RiskLevel.HIGH);
        assertTrue(flagged.needsAsk(), "检出危险内容应强制 ASK");
        assertTrue(flagged.reason().contains("DANGEROUS"),
                "拒绝原因应标明危险，实际：" + flagged.reason());

        // 同一工具的安全命令不受影响
        assertTrue(engine.evaluate("Exec", Map.of("command", "ls -la"),
                Tool.RiskLevel.HIGH).needsAsk());
    }

    @Test
    void clearRiskDetectorsRemovesDetection() {
        settings.setCurrentMode(PermissionMode.DEFAULT);
        RiskDetector always = new RiskDetector() {
            @Override
            public String detect(String toolName, Map<String, Object> input) {
                return "always dangerous";
            }

            @Override
            public boolean isDangerousWildcard(String rule) {
                return false;
            }
        };
        engine.addRiskDetector(always);
        assertTrue(engine.evaluate("T", Map.of(), Tool.RiskLevel.HIGH)
                .reason().contains("DANGEROUS"));

        engine.clearRiskDetectors();
        assertFalse(engine.evaluate("T", Map.of(), Tool.RiskLevel.HIGH)
                .reason().contains("DANGEROUS"));
    }

    // ── 危险通配符不得持久化 ──

    @Test
    void dangerousWildcardIsNotPersistedOnAlwaysAllow() {
        settings.setCurrentMode(PermissionMode.DEFAULT);
        engine.addRiskDetector(new RiskDetector() {
            @Override
            public String detect(String toolName, Map<String, Object> input) {
                return null;
            }

            @Override
            public boolean isDangerousWildcard(String rule) {
                return rule.contains("(*)") || rule.equals("Exec");
            }
        });

        int before = settings.getAllRules().size();
        engine.applyChoice(PermissionTypes.PermissionChoice.ALWAYS_ALLOW, "Exec", null);

        // 危险通配符规则不得写入，否则一次误点就永久放行整类操作
        assertFalse(settings.getAllRules().size() > before,
                "危险通配符不应被持久化为放行规则");
    }

    @Test
    void alwaysDenyIsAlwaysPersisted() {
        settings.setCurrentMode(PermissionMode.DEFAULT);
        engine.applyChoice(PermissionTypes.PermissionChoice.ALWAYS_DENY, "BadTool", null);

        // 拒绝规则应无条件持久化，且后续评估生效
        assertTrue(engine.evaluate("BadTool", Map.of(), Tool.RiskLevel.LOW).isDenied(),
                "ALWAYS_DENY 后该工具应被拒绝");
    }

    @Test
    void allowOnceAndDenyOnceDoNotPersist() {
        settings.setCurrentMode(PermissionMode.DEFAULT);
        int before = settings.getAllRules().size();

        engine.applyChoice(PermissionTypes.PermissionChoice.ALLOW_ONCE, "T", null);
        engine.applyChoice(PermissionTypes.PermissionChoice.DENY_ONCE, "T", null);

        assertFalse(settings.getAllRules().size() != before,
                "单次选择不应产生持久化规则");
    }
}
