package com.inspirationi.loop.permission;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.tool.Tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless（无 UI 回调）场景下的权限兜底策略。
 * <p>
 * 曾经的实现在兜底回调里<b>重新评估</b>一次权限，而回调拿不到工具对象，只能硬编码
 * 一个风险等级 —— 传的是 {@code MEDIUM}。而 DEFAULT 模式下
 * {@link PermissionTypes#autoAllowUpTo} 恰好也是 {@code MEDIUM}，于是
 * {@code isAutoAllowed(MEDIUM, DEFAULT)} 必然为真：规则引擎第一次用真实风险等级
 * 判定为「需要询问」的 CRITICAL / HIGH 工具，到了兜底回调被当作 MEDIUM 重新评估，
 * 直接自动放行，用户确认被完全跳过。
 * <p>
 * 修复的形状是让 {@link AgentLoop.PermissionRequest} 携带真实风险等级，兜底回调
 * 只依据它决定放行与否，不再重新评估。这些测试锁定该契约。
 */
class HeadlessPermissionFallbackTest {

    /**
     * 复刻 {@code DefaultHmsSessionManager.createSession} 里的 headless 兜底回调。
     * <p>
     * 与产品代码保持同一策略：只放行本就无需确认的低风险操作，其余一律拒绝。
     */
    private static PermissionChoice headlessFallback(AgentLoop.PermissionRequest req) {
        Tool.RiskLevel risk = req.riskLevel();
        if (risk != null && risk.ordinal() <= Tool.RiskLevel.LOW.ordinal()) {
            return PermissionChoice.ALLOW_ONCE;
        }
        return PermissionChoice.DENY_ONCE;
    }

    private static AgentLoop.PermissionRequest request(Tool.RiskLevel risk) {
        return new AgentLoop.PermissionRequest(
                "SomeTool", "{}", Map.of(), "doing something", risk,
                PermissionTypes.PermissionDecision.ask("SomeTool", null));
    }

    @Test
    void criticalToolIsNotAutoAllowedWhenNobodyCanBeAsked() {
        assertEquals(PermissionChoice.DENY_ONCE, headlessFallback(request(Tool.RiskLevel.CRITICAL)),
                "无人可询问时 CRITICAL 工具必须被拒绝，而不是静默放行");
    }

    @Test
    void highRiskToolIsNotAutoAllowedWhenNobodyCanBeAsked() {
        assertEquals(PermissionChoice.DENY_ONCE, headlessFallback(request(Tool.RiskLevel.HIGH)),
                "无人可询问时 HIGH 工具必须被拒绝");
    }

    @Test
    void mediumRiskToolIsAlsoDeniedSinceItAlreadyNeededAsking() {
        // 请求能到达兜底回调，说明规则引擎已判定「需要询问」——
        // 即便等级是 MEDIUM，也不能因为「MEDIUM 通常自动放行」就放它过去。
        assertEquals(PermissionChoice.DENY_ONCE, headlessFallback(request(Tool.RiskLevel.MEDIUM)),
                "已被判定需要询问的 MEDIUM 工具，在无人可问时同样应拒绝");
    }

    @Test
    void lowRiskToolsRemainUsableHeadless() {
        for (Tool.RiskLevel risk : new Tool.RiskLevel[]{
                Tool.RiskLevel.READ_ONLY, Tool.RiskLevel.LOW}) {
            assertEquals(PermissionChoice.ALLOW_ONCE, headlessFallback(request(risk)),
                    risk + " 应在 headless 下保持可用，否则 SDK 无回调时完全不能干活");
        }
    }

    /**
     * 锁定「兜底回调不得重新评估」这一契约的根据：一旦重新评估并硬编码 MEDIUM，
     * DEFAULT 模式下必然自动放行。此测试断言那个组合确实是放行的 —— 说明为什么
     * 不能走重新评估这条路。
     */
    @Test
    void reEvaluatingWithHardcodedMediumWouldAutoAllowInDefaultMode() {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionTypes.PermissionMode.DEFAULT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        assertTrue(engine.evaluate("SomeTool", Map.of(), Tool.RiskLevel.MEDIUM).isAllowed(),
                "MEDIUM 在 DEFAULT 模式下自动放行 —— 这正是硬编码 MEDIUM 重新评估"
                        + "会绕过 CRITICAL 工具用户确认的原因");
        assertTrue(engine.evaluate("SomeTool", Map.of(), Tool.RiskLevel.CRITICAL).needsAsk(),
                "而用真实的 CRITICAL 等级评估，得到的是「需要询问」");
    }

    @Test
    void permissionRequestCarriesRealRiskLevelAndDecision() {
        var decision = PermissionTypes.PermissionDecision.ask("Bash", "rm");
        var req = new AgentLoop.PermissionRequest(
                "Bash", "{\"command\":\"rm -rf /\"}", Map.of("command", "rm -rf /"),
                "Running rm -rf /", Tool.RiskLevel.CRITICAL, decision);

        assertSame(Tool.RiskLevel.CRITICAL, req.riskLevel(),
                "请求必须携带工具声明的真实风险等级");
        assertNotNull(req.decision(), "请求应携带规则引擎的原始判定，供回调展示 ASK 原因");
        assertEquals("rm -rf /", req.parsedArguments().get("command"),
                "请求应携带已解析参数，回调无需再解一遍 JSON");
    }

    @Test
    void parsedArgumentsAreImmutableSnapshot() {
        Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("command", "ls");
        var req = new AgentLoop.PermissionRequest(
                "Bash", "{}", mutable, "listing", Tool.RiskLevel.LOW, null);

        // 请求会跨线程递给 UI 回调，不能让回调看到构造之后的变更
        mutable.put("command", "rm -rf /");
        assertEquals("ls", req.parsedArguments().get("command"),
                "parsedArguments 必须是构造时的不可变快照");
        assertThrows(UnsupportedOperationException.class,
                () -> req.parsedArguments().put("x", "y"),
                "parsedArguments 不应可被回调修改");
    }
}
