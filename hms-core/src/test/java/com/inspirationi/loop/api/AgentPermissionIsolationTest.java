package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.agent.ToolSelect;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.permission.PermissionTypes;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.impl.EnterPlanModeTool;
import com.inspirationi.loop.tool.impl.ListAgentsTool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多智能体的差异隔离 —— 提示词、工具集、权限三处互不串味。
 * <p>
 * 权限是三者中最危险的：若 Agent A 的会话读写的是全局 {@link PermissionSettings}，
 * 那么 A 里的一次「进入计划模式」会把<b>整个进程</b>拖进只读，而 A 的其余会话与
 * 所有其他 Agent 都会跟着被限制 —— 排查时只会看到「系统突然不能写文件了」。
 * <p>
 * 反向的要求同样硬：默认 Agent <b>必须</b>复用全局 Bean，否则 demo-app 的
 * {@code PermissionController} 改模式将不再对会话生效。
 */
class AgentPermissionIsolationTest {

    /** 场景：计划模式只在发起它的 Agent 内部生效，全局与兄弟 Agent 不受影响。 */
    @Test
    void planModeDoesNotLeakToGlobalSettingsOrSiblingAgents() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime alpha = support.register("alpha");
            AgentRuntime beta = support.register("beta");

            PermissionMode globalBefore = support.globalSettings.getCurrentMode();
            PermissionMode betaBefore = beta.permissionSettings().getCurrentMode();

            // Agent 级上下文正是会话上下文的父级 —— 会话经 childOf 回落到它读权限
            new EnterPlanModeTool().execute(Map.of(), alpha.agentToolContext());

