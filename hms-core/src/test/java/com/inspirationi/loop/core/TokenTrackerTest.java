package com.inspirationi.loop.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenTracker} 的阈值判定与定价测试。
 * <p>
 * 重点覆盖 {@link TokenTracker#shouldAutoCompact()} —— 自动压缩链路的触发条件。
 */
class TokenTrackerTest {

    /** 默认 200K 窗口减去 20K 预留 = 180K 有效窗口。 */
    private static final long EFFECTIVE_WINDOW = 180_000;

    @Test
    void freshTrackerDoesNotTriggerCompact() {
        TokenTracker tracker = new TokenTracker();
        assertEquals(0, tracker.getLastPromptTokens());
        assertFalse(tracker.shouldAutoCompact(),
                "未记录任何用量时不应触发压缩");
    }

    @Test
    void usageBelowThresholdDoesNotTriggerCompact() {
        TokenTracker tracker = new TokenTracker();
        // 50% 使用率
        tracker.recordUsage(EFFECTIVE_WINDOW / 2, 100);
        assertFalse(tracker.shouldAutoCompact());
        assertEquals(TokenTracker.TokenWarningState.NORMAL, tracker.getTokenWarningState());
    }

    @Test
    void usageAboveThresholdTriggersCompact() {
        TokenTracker tracker = new TokenTracker();
        // 95% > 93% 阈值
        tracker.recordUsage((long) (EFFECTIVE_WINDOW * 0.95), 100);
        assertTrue(tracker.shouldAutoCompact(),
                "超过 93% 阈值应触发自动压缩");
        assertEquals(TokenTracker.TokenWarningState.ERROR, tracker.getTokenWarningState());
    }

    @Test
    void warningStateProgressesThroughThresholds() {
        TokenTracker tracker = new TokenTracker();

        tracker.recordUsage((long) (EFFECTIVE_WINDOW * 0.85), 0);
        assertEquals(TokenTracker.TokenWarningState.WARNING, tracker.getTokenWarningState(),
                "85% 应处于 WARNING（82%~93%）");

        tracker.recordUsage((long) (EFFECTIVE_WINDOW * 0.99), 0);
        assertEquals(TokenTracker.TokenWarningState.BLOCKING, tracker.getTokenWarningState(),
                "99% 应处于 BLOCKING（>=98%）");
        assertTrue(tracker.isBlocking());
    }

    @Test
    void lastPromptTokensReflectsMostRecentCallNotSum() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage(1_000, 50);
        tracker.recordUsage(2_000, 50);

        // 上下文大小近似「最近一次」prompt，而非累计
        assertEquals(2_000, tracker.getLastPromptTokens());
        // 累计值单独统计
        assertEquals(3_000, tracker.getInputTokens());
        assertEquals(100, tracker.getOutputTokens());
        assertEquals(2, tracker.getApiCallCount());
    }

    /**
     * 缓存命中的 token 也是提示词的一部分，必须计入压缩判据。
     * <p>
     * 上游把 {@code input_tokens} 与 {@code cache_read_input_tokens} 分开上报。
     * 只看前者时，一个「缓存几乎全覆盖」的请求（常见于多轮工具对话）在判据里只剩
     * 很小一个数，压缩阈值永远达不到，上下文最终被上游以 400 拒绝。
     */
    @Test
    void cachedPromptTokensCountTowardContextSize() {
        TokenTracker tracker = new TokenTracker(20_000, 2_000);   // 有效窗口 18K，阈值 16.74K

        // 真实上下文 223 + 10_112 ≈ 10.3K，未到阈值
        tracker.recordUsage(223, 40, 10_112, 0);
        assertEquals(10_335, tracker.getLastPromptTokens(),
                "上下文规模应包含缓存命中部分，而不是只剩新增的 input");
        assertFalse(tracker.shouldAutoCompact());

        // 真实上下文 800 + 16_000 = 16.8K > 16.74K
        tracker.recordUsage(800, 40, 16_000, 0);
        assertEquals(16_800, tracker.getLastPromptTokens());
        assertTrue(tracker.shouldAutoCompact(),
                "缓存占大头时压缩判据仍须按完整提示词计算");

        // 累计统计保持原样：缓存单独计列，不与 input 混算
        assertEquals(1_023, tracker.getInputTokens());
        assertEquals(26_112, tracker.getCacheReadTokens());
    }

    @Test
    void setModelSwitchesPricing() {
        TokenTracker opus = new TokenTracker();
        opus.setModel("claude-opus-4-20250514");
        opus.recordUsage(1_000_000, 0);

        TokenTracker haiku = new TokenTracker();
        haiku.setModel("claude-3-haiku-20240307");
        haiku.recordUsage(1_000_000, 0);

        // Opus 输入 $15/M vs Haiku $0.25/M
        assertEquals(15.0, opus.estimateCost(), 0.001);
        assertEquals(0.25, haiku.estimateCost(), 0.001);
    }

    @Test
    void smallerContextWindowLowersCompactThreshold() {
        TokenTracker tracker = new TokenTracker();
        tracker.setContextWindowSize(30_000);
        tracker.setReservedTokens(5_000);

        // 有效窗口 25K，24K 即 96% > 93%
        tracker.recordUsage(24_000, 0);
        assertTrue(tracker.shouldAutoCompact(),
                "缩小上下文窗口后应更早触发压缩");
    }

    /**
     * setter 不得绕过构造器的非正数校验。
     * <p>
     * 裸赋值让窗口变成 0 时，有效窗口非正 → {@code getUsagePercentage()} 恒为 0 →
     * 压缩<b>永不触发</b>，上下文一路涨到被上游拒绝。这正是构造器注释里说的
     * 「极难定位」的症状，setter 必须共用同一套回退。
     */
    @Test
    void settersFallBackOnInvalidValuesInsteadOfDisablingCompaction() {
        TokenTracker tracker = new TokenTracker();

        tracker.setContextWindowSize(0);
        assertEquals(TokenTracker.DEFAULT_CONTEXT_WINDOW, tracker.getContextWindowSize(),
                "窗口设为 0 应回退到默认值，而不是让有效窗口归零");
        assertTrue(tracker.getEffectiveWindow() > 0, "有效窗口必须为正，否则压缩永不触发");

        tracker.setReservedTokens(-1);
        assertEquals(TokenTracker.DEFAULT_RESERVED_TOKENS, tracker.getReservedTokens(),
                "预留设为负数应回退到默认值");

        // 预留 >= 窗口同样会让有效窗口归零
        tracker.setReservedTokens(tracker.getContextWindowSize());
        assertEquals(TokenTracker.DEFAULT_RESERVED_TOKENS, tracker.getReservedTokens(),
                "预留不小于窗口时应回退到默认值");

        // 压缩仍然可以正常触发
        tracker.recordUsage((long) (tracker.getEffectiveWindow() * 0.95), 0);
        assertTrue(tracker.shouldAutoCompact(), "非法配置被回退后压缩应照常工作");
    }

    /** 缩小窗口后，原本合法的预留值若不再小于窗口，应一并被规范化。 */
    @Test
    void shrinkingWindowRenormalizesReservedTokens() {
        TokenTracker tracker = new TokenTracker(200_000, 50_000);
        assertEquals(50_000, tracker.getReservedTokens());

        // 窗口缩到比预留还小 —— 若不重新规范化，有效窗口会变成负数
        tracker.setContextWindowSize(30_000);
        assertTrue(tracker.getEffectiveWindow() > 0,
                "缩小窗口后有效窗口仍须为正，实际预留=" + tracker.getReservedTokens());
    }

    @Test
    void resetClearsAllCounters() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage(EFFECTIVE_WINDOW, 500);
        assertTrue(tracker.shouldAutoCompact());

        tracker.reset();
        assertEquals(0, tracker.getInputTokens());
        assertEquals(0, tracker.getLastPromptTokens());
        assertFalse(tracker.shouldAutoCompact(), "reset 后不应再触发压缩");
    }
}
