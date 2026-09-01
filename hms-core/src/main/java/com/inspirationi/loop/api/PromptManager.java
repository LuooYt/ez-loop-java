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
        return getGlobalPrompt() + "\n\n---\n\n" + sessionPrompt;
    }
}
