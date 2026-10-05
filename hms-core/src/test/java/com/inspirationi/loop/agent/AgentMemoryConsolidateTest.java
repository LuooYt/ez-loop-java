package com.inspirationi.loop.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆清洗（{@link InMemoryAgentMemory#consolidate()}）—— 唯一会花 token 的记忆操作，
 * 因此失败路径比成功路径更值得测。
 * <p>
 * 清洗由心跳线程调用；一旦它在失败时抛异常或反复重试，主动 Agent 的每一跳都会
 * 白白烧掉一次模型调用。熔断就是为这件事准备的。
 */
class AgentMemoryConsolidateTest {

    /** 阈值 5、上限 200 的清洗规格 —— 小阈值便于用少量条目触发。 */
    private static MemorySpec cleanupSpec(boolean cleanup) {
        return new MemorySpec(true, true, true, true,
                200, 20, 500, 1200, MemorySpec.DigestStrategy.SIMPLE, cleanup, 5, false);
    }

    private static void fill(InMemoryAgentMemory memory, int count) {
        for (int i = 0; i < count; i++) {
            memory.remember("待清洗的第 " + i + " 条事实", "agent", List.of("batch"));
        }
    }

    // ==================== 成功路径 ====================

    @Test
    void consolidatesOldestBatchIntoOneEntry() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 10);
        memory.setSummarizer(batch -> "合并后的结论");

        int merged = memory.consolidate();

        assertEquals(9, merged, "应合并掉最旧的 9 条（留 1 条作底）");
        assertEquals(2, memory.size(), "9 条旧记忆 + 1 条合并结果");
        assertTrue(memory.entries().stream().anyMatch(e -> e.content().equals("合并后的结论")),
                "合并结果应作为新条目存下");
        assertTrue(memory.entries().stream()
                        .anyMatch(e -> e.source().equals("consolidate")),
                "合并条目的来源应可追溯");
    }

    @Test
    void mergedEntryInheritsTagsFromBatch() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 10);
        memory.setSummarizer(batch -> "合并后的结论");

        memory.consolidate();

        AgentMemoryEntry merged = memory.entries().stream()
                .filter(e -> e.content().equals("合并后的结论"))
                .findFirst().orElseThrow();
        assertTrue(merged.tags().contains("batch"), "合并条目应继承原批次的标签：" + merged.tags());
    }

    /** 合并结果本身也要受 {@code maxEntryChars} 约束 —— 否则一次清洗就能把提示词撑爆。 */
    @Test
    void mergedEntryIsTruncatedToSpecLimit() {
        MemorySpec spec = new MemorySpec(true, true, true, true,
                200, 20, 40, 1200, MemorySpec.DigestStrategy.SIMPLE, true, 5, false);
        InMemoryAgentMemory memory = new InMemoryAgentMemory(spec);
        fill(memory, 10);
        memory.setSummarizer(batch -> "很长很长的合并结果".repeat(20));

        memory.consolidate();

        AgentMemoryEntry merged = memory.entries().stream()
                .filter(e -> e.source().equals("consolidate"))
                .findFirst().orElseThrow();
        assertEquals(40, merged.content().length(), "合并结果超长应被截断");
    }

    @Test
    void entriesBelowThresholdAreLeftAlone() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 5);   // 恰好等于阈值，不越界
        AtomicInteger calls = new AtomicInteger();
        memory.setSummarizer(batch -> {
            calls.incrementAndGet();
            return "不该被调用";
        });

        assertEquals(0, memory.consolidate());
        assertEquals(0, calls.get(), "未越阈值不得调用模型 —— 这是纯浪费");
        assertEquals(5, memory.size());
    }

    @Test
    void singleEntryIsNeverConsolidated() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 1);
        memory.setSummarizer(batch -> "合并");

        assertEquals(0, memory.consolidate(), "只剩一条时无可合并");
        assertEquals(1, memory.size());
    }

    // ==================== 未启用 / 未配置 ====================

    @Test
    void noOpWhenCleanupDisabledInSpec() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(false));
        fill(memory, 10);
        AtomicInteger calls = new AtomicInteger();
        memory.setSummarizer(batch -> {
            calls.incrementAndGet();
            return "合并";
        });

        assertEquals(0, memory.consolidate());
        assertEquals(0, calls.get(), "spec 关掉清洗时不得触发模型调用");
        assertEquals(10, memory.size());
    }

    @Test
    void noOpWithoutSummarizer() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 10);

        assertEquals(0, memory.consolidate(), "未注入清洗器时应为空操作");
        assertEquals(10, memory.size());
    }

    // ==================== 失败路径 ====================

    /** 清洗失败必须原样保留条目 —— 宁可不精简，也不能丢事实。 */
    @Test
    void failureKeepsAllEntries() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 10);
        memory.setSummarizer(batch -> {
            throw new IllegalStateException("模型挂了");
        });

        assertEquals(0, memory.consolidate());
        assertEquals(10, memory.size(), "失败时不得删除任何条目");
    }

    @Test
    void blankSummaryKeepsAllEntries() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 10);
        memory.setSummarizer(batch -> "   ");

        assertEquals(0, memory.consolidate());
        assertEquals(10, memory.size());
    }

    /**
     * 连续失败 3 次后熔断 —— 此后不再尝试，避免每次心跳都白烧一次模型调用。
     * <p>
     * 断言「第 4 次不再调用 summarizer」而非只看返回值：返回值本来就是 0，
     * 熔断与否在外部表现相同，只有调用计数能区分。
     */
    @Test
    void circuitBreakerStopsRetryingAfterRepeatedFailures() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 10);
        AtomicInteger calls = new AtomicInteger();
        memory.setSummarizer(batch -> {
            calls.incrementAndGet();
            throw new IllegalStateException("持续失败");
        });

        for (int i = 0; i < 5; i++) {
            assertEquals(0, memory.consolidate());
        }

        assertEquals(3, calls.get(),
                "连续失败 3 次后应熔断，之后不再调用模型（实际调用 " + calls.get() + " 次）");
        assertEquals(10, memory.size());
    }

    /**
     * 偶发失败不熔断：失败两次后第三次成功，说明熔断阈值是「<b>连续</b>」而非「累计」。
     * <p>
     * 若实现改成累计失败数，正常波动（模型偶发超时）会把清洗永久关掉 —— 且外部
     * 看不出来，只会表现为「记忆再也没被精简过」。
     */
    @Test
    void successResetsFailureStreakBeforeBreakerTrips() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 10);
        AtomicInteger calls = new AtomicInteger();
        memory.setSummarizer(batch -> {
            if (calls.incrementAndGet() <= 2) {
                throw new IllegalStateException("前两次失败");
            }
            return "最终合并结果";
        });

        assertEquals(0, memory.consolidate(), "第 1 次：失败");
        assertEquals(0, memory.consolidate(), "第 2 次：失败");
        assertEquals(9, memory.consolidate(),
                "第 3 次：失败计数仅 2，未达熔断阈值 3，应重试并成功");
        assertEquals(3, calls.get());
        assertEquals(2, memory.size(), "9 条旧记忆合并成 1 条，加 1 条未参与批次");
        assertTrue(memory.entries().stream().anyMatch(e -> e.content().equals("最终合并结果")));
    }

    /**
     * 清洗会让 digest 版本失效：合并前后摘要内容必然不同，缓存不能继续复用。
     */
    @Test
    void consolidateBumpsDigestVersion() {
        InMemoryAgentMemory memory = new InMemoryAgentMemory(cleanupSpec(true));
        fill(memory, 10);
        memory.setSummarizer(batch -> "合并后的结论");

        long before = memory.digestVersion();
        memory.consolidate();

        assertTrue(memory.digestVersion() > before, "清洗改变了记忆内容，版本必须自增");
        assertFalse(memory.digest().isEmpty(), "清洗后仍应有摘要可注入");
    }
}
