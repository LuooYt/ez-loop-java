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
 * 曾经缺的只是 {@link AgentLoop} 里把二者接上。
 * <p>
 * 不接上的后果是<b>漏计</b>：{@code promptTokens} 只含未命中缓存的新输入，
 * 缓存读写量被整个丢弃，{@code estimateCost()} 因此系统性<b>低估</b> ——
 * 高缓存命中率的长会话里，绝大部分 token 走的正是缓存这条不计费的路径。
 * <p>
 * 反方向（把缓存读取按全价计入 input）会高估，因为缓存读取单价约为输入的
 * 1/10（Sonnet 为 $0.30 vs $3.00 每百万 token）。两个方向都是错的，
 * 只有分开记账才能得到正确金额 —— 见 {@link #fullPriceAccountingWouldOverstateCost}。
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
     * 模型报告的缓存用量必须一路传到 TokenTracker。
     * <p>
     * 曾经的缺陷：{@code AgentLoop} 只从 usage 里取 promptTokens /
     * completionTokens 两项、调两参 {@code recordUsage}，缓存字段被整个丢弃，
     * 两个累计器恒为 0。
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
                        + "丢弃它等于让这部分用量完全不计费，estimateCost() 随之低估 —— "
                        + "高缓存命中率的长会话里，绝大部分 token 走的正是这条路径");
        assertEquals(200, tracker.getCacheCreationTokens(),
                "模型报告的 200 缓存写入 token 同样必须转交");
    }

    /**
     * 分开记账是必需的：合并到 input 按全价计会高估。
     * <p>
     * <b>这是一个反面对照，不是当前行为</b>：{@link TokenTracker} 从不自行合并
     * 用量，本例的「全价」情形要靠调用方把 1010 一起报成 {@code promptTokens}
     * 才会出现。它与 {@link #agentLoopForwardsCacheTokensToTracker} 验证的漏计
     * 是<b>相反</b>方向的错误 —— 一并留在这里，是为了说明缓存 token 既不能丢、
     * 也不能并入 input，只有走四参 {@code recordUsage} 才得到正确金额。
     */
    @Test
    void fullPriceAccountingWouldOverstateCost() {
        // 正确记账：10 全价 input + 5 output + 1000 折价 cacheRead
        TokenTracker correct = new TokenTracker();
        correct.setModel("claude-sonnet-4-20250514");
        correct.recordUsage(10, 5, 1000, 200);

        // 反面对照：调用方若把缓存读取并进 promptTokens，就会按全价计
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
