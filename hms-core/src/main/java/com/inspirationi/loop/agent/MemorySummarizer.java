package com.inspirationi.loop.agent;

import java.util.List;

/**
 * 记忆清洗器 —— 把一批旧记忆合并成一段精炼内容。
 * <p>
 * 以函数式接口而非直接依赖 {@code ChatModel}：{@code agent} 包刻意保持对 Spring/
 * Spring AI 的零依赖（纯内存领域层），真正的模型调用由 {@code AgentRuntimeFactory}
 * 用闭包注入 —— 与 {@link ToolSelect} 的取舍一致。
 */
@FunctionalInterface
public interface MemorySummarizer {

    /**
     * 把一批记忆条目合并为一条更精炼的记忆。
     *
     * @param batch 待合并的条目（按时间升序）
     * @return 合并后的正文；返回 {@code null} 或空白表示本次清洗失败（调用方保留原条目）
     */
    String summarize(List<AgentMemoryEntry> batch);
}