            assertEquals(PermissionMode.STRICT, alpha.permissionSettings().getCurrentMode(),
                    "发起方应进入只读计划模式");
            assertEquals(globalBefore, support.globalSettings.getCurrentMode(),
                    "全局 PermissionSettings Bean 不得被 Agent 的会话改写");
            assertEquals(betaBefore, beta.permissionSettings().getCurrentMode(),
                    "兄弟 Agent 不得被牵连");
        }
    }

    /** 未声明权限设置的 Agent 各拿一份私有实例 —— 这是隔离的前提。 */
    @Test
    void agentsWithoutDeclaredSettingsGetPrivateInstances() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime alpha = support.register("alpha");
            AgentRuntime beta = support.register("beta");

            assertNotSame(alpha.permissionSettings(), beta.permissionSettings());
            assertNotSame(alpha.permissionSettings(), support.globalSettings);
            assertNotSame(alpha.permissionEngine(), beta.permissionEngine());
        }
    }

    /**
     * 默认 Agent 复用全局 Bean —— demo-app 的 {@code PermissionController} 依赖这一点：
     * 用户在前端点「严格模式」，生效的必须是会话实际读到的那个实例。
     */
    @Test
    void defaultAgentSharesGlobalSettingsBean() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();

            assertSame(support.globalSettings, defaultAgent.permissionSettings(),
                    "默认 Agent 必须复用全局 Bean，否则前端改模式不会生效");

            support.globalSettings.setCurrentMode(PermissionMode.STRICT);
            assertEquals(PermissionMode.STRICT, defaultAgent.permissionSettings().getCurrentMode(),
                    "通过全局 Bean 改的模式应立刻表现为默认 Agent 的模式");
        }
    }

    /** 定义里显式声明的初始模式要在装配时落到 settings 上。 */
    @Test
    void declaredPermissionModeIsAppliedAtAssembly() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            PermissionMode globalBefore = support.globalSettings.getCurrentMode();

            AgentRuntime strictAgent = support.register("strict-one",
                    builder -> builder.permissionMode(PermissionMode.STRICT));

            assertEquals(PermissionMode.STRICT, strictAgent.permissionSettings().getCurrentMode());
            assertEquals(globalBefore, support.globalSettings.getCurrentMode(),
                    "声明在 Agent 上的模式不应影响全局");
        }
    }

    /** 工具集同样按 Agent 独立 —— 白名单互不越界，内置工具不受筛选影响。 */
    @Test
    void toolSelectionIsPerAgentAndKeepsBuiltins() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            support.globalTools.register(namedTool("ToolA"));
            support.globalTools.register(namedTool("ToolB"));

            AgentRuntime alpha = support.register("alpha",
                    builder -> builder.toolSelect(ToolSelect.allowlist("ToolA")));
            AgentRuntime beta = support.register("beta",
                    builder -> builder.toolSelect(ToolSelect.allowlist("ToolB")));

            Set<String> alphaTools = alpha.toolRegistry().getToolNames();
            Set<String> betaTools = beta.toolRegistry().getToolNames();

            assertTrue(alphaTools.contains("ToolA"));
            assertFalse(alphaTools.contains("ToolB"), "白名单外的工具不得出现在该 Agent 上");
            assertTrue(betaTools.contains("ToolB"));
            assertFalse(betaTools.contains("ToolA"));

            assertTrue(alphaTools.contains("ListAgents"),
                    "内置的发现工具在 ToolSelect 筛选之后注册，不受白名单影响");
        }
    }

    /** 会话的工具集继承自所属 Agent —— 会话维度不构成第三条隔离边界。 */
    @Test
    void sessionsInheritTheirAgentTools() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            support.globalTools.register(namedTool("ToolA"));
            support.globalTools.register(namedTool("ToolB"));

            AgentRuntime alpha = support.register("alpha",
                    builder -> builder.toolSelect(ToolSelect.allowlist("ToolA")));
            AgentRuntime beta = support.register("beta",
                    builder -> builder.toolSelect(ToolSelect.allowlist("ToolB")));

            String sessionA = alpha.sessionManager().createSession();
            String sessionB = beta.sessionManager().createSession();

            assertTrue(alpha.sessionManager().getSessionToolRegistry(sessionA)
                    .getToolNames().contains("ToolA"));
            assertFalse(alpha.sessionManager().getSessionToolRegistry(sessionA)
                    .getToolNames().contains("ToolB"));
            assertTrue(beta.sessionManager().getSessionToolRegistry(sessionB)
                    .getToolNames().contains("ToolB"));
        }
    }

    /**
     * Agent 级上下文仍是全局上下文的子级 —— 只遮蔽权限等私有键，
     * 全局键（任务管理器、MCP 管理器等）必须仍可读到。
     * <p>
     * 这条若失守，所有依赖全局上下文的工具会在多智能体模式下一起失灵，
     * 且症状是「某个工具突然说功能未配置」，与权限问题毫无表面关联。
     */
    @Test
    void agentContextStillSeesGlobalKeys() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            support.globalContext.set("PROBE_KEY", "probe-value");
            AgentRuntime alpha = support.register("alpha");

            assertEquals("probe-value", alpha.agentToolContext().get("PROBE_KEY"),
                    "Agent 上下文缺失的键应回落到全局上下文");

            assertSame(alpha.permissionSettings(),
                    alpha.agentToolContext().get("PERMISSION_SETTINGS"),
                    "PERMISSION_SETTINGS 被本地遮蔽为 Agent 自己的那份");
            assertNotSame(support.globalSettings,
                    alpha.agentToolContext().get("PERMISSION_SETTINGS"),
                    "遮蔽掉的正是全局 Bean —— 否则 EnterPlanMode 会改到全局");
            assertEquals("alpha", alpha.agentToolContext().get(ListAgentsTool.AGENT_ID_KEY));
            assertSame(support.registry,
                    alpha.agentToolContext().get(ListAgentsTool.AGENT_REGISTRY_KEY));
        }
    }

    /** 权限规则不跨 Agent 继承 —— 规则是「授权」，复制等于悄悄扩大授权范围。 */
    @Test
    void permissionRulesDoNotLeakIntoNewAgents() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            support.globalSettings.addUserRule(PermissionTypes.PermissionRule.forTool(
                    "ToolA", PermissionTypes.PermissionBehavior.ALLOW));

            AgentRuntime alpha = support.register("alpha");

            assertTrue(alpha.permissionSettings().getAllRules().isEmpty(),
                    "针对全局的授权不得被复制到新 Agent 上：" + alpha.permissionSettings().getAllRules());
            assertEquals(1, support.globalSettings.getAllRules().size(),
                    "全局自身的规则应保持不变");
        }
    }

    // ==================== 辅助 ====================

    /** 最小工具桩 —— 只用于验证工具集筛选。 */
    private static Tool namedTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试工具 " + name;
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
            }

            @Override
            public String execute(Map<String, Object> input, ToolContext context) {
                return name;
            }

            @Override
            public String activityDescription(Map<String, Object> input) {
                return name;
            }
        };
    }
}
