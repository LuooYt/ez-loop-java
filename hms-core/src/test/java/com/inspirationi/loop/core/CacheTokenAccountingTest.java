package com.inspirationi.loop.core;

import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt caching 的 token 必须被记账。
 * <p>
 * Spring AI 2.0.1 的 {@link org.springframework.ai.chat.metadata.Usage} 提供
 * {@code getCacheReadInputTokens()} / {@code getCacheWriteInputTokens()}，
 * {@link TokenTracker} 也备好了四参 {@code recordUsage} 与两个累计器 ——
 * 缺的只是 {@link AgentLoop} 里把二者接上。
 * <p>
 * 这不是可选的精度问题：缓存读取的单价约为输入的 1/10（Sonnet 为
 * $0.30 vs $3.00 每百万 token）。把缓存命中的部分按全价计入 input，
 * {@code estimateCost()} 会系统性高估 —— 在高缓存命中率的长会话里可差数倍。
 */
class CacheTokenAccountingTest {

    /** 报告一次带 prompt caching 的用量：10 个新输入、5 个输出、1000 缓存读、200 缓存写。 */
    private static ChatModel modelReportingCacheUsage() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                DefaultUsage usage = new DefaultUsage(
                        10,      // promptTokens（未命中缓存的新输入）
                        5,       // completionTokens
                        15,      // totalTokens
                        null,    // nativeUsage
                        1000L,   // cacheReadInputTokens
                        200L);   // cacheWriteInputTokens
                ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                        .usage(usage)
                        .build();
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok"),
                                ChatGenerationMetadata.NULL)),
                        metadata);
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    /** 前提确认：Spring AI 的 Usage 确实承载缓存字段，数据是可得的。 */
    @Test
    void springAiUsageCarriesCacheTokens() {
        DefaultUsage usage = new DefaultUsage(10, 5, 15, null, 1000L, 200L);
        assertEquals(1000L, usage.getCacheReadInputTokens(),
                "前提：Usage 应能报告缓存读取量");
        assertEquals(200L, usage.getCacheWriteInputTokens(),
                "前提：Usage 应能报告缓存写入量");
    }

    /** 对照：TokenTracker 自身的四参 recordUsage 工作正常，累计器可用。 */
    @Test
    void trackerRecordsCacheTokensWhenTold() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage(10, 5, 1000, 200);
        assertEquals(1000, tracker.getCacheReadTokens(), "四参 recordUsage 应记录缓存读取");
        assertEquals(200, tracker.getCacheCreationTokens(), "四参 recordUsage 应记录缓存写入");
    }

    /**
     * <b>缺陷验证</b>：模型报告了缓存用量，但跑完一轮 AgentLoop 后
     * TokenTracker 的缓存累计器仍是 0。
     * <p>
     * {@code AgentLoop} 只从 usage 里取 promptTokens / completionTokens 两项，
     * 调两参 {@code recordUsage}，缓存字段被整个丢弃。
     */
    @Test
    void agentLoopForwardsCacheTokensToTracker() {
        TokenTracker tracker = new TokenTracker();
        AgentLoop loop = new AgentLoop(modelReportingCacheUsage(), new ToolRegistry(),
                ToolContext.defaultContext(), "sys", tracker);

        loop.run("hi");

        // 先确认基础记账确实发生了 —— 否则下面的 0 可能只是「压根没跑」
        assertEquals(10, tracker.getInputTokens(), "前提：基础 input 应已记账");
        assertEquals(5, tracker.getOutputTokens(), "前提：基础 output 应已记账");

        assertEquals(1000, tracker.getCacheReadTokens(),
                "模型报告了 1000 缓存读取 token，AgentLoop 必须转交给 TokenTracker。"
                        + "丢弃它会让 estimateCost() 把缓存命中按全价计入 input —— "
                        + "缓存读取实际单价约为输入的 1/10，长会话里可高估数倍");
        assertEquals(200, tracker.getCacheCreationTokens(),
                "模型报告的 200 缓存写入 token 同样必须转交");
    }

    /**
     * 缺陷的直接后果：费用估算偏高。
     * <p>
     * 同一次调用，缓存记账与不记账两种情形下 {@code estimateCost()} 的差距 ——
     * 用以说明这不是记账洁癖，而是会体现在账单口径上的偏差。
     */
    @Test
    void droppingCacheTokensOverstatesCost() {
        // 正确记账：10 全价 input + 5 output + 1000 折价 cacheRead
        TokenTracker correct = new TokenTracker();
        correct.setModel("claude-sonnet-4-20250514");
        correct.recordUsage(10, 5, 1000, 200);

        // 若把缓存读取当作普通 input 全价计入（丢弃缓存字段后，
        // 上游若改为把 1010 一起报成 promptTokens 就是这个结果）
        TokenTracker overstated = new TokenTracker();
        overstated.setModel("claude-sonnet-4-20250514");
        overstated.recordUsage(1010, 5);

        assertTrue(overstated.estimateCost() > correct.estimateCost(),
                "把缓存读取按全价计入必然高估费用");
        assertTrue(overstated.estimateCost() > correct.estimateCost() * 2,
                "在此用量下高估幅度应超过一倍 —— 正确=" + correct.estimateCost()
                        + " 高估=" + overstated.estimateCost());
    }
}
