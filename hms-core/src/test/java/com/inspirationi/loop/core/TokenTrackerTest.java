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
