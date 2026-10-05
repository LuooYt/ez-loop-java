package com.inspirationi.loop.api;

/**
 * 提示词管理器 —— 两级提示词体系：全局 + 会话。
 * <p>
 * 全局提示词在 AppConfig Bean 启动时注册，所有会话共享基础框架。
 * 会话提示词在 createSession 时初始化，绑定 sessionId。
 * <p>
 * AgentLoop 实际使用的 systemPrompt = GlobalPrompt + "\n\n---\n\n" + SessionPrompt。
 */
public interface PromptManager {

    /** 各级提示词之间的分隔符 —— 与 {@link #buildFullPrompt(String)} 保持同一常量。 */
    String SECTION_SEPARATOR = "\n\n---\n\n";

    // ==================== 全局提示词 ====================

    /** 获取当前全局提示词 */
    String getGlobalPrompt();

    /**
     * 更新全局提示词。
     * <p>
     * 已创建的会话不受影响（AgentLoop 中 messageHistory[0] 不变）。
     * 新创建的会话使用新的全局提示词。
     */
    void updateGlobalPrompt(String prompt);

    /** 重置全局提示词为默认值 */
    void resetGlobalPrompt();

    // ==================== 会话提示词 ====================

    /**
     * 获取指定会话的提示词（不含全局前缀）。
     */
    String getSessionPrompt(String sessionId);

    /**
     * 更新指定会话的提示词，实时重建 AgentLoop 消息历史。
     * <p>
     * 该操作保留对话历史中的用户/助手消息，
     * 仅将 messageHistory[0] 替换为 fullPrompt = globalPrompt + "---" + sessionPrompt。
     */
    void updateSessionPrompt(String sessionId, String prompt);

    /**
     * 组装完整的两级提示词：globalPrompt + "---" + sessionPrompt。
     * <p>
     * 默认实现按 {@link #getGlobalPrompt()} 与传入的会话提示词拼接；
     * 需要自定义拼接规则的实现可覆写此方法。
     */
    default String buildFullPrompt(String sessionPrompt) {
        return getGlobalPrompt() + SECTION_SEPARATOR + sessionPrompt;
    }

    /**
     * 组装<b>三级</b>完整提示词：Agent 基座提示词 + 记忆摘要 + 会话提示词。
     * <p>
     * 这是<b>纯新增</b>的 default 方法 —— 一参的 {@link #buildFullPrompt(String)}
     * 原样保留，单 Agent 部署走的仍是那条路径，拼接结果逐字节不变。
     * <p>
     * 三级的含义与顺序：
     * <ol>
     *   <li><b>Agent 基座</b> —— 该智能体的角色与守则（取代单 Agent 时代的全局提示词）</li>
     *   <li><b>记忆摘要</b> —— 该 Agent 跨会话积累的记忆；<b>在会话创建时冻结</b>，
     *       此后同一会话的 system prompt 前缀保持不变，prompt cache 才可能命中。
     *       既有会话要取新记忆应走 recall 工具（追加在消息尾部，不击穿前缀）</li>
     *   <li><b>会话提示词</b> —— 单个会话自己的目标描述</li>
     * </ol>
     * 空段自动省略，且不会留下前后多余的分隔符 —— 否则「无记忆」的 Agent
     * 会得到 {@code "…---\n\n---\n\n…"} 这种把两个分隔符叠在一起的提示词。
     *
     * @param agentPrompt   第 1 级；{@code null}/空白表示无
     * @param memoryDigest  第 2 级；{@code null}/空白表示该 Agent 无记忆
     * @param sessionPrompt 第 3 级；{@code null}/空白表示无
     * @return 拼接后的完整系统提示词
     */
    default String buildFullPrompt(String agentPrompt, String memoryDigest, String sessionPrompt) {
        StringBuilder sb = new StringBuilder();
        if (agentPrompt != null && !agentPrompt.isBlank()) {
            sb.append(agentPrompt);
        }
        for (String section : new String[]{memoryDigest, sessionPrompt}) {
            if (section == null || section.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(SECTION_SEPARATOR);
            }
            sb.append(section);
        }
        return sb.toString();
    }
}
