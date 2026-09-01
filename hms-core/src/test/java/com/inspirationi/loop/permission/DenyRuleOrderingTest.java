package com.inspirationi.loop.permission;

import com.inspirationi.loop.permission.PermissionTypes.PermissionBehavior;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.permission.PermissionTypes.PermissionRule;
import com.inspirationi.loop.tool.Tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DENY 规则与风险等级自动放行的优先级。
 * <p>
 * {@link PermissionRuleEngine#evaluate} 的步骤 ④「风险等级自动放行」早于步骤 ⑥
 * 「持久化规则检查」，因此低风险工具上的显式 DENY 规则会被跳过 —— 用户点了
 * 「始终拒绝」却依然放行。这些测试锁定修复后的正确顺序。
 */
class DenyRuleOrderingTest {

    @Test
    void explicitDenyRuleBeatsRiskLevelAutoAllow() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.DEFAULT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        settings.addUserRule(PermissionRule.forTool("LowRiskTool", PermissionBehavior.DENY));

        // LOW 在 DEFAULT 模式下本会被自动放行，但显式 DENY 必须胜出
        assertTrue(engine.evaluate("LowRiskTool", Map.of(), Tool.RiskLevel.LOW).isDenied(),
                "显式 DENY 规则必须优先于风险等级自动放行");
    }

    @Test
    void denyRuleAppliesAcrossAllAutoAllowedLevels() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.TRUSTED);   // 自动放行到 HIGH
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        settings.addUserRule(PermissionRule.forTool("Banned", PermissionBehavior.DENY));

        for (Tool.RiskLevel risk : new Tool.RiskLevel[]{
                Tool.RiskLevel.READ_ONLY, Tool.RiskLevel.LOW,
                Tool.RiskLevel.MEDIUM, Tool.RiskLevel.HIGH}) {
            assertTrue(engine.evaluate("Banned", Map.of(), risk).isDenied(),
                    "DENY 规则应在 " + risk + " 上同样生效（该等级本会被自动放行）");
        }
    }

    @Test
    void alwaysDenyChoiceTakesEffectImmediately() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.DEFAULT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        // 用户选「始终拒绝」后，同一工具的后续调用必须被拒
        engine.applyChoice(PermissionTypes.PermissionChoice.ALWAYS_DENY, "BadTool", null);

        assertTrue(engine.evaluate("BadTool", Map.of(), Tool.RiskLevel.LOW).isDenied(),
                "ALWAYS_DENY 后低风险调用也应被拒绝");
        assertTrue(engine.evaluate("BadTool", Map.of(), Tool.RiskLevel.READ_ONLY).isDenied(),
                "ALWAYS_DENY 后只读调用也应被拒绝");
    }

    @Test
    void bypassModeStillOverridesDenyRules() {
        // BYPASS 是显式的「全部放行」逃生阀，语义上应压过规则
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.BYPASS);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        settings.addUserRule(PermissionRule.forTool("AnyTool", PermissionBehavior.DENY));

        assertTrue(engine.evaluate("AnyTool", Map.of(), Tool.RiskLevel.HIGH).isAllowed(),
                "BYPASS 模式应压过 DENY 规则（显式逃生阀）");
    }

    @Test
    void allowRuleDoesNotNeedToPrecedeAutoAllow() {
        // ALLOW 规则与自动放行结果一致，顺序无所谓 —— 记录此不变量以防过度收紧
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.DEFAULT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        settings.addUserRule(PermissionRule.forTool("OkTool", PermissionBehavior.ALLOW));

        assertTrue(engine.evaluate("OkTool", Map.of(), Tool.RiskLevel.LOW).isAllowed());
        assertTrue(engine.evaluate("OkTool", Map.of(), Tool.RiskLevel.HIGH).isAllowed());
    }
}
