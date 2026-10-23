package com.inspirationi.loop.permission;

import com.inspirationi.loop.permission.PermissionTypes.PermissionBehavior;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.permission.PermissionTypes.PermissionRule;
import com.inspirationi.loop.tool.Tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DENY 规则的优先级 —— 它必须压过一切自动放行，包括 BYPASS。
 * <p>
 * 两处曾经装反：
 * <ul>
 *   <li>「风险等级自动放行」早于 DENY 检查，低风险工具上的显式 DENY 被跳过 ——
 *       用户点了「始终拒绝」却依然放行；</li>
 *   <li>BYPASS 最先返回，用户亲手加的 DENY 在旁路模式下形同虚设 ——
 *       而 README 写的是 DENY 优先。</li>
 * </ul>
 * 这些测试锁定正确顺序：DENY 是用户的显式意图，BYPASS 只是「不再逐次确认」。
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
    void bypassModeDoesNotOverrideDenyRules() {
        // BYPASS 跳过的是「逐次确认」，不是用户设过的拒绝规则
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.BYPASS);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        settings.addUserRule(PermissionRule.forTool("AnyTool", PermissionBehavior.DENY));

        assertTrue(engine.evaluate("AnyTool", Map.of(), Tool.RiskLevel.HIGH).isDenied(),
                "显式 DENY 必须继续生效，BYPASS 不是「取消我设过的拒绝」的开关");
    }

    @Test
    void bypassModeStillAllowsToolsWithoutDenyRule() {
        // 收窄 BYPASS 的语义时不能顺手把它废掉：没有 DENY 规则的调用照旧直接放行
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.BYPASS);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        assertTrue(engine.evaluate("AnyTool", Map.of(), Tool.RiskLevel.CRITICAL).isAllowed());
    }

    @Test
    void strictModeStillRespectsDenyRule() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.STRICT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        settings.addUserRule(PermissionRule.forTool("ReadTool", PermissionBehavior.DENY));

        // STRICT 本会放行只读工具，但显式 DENY 仍应胜出
        assertTrue(engine.evaluate("ReadTool", Map.of(), Tool.RiskLevel.READ_ONLY).isDenied());
    }

    @Test
    void contextModeOverrideDoesNotBypassDeny() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.DEFAULT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        settings.addUserRule(PermissionRule.forTool("AnyTool", PermissionBehavior.DENY));

        var ctx = com.inspirationi.loop.tool.ToolContext.defaultContext();
        ctx.set("PERMISSION_MODE_OVERRIDE", PermissionMode.BYPASS);

        assertTrue(engine.evaluate("AnyTool", Map.of(), Tool.RiskLevel.HIGH, ctx).isDenied(),
                "上下文把模式覆盖成 BYPASS 也不能绕过显式 DENY");
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
