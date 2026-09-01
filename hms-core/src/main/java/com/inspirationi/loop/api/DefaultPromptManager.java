package com.inspirationi.loop.api;

import com.inspirationi.loop.i18n.PromptI18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PromptManager} 的默认实现。
 */
public class DefaultPromptManager implements PromptManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultPromptManager.class);

    /** 默认全局提示词（两级提示词中的全局级，描述 AI 助手的核心原则、安全边界、工作流程与沟通风格）。 */
    public static final String DEFAULT_GLOBAL_PROMPT = """
            你是 HMS Core AI 助手，一个通用的人工智能（AI）Agent，具备调用工具完成复杂任务的能力。

            ## 核心原则
            - 准确、简洁、有条理地回答用户问题
            - 信息不足时，明确告知用户，并建议下一步行动
            - 优先通过合适的工具完成当前任务，而非仅凭记忆作答
            - 主动管理多步骤任务：拆解、规划、逐步执行、验证结果

            ## 安全边界
            - 不执行破坏性或高风险操作，除非获得用户明确授权
            - 保护用户隐私与敏感信息，不泄露内部凭据
            - 所有输出与行为必须合法、合规

            ## 工作流程
            1. 理解并澄清用户意图
            2. 规划任务步骤，按需调用合适的工具
            3. 执行并验证结果
            4. 清晰、完整地向用户报告，必要时给出后续建议

            ## 沟通风格
            - 使用与用户一致的语言作答（默认简体中文）
            - 需要用户决策时，使用 AskUserQuestion 主动询问
            - 报告结果时包含关键信息（路径、ID、结论），避免冗长无关内容
            """;

    /** 会话管理器，用于读取/更新会话级提示词（支持延迟注入以打破 Bean 循环依赖）。 */
    private volatile HmsSessionManager sessionManager;
    /** 当前全局提示词（volatile，支持运行时更新）。 */
    private volatile String globalPrompt;

    /**
     * 构造提示词管理器。
     *
     * @param sessionManager      会话管理器
     * @param initialGlobalPrompt 初始全局提示词（为 null 时使用默认提示词）
     */
    public DefaultPromptManager(HmsSessionManager sessionManager, String initialGlobalPrompt) {
        this.sessionManager = sessionManager;
        this.globalPrompt = initialGlobalPrompt != null ? initialGlobalPrompt : DEFAULT_GLOBAL_PROMPT;
        log.info("PromptManager initialized (global prompt: {} chars)", this.globalPrompt.length());
    }

    /**
     * 延迟注入会话管理器（打破 PromptManager ↔ HmsSessionManager 的构造循环依赖）。
     * Bean 装配完成后调用，之后的会话级提示词读写即可正常工作。
     */
    public void setSessionManager(HmsSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        log.info("SessionManager injected into PromptManager");
    }

    // ==================== 全局 ====================

    /** 获取当前全局提示词。 */
    @Override
    public String getGlobalPrompt() {
        return globalPrompt;
    }

    /** 更新全局提示词（新创建的会话生效，已存在的会话不受影响）。 */
    @Override
    public void updateGlobalPrompt(String prompt) {
        this.globalPrompt = prompt;
        log.info("Global prompt updated ({} chars)", prompt.length());
    }

    /** 重置全局提示词为当前语言下的默认值。 */
    @Override
    public void resetGlobalPrompt() {
        // 重置时使用当前语言下的默认提示词（中文系统 → 中文；非中文系统 → 翻译后的版本）
        this.globalPrompt = PromptI18n.t(PromptI18n.KEY_GLOBAL_PROMPT, DEFAULT_GLOBAL_PROMPT);
        log.info("Global prompt reset to default (language: {})", PromptI18n.getTargetLanguage());
    }

    // ==================== 会话 ====================

    /** 获取指定会话的会话级提示词（不含全局前缀，会话不存在时返回 null）。 */
    @Override
    public String getSessionPrompt(String sessionId) {
        if (sessionManager == null) {
            log.warn("getSessionPrompt called before sessionManager injection");
            return null;
        }
        var info = sessionManager.getSessionInfo(sessionId);
        return info != null ? info.sessionPrompt() : null;
    }

    /** 更新指定会话的会话级提示词，并实时重建对应 AgentLoop 的 systemPrompt。 */
    @Override
    public void updateSessionPrompt(String sessionId, String newSessionPrompt) {
        if (sessionManager == null) {
            throw new IllegalStateException("sessionManager not injected before updateSessionPrompt");
        }
        // 委托给会话管理器：在实现类内部完成对 LoopSession 的写回与 AgentLoop 系统提示词刷新
        sessionManager.updateSessionPrompt(sessionId, newSessionPrompt);
    }

    /**
     * 组装完整的两级提示词。
     */
    @Override
    public String buildFullPrompt(String sessionPrompt) {
        return globalPrompt + "\n\n---\n\n" + sessionPrompt;
    }

    /** 获取内置的默认全局提示词（供内部使用）。 */
    String getDefaultGlobalPrompt() {
        return DEFAULT_GLOBAL_PROMPT;
    }
}
