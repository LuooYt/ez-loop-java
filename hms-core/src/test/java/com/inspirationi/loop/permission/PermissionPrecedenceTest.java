package com.inspirationi.loop.permission;

import com.inspirationi.loop.permission.PermissionTypes.PermissionBehavior;
import com.inspirationi.loop.permission.PermissionTypes.PermissionDecision;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.permission.PermissionTypes.PermissionRule;
import com.inspirationi.loop.tool.Tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定优先级表的回归测试 —— 与 {@link PermissionRuleEngine} 类注释里的表、
 * 以及 {@code docs/permissions.md} 保持一致。
 * <p>
 * 这张表的每条边都对应一次「本该拦住却被放行」或「本该放行却被拦住」的事故：
 * DENY 被自动放行越过、ASK 规则被收集却从不参与判定。锁定顺序比锁定单点更重要 ——
 * 调整顺序时这里会先红。
 */
class PermissionPrecedenceTest {

    private static PermissionRuleEngine engineWith(PermissionMode mode) {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(mode);
        return new PermissionRuleEngine(settings);
    }

    @Test
    void explicitDenyOutranksAskRule() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.TRUSTED);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        settings.addUserRule(PermissionRule.forTool("Danger", PermissionBehavior.ASK));
        settings.addUserRule(PermissionRule.forTool("Danger", PermissionBehavior.DENY));

        PermissionDecision decision = engine.evaluate("Danger", Map.of(), Tool.RiskLevel.LOW);

        assertTrue(decision.isDenied(), "两条规则冲突时 DENY 胜出：" + decision);
    }

    /** ② ASK 规则必须真的参与判定 —— 它此前被收集进规则表却从不被读取。 */
    @Test
    void explicitAskRuleForcesConfirmationInPermissiveMode() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.TRUSTED);   // HIGH 本会被自动放行
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        settings.addUserRule(PermissionRule.forTool("Payments", PermissionBehavior.ASK));

        PermissionDecision decision = engine.evaluate("Payments", Map.of(), Tool.RiskLevel.HIGH);

        assertTrue(decision.needsAsk(), "显式 ASK 规则应压过风险自动放行：" + decision);
        assertTrue(decision.reason().contains("Payments"),
                "原因里应能看出是哪条规则触发的：" + decision.reason());
    }

    /** 与 DENY 同一理由：BYPASS 是「不再逐次确认」，不是「取消我设过的规则」。 */
    @Test
    void explicitAskRuleSurvivesBypass() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.BYPASS);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        settings.addUserRule(PermissionRule.forTool("Payments", PermissionBehavior.ASK));

        assertTrue(engine.evaluate("Payments", Map.of(), Tool.RiskLevel.CRITICAL).needsAsk(),
                "BYPASS 下显式 ASK 规则仍要求确认");
    }

    /** STRICT 排在 ASK 之前：把 DENY 降级成一次确认是放松约束，不是收紧。 */
    @Test
    void strictModeIsNotDowngradedToAskByARule() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.STRICT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        settings.addUserRule(PermissionRule.forTool("Writer", PermissionBehavior.ASK));

        PermissionDecision decision = engine.evaluate("Writer", Map.of(), Tool.RiskLevel.MEDIUM);

        assertTrue(decision.isDenied(), "STRICT 下非只读仍是 DENY：" + decision);
        assertFalse(decision.needsAsk());
    }

    /** 冲突时按更严格的一方处理：ASK 先于 ALLOW。 */
    @Test
    void askRuleOutranksAllowRule() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.DEFAULT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        settings.addUserRule(PermissionRule.forTool("Mixed", PermissionBehavior.ALLOW));
        settings.addUserRule(PermissionRule.forTool("Mixed", PermissionBehavior.ASK));

        assertTrue(engine.evaluate("Mixed", Map.of(), Tool.RiskLevel.HIGH).needsAsk(),
                "ALLOW 与 ASK 冲突时应要求确认");
    }

    /** ASK 规则只作用于它自己声明的工具，不外溢。 */
    @Test
    void askRuleDoesNotAffectOtherTools() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.TRUSTED);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        settings.addUserRule(PermissionRule.forTool("Payments", PermissionBehavior.ASK));

        assertTrue(engine.evaluate("ReadOnly", Map.of(), Tool.RiskLevel.LOW).isAllowed(),
                "别的工具不应被这条 ASK 规则波及");
    }

    /** BYPASS 仍放行没有规则约束的调用 —— 收窄语义不等于废掉它。 */
    @Test
    void bypassStillAllowsUnruledTools() {
        PermissionRuleEngine engine = engineWith(PermissionMode.BYPASS);
        assertTrue(engine.evaluate("Anything", Map.of(), Tool.RiskLevel.CRITICAL).isAllowed());
    }
}
