package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.agent.MemorySpec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认 Agent 与改造前的<b>逐字节等价性</b> —— 整个多智能体改造不回归的基线。
 * <p>
 * 三级提示词（基座 / 记忆摘要 / 会话）是新能力，但既有的两级拼接（全局 + 会话）必须
 * 一字不差地保留下来：demo-app 的前端展示、以及靠 system prompt 前缀命中的 prompt cache
 * 都建立在它之上。这里的断言全部以<b>完整字符串相等</b>给出，不使用 contains ——
 * 差一个分隔符就是差一个分隔符。
 */
class DefaultAgentRegressionTest {

    /**
     * 默认 Agent 的 system prompt ＝ {@code 全局 + "\n\n---\n\n" + 会话}。
     * <p>
     * 注意默认 Agent 的定义里带了 {@code basePrompt}（＝全局提示词），因此实际走的是
     * 三级拼接路径；也就是说，这条断言同时验证了「三级拼接在摘要为空时退化回两级」。
     */
    @Test
    void defaultAgentSystemPromptMatchesLegacyConcatenation() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();

            String sessionId = defaultAgent.sessionManager().createSession("会话级提示词");

            assertEquals(MultiAgentTestSupport.GLOBAL_PROMPT
                            + PromptManager.SECTION_SEPARATOR + "会话级提示词",
                    systemPromptOf(defaultAgent, sessionId));
        }
    }

    /** 无会话级提示词时同样逐字节一致 —— 这条路径由 PromptManager 的默认实现给出。 */
    @Test
    void defaultAgentWithoutSessionPromptKeepsLegacyShape() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime defaultAgent = support.registerDefaultAgent();

            String sessionId = defaultAgent.sessionManager().createSession();
            String systemPrompt = systemPromptOf(defaultAgent, sessionId);

            String expectedPrefix = MultiAgentTestSupport.GLOBAL_PROMPT + PromptManager.SECTION_SEPARATOR;
            assertTrue(systemPrompt.startsWith(expectedPrefix),
                    "应以全局提示词与标准分隔符开头，实际：" + systemPrompt);
            assertEquals(1, countOccurrences(systemPrompt, PromptManager.SECTION_SEPARATOR),
                    "不应出现重复分隔符：" + systemPrompt);
        }
    }

    /**
     * 未声明 {@code basePrompt} 的 Agent 走旧的一参数路径。
     * <p>
     * 这条路径的存在本身就是兼容性设计：任何没写基座提示词的 Agent（含集成方自己
     * 随手注册的那些）行为与今天完全一致。
     */
    @Test
    void agentWithoutBasePromptFallsBackToSingleParamPath() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime plain = support.register("plain");

            String sessionId = plain.sessionManager().createSession("会话提示词");

            assertEquals(MultiAgentTestSupport.GLOBAL_PROMPT
                            + PromptManager.SECTION_SEPARATOR + "会话提示词",
                    systemPromptOf(plain, sessionId));
        }
    }

    /** 空白的 basePrompt 等同于未声明 —— 不产生一个空段。 */
    @Test
    void blankBasePromptIsTreatedAsAbsent() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime blank = support.register("blank", builder -> builder.basePrompt("   "));

            String sessionId = blank.sessionManager().createSession("会话提示词");

            assertEquals(MultiAgentTestSupport.GLOBAL_PROMPT
                            + PromptManager.SECTION_SEPARATOR + "会话提示词",
                    systemPromptOf(blank, sessionId));
        }
    }

    // ==================== 三级拼接 ====================

    /** 有记忆摘要时按「基座 → 摘要 → 会话」排列，且各段之间恰好一个分隔符。 */
    @Test
    void memoryDigestSitsBetweenBasePromptAndSessionPrompt() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime agent = support.register("memo", builder -> builder
                    .basePrompt("我是专职助理")
                    .memory(MemorySpec.simple()));
            agent.memory().remember("用户偏好简洁回答", "api", List.of("preference"));

            String sessionId = agent.sessionManager().createSession("本次会话提示词");
            String systemPrompt = systemPromptOf(agent, sessionId);

            int base = systemPrompt.indexOf("我是专职助理");
            int digest = systemPrompt.indexOf("用户偏好简洁回答");
            int session = systemPrompt.indexOf("本次会话提示词");

            assertTrue(base >= 0 && digest >= 0 && session >= 0, "三段都应出现：" + systemPrompt);
            assertTrue(base < digest, "基座提示词应在记忆摘要之前");
            assertTrue(digest < session, "记忆摘要应在会话提示词之前");
            assertEquals(2, countOccurrences(systemPrompt, PromptManager.SECTION_SEPARATOR),
                    "三段之间恰好两个分隔符：" + systemPrompt);
        }
    }

    /**
     * 记忆启用但库为空时，摘要段必须<b>整体消失</b>而不是留下一个空段。
     * <p>
     * 空段会让 system prompt 多出一个分隔符 —— 对人是看不出的小事，对 prompt cache
     * 是「同一配置两种前缀」，对断言是「今天通过明天不通过」。
     */
    @Test
    void emptyDigestLeavesNoExtraSeparator() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            AgentRuntime agent = support.register("memo", builder -> builder
                    .basePrompt("我是专职助理")
                    .memory(MemorySpec.simple()));

            String sessionId = agent.sessionManager().createSession("会话提示词");

            assertEquals("我是专职助理" + PromptManager.SECTION_SEPARATOR + "会话提示词",
                    systemPromptOf(agent, sessionId));
        }
    }

    /** 摘要注入关闭时同样不出现该段。 */
    @Test
    void digestDisabledBySpecProducesTwoLevelPrompt() {
        try (MultiAgentTestSupport support = new MultiAgentTestSupport()) {
            // 记忆条目存在，但 digestInPrompt=false
            MemorySpec spec = new MemorySpec(true, true, true, false,
                    100, 20, 500, 1200, MemorySpec.DigestStrategy.SIMPLE, false, 0, false);
            AgentRuntime agent = support.register("memo", builder -> builder
                    .basePrompt("我是专职助理")
                    .memory(spec));
            agent.memory().remember("这段不应出现在提示词里", "api", List.of());

            String sessionId = agent.sessionManager().createSession("会话提示词");

            assertEquals("我是专职助理" + PromptManager.SECTION_SEPARATOR + "会话提示词",
                    systemPromptOf(agent, sessionId));
        }
    }

    // ==================== 辅助 ====================

    /** 取会话历史中的 system 消息内容。 */
    private static String systemPromptOf(AgentRuntime agent, String sessionId) {
        return agent.sessionManager().getSessionMessages(sessionId).stream()
                .filter(m -> "system".equals(m.role()))
                .map(ChatMessage::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "会话历史中应有 system 消息：" + agent.sessionManager()
                                .getSessionMessages(sessionId)));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
