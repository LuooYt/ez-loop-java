package com.inspirationi.loop.agent;

/**
 * 智能体记忆的配置规格 —— 开关、容量与清洗策略。
 * <p>
 * 默认 {@link #disabled()}：记忆是**可选的**能力，不配置就不产生任何行为变化
 * （默认 Agent 走的正是这条路，从而与改造前逐字节等价）。
 *
 * @param enabled            是否启用记忆（关闭时不建 memory、不注入 digest、不注册记忆工具）
 * @param recallTool         是否注册 {@code RecallAgentMemory} 读工具
 * @param writeTool          是否注册 {@code RememberAgentMemory} 写工具。
 *                           <b>安全提示</b>：写工具让模型能往 system prompt 注入内容，
 *                           属 prompt-injection 面。默认开启是为了满足「自主积累记忆」，
 *                           企业部署可置 false 收紧。
 * @param digestInPrompt     是否把记忆摘要拼进 system prompt
 * @param maxEntries         记忆条目数上限，超出丢最旧
 * @param digestMaxEntries   digest 里最多渲染多少条（取最新的）
 * @param maxEntryChars      单条记忆正文长度上限（超出截断）
 * @param digestMaxChars     digest 总长度上限（超出丢最旧的行）
 * @param strategy           digest 生成策略（当前 SIMPLE 为唯一实现，AI 精炼由清洗承担）
 * @param cleanup            是否启用周期性 AI 清洗
 * @param cleanupThreshold   条目数越过该阈值时触发清洗
 * @param autoRefreshDigest  记忆变更时是否自动刷新既有会话的 system prompt。
 *                           <b>默认关闭</b>：刷新会改动 messageHistory[0]（最前缀），
 *                           使整个 prompt cache 失效，成本明确，须显式开启。
 */
public record MemorySpec(
        boolean enabled,
        boolean recallTool,
        boolean writeTool,
        boolean digestInPrompt,
        int maxEntries,
        int digestMaxEntries,
        int maxEntryChars,
        int digestMaxChars,
        DigestStrategy strategy,
        boolean cleanup,
        int cleanupThreshold,
        boolean autoRefreshDigest
) {

    /** digest 的生成策略。 */
    public enum DigestStrategy {
        /** 纯内存渲染：取最近若干条，零 token。当前唯一实现。 */
        SIMPLE,
        /** 预留：用模型把记忆压成一段连贯摘要（尚未实现，当前回落 SIMPLE）。 */
        AI
    }

    /** 关闭记忆 —— 默认 Agent 与「纯工具型」Agent 使用。 */
    public static MemorySpec disabled() {
        return new MemorySpec(false, false, false, false,
                0, 0, 0, 0, DigestStrategy.SIMPLE, false, 0, false);
    }

    /** 开启记忆：读写工具均注册、digest 注入、写入期去重；AI 清洗与自动刷新默认关闭。 */
    public static MemorySpec simple() {
        return new MemorySpec(true, true, true, true,
                200, 20, 500, 1200, DigestStrategy.SIMPLE, false, 50, false);
    }

    /** 在 {@link #simple()} 基础上开启周期性 AI 清洗。 */
    public static MemorySpec withCleanup() {
        return new MemorySpec(true, true, true, true,
                200, 20, 500, 1200, DigestStrategy.SIMPLE, true, 50, false);
    }

    /** 只读记忆：只注册 recall 工具，模型不能写（收紧部署）。 */
    public static MemorySpec readOnly() {
        return new MemorySpec(true, true, false, true,
                200, 20, 500, 1200, DigestStrategy.SIMPLE, false, 50, false);
    }
}
