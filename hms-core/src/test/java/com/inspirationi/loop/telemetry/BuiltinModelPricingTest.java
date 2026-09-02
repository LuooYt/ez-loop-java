package com.inspirationi.loop.telemetry;

import com.inspirationi.loop.telemetry.BuiltinModelPricing.ModelRate;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuiltinModelPricing} 的定价行为测试。
 * <p>
 * 重点覆盖三处从旧实现继承来的陷阱：子串匹配的优先级、未知模型的处理、
 * 以及缓存读取必须按其自身单价计费。
 */
class BuiltinModelPricingTest {

    private final BuiltinModelPricing pricing = new BuiltinModelPricing();

    private static BigDecimal costOf(BuiltinModelPricing pricing, String model, TokenUsage usage) {
        return pricing.cost(model, usage).orElseThrow(
                () -> new AssertionError("应当算出费用，但返回了 empty：model=" + model));
    }

    /** 断言金额相等 —— 用 compareTo 而非 equals，后者会因标度差异（0.25 vs 0.250）失败。 */
    private static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + "（期望 " + expected + "，实际 " + actual + "）");
    }

    @Test
    void chargesEachTokenClassAtItsOwnRate() {
        // Sonnet: input $3/M, output $15/M, cacheRead $0.3/M
        // 100万 input + 100万 output + 100万 cacheRead = 3 + 15 + 0.3
        BigDecimal cost = costOf(pricing, "claude-sonnet-4-20250514",
                new TokenUsage(1_000_000, 1_000_000, 1_000_000, 0));
        assertAmount("18.3", cost, "三类 token 应各按自身单价计费");
    }

    /**
     * 缓存读取不得按普通输入价计费。
     * <p>
     * 单价差约 10 倍，若把 cacheRead 并进 input，长会话费用会被高估数倍 ——
     * 这正是 {@code TokenUsage} 坚持四类分开承载的原因。
     */
    @Test
    void cacheReadIsMuchCheaperThanPlainInput() {
        BigDecimal asInput = costOf(pricing, "sonnet", new TokenUsage(1_000_000, 0, 0, 0));
        BigDecimal asCacheRead = costOf(pricing, "sonnet", new TokenUsage(0, 0, 1_000_000, 0));

        assertTrue(asCacheRead.compareTo(asInput) < 0, "缓存读取应比普通输入便宜");
        assertAmount("3", asInput, "普通输入 $3/M");
        assertAmount("0.3", asCacheRead, "缓存读取 $0.3/M");
    }

    /**
     * {@code gpt-4o-mini} 不能被 {@code gpt-4o} 抢先匹配。
     * <p>
     * 两者单价差约 16 倍。旧实现靠 if-else 的书写顺序保证这一点，加一个分支就可能
     * 悄悄破坏；现在由「模式长者优先」结构性保证。
     */
    @Test
    void longerPatternWinsSoMiniIsNotShadowed() {
        BigDecimal mini = costOf(pricing, "gpt-4o-mini", new TokenUsage(1_000_000, 0, 0, 0));
        BigDecimal full = costOf(pricing, "gpt-4o", new TokenUsage(1_000_000, 0, 0, 0));

        assertAmount("0.15", mini, "gpt-4o-mini 应按自己的 $0.15/M 计费");
        assertAmount("2.5", full, "gpt-4o 应按 $2.5/M 计费");
        assertTrue(mini.compareTo(full) < 0, "mini 必须比完整版便宜 —— 否则说明被抢匹配了");
    }

    /** 模式匹配对大小写与前后缀都不敏感（真实模型名常带网关前缀与日期后缀）。 */
    @Test
    void matchesModelNamesWithPrefixesAndSuffixes() {
        assertTrue(pricing.cost("us.anthropic.CLAUDE-OPUS-4-20250514", TokenUsage.of(1, 1))
                .isPresent(), "带网关前缀与大写的模型名也应匹配");
    }

    /**
     * 未知模型返回 empty，不套用任何默认费率。
     * <p>
     * 旧实现会给未知模型套 Sonnet 的价格并照常返回金额 —— 那个数字看起来完全合理
     * 却与实际账单无关。显式的「不知道」远比一个可信的错数有用。
     */
    @Test
    void unknownModelReturnsEmptyInsteadOfGuessing() {
        assertTrue(pricing.cost("llama-3-70b", TokenUsage.of(1_000_000, 0)).isEmpty(),
                "未知模型应返回 empty，而不是按某个默认价目表估一个数");
        assertTrue(pricing.cost(null, TokenUsage.of(1, 1)).isEmpty(), "模型名为 null 应返回 empty");
        assertTrue(pricing.cost("", TokenUsage.of(1, 1)).isEmpty(), "模型名为空应返回 empty");
        assertTrue(pricing.cost("sonnet", null).isEmpty(), "用量为 null 应返回 empty");
    }

    /** 零用量的已知模型返回 0（而非 empty）—— 「确实没花钱」与「定价未知」必须可区分。 */
    @Test
    void knownModelWithZeroUsageCostsZeroNotUnknown() {
        Optional<BigDecimal> cost = pricing.cost("sonnet", TokenUsage.NONE);
        assertTrue(cost.isPresent(), "已知模型 + 零用量应返回 0，而非 empty");
        assertAmount("0", cost.get(), "零用量费用为 0");
    }

    @Test
    void configuredRatesOverrideBuiltinDefaults() {
        Map<String, ModelRate> overrides = new LinkedHashMap<>();
        overrides.put("sonnet", ModelRate.of(99.0, 0.0, 0.0));
        BuiltinModelPricing custom = new BuiltinModelPricing(overrides);

        assertAmount("99", costOf(custom, "sonnet", new TokenUsage(1_000_000, 0, 0, 0)),
                "配置的费率应覆盖内置默认值");
        // 未被覆盖的模型仍走内置默认值
        assertAmount("0.25", costOf(custom, "haiku", new TokenUsage(1_000_000, 0, 0, 0)),
                "未覆盖的模型应沿用内置费率");
    }

    @Test
    void configuredRatesCanAddNewModels() {
        Map<String, ModelRate> overrides = new LinkedHashMap<>();
        overrides.put("my-private-llm", ModelRate.of(1.0, 2.0, 0.1));
        BuiltinModelPricing custom = new BuiltinModelPricing(overrides);

        assertAmount("1", costOf(custom, "my-private-llm-v2", new TokenUsage(1_000_000, 0, 0, 0)),
                "配置可为内置表之外的模型新增费率");
    }

    /**
     * 新增模式的匹配优先级由长度决定，与配置顺序无关。
     * <p>
     * 集成方在 yml 里以任意顺序添加模式都不该破坏匹配 —— 若依赖插入顺序，
     * 一个「先短后长」的配置就会让长模式永远命中不到。
     */
    @Test
    void patternPriorityIgnoresConfigurationOrder() {
        Map<String, ModelRate> shortFirst = new LinkedHashMap<>();
        shortFirst.put("my-llm", ModelRate.of(50.0, 0.0, 0.0));
        shortFirst.put("my-llm-turbo", ModelRate.of(1.0, 0.0, 0.0));

        BuiltinModelPricing custom = new BuiltinModelPricing(shortFirst);
        assertAmount("1", costOf(custom, "my-llm-turbo", new TokenUsage(1_000_000, 0, 0, 0)),
                "更长的模式应优先匹配，即使它在配置里排在后面");
    }

    /**
     * 缓存写入当前不计费 —— 锁定这个已知的低估，避免无意改动。
     * <p>
     * 沿用重构前的行为，以免同一份用量在升级前后给出不同金额。需要精确计费者
     * 应实现自己的 {@link TokenPricing}。
     */
    @Test
    void cacheCreationIsNotChargedByBuiltinPricing() {
        BigDecimal withWrite = costOf(pricing, "sonnet", new TokenUsage(0, 0, 0, 1_000_000));
        assertAmount("0", withWrite,
                "内置实现不对缓存写入计费（已知低估，见 BuiltinModelPricing 的说明）");
    }

    /** 费率表的诊断视图应按匹配优先级给出，便于确认配置是否生效。 */
    @Test
    void patternsAreExposedInMatchOrder() {
        var patterns = pricing.patterns();
        assertFalse(patterns.isEmpty(), "应能列出生效的模式");
        assertTrue(patterns.indexOf("gpt-4o-mini") < patterns.indexOf("gpt-4o"),
                "诊断视图应体现真实的匹配优先级：" + patterns);
    }

    /**
     * 除不尽的价格不应抛异常。
     * <p>
     * {@code BigDecimal.divide} 在无限小数且未指定精度时会抛
     * {@link ArithmeticException} —— 一个「1 美元 / 3」这样的费率配置若让整个
     * 用量查询 500，问题会很难定位。
     */
    @Test
    void nonTerminatingDivisionDoesNotThrow() {
        Map<String, ModelRate> overrides = new LinkedHashMap<>();
        overrides.put("odd-rate", new ModelRate(
                new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1")));
        BuiltinModelPricing custom = new BuiltinModelPricing(overrides);

        // 3 个 token × $1/M ÷ 1e6 = 0.000003，本身可终止；用 7 个 token 触发 1/7 类比值
        assertTrue(custom.cost("odd-rate", new TokenUsage(7, 0, 0, 0)).isPresent(),
                "除不尽的比值不应抛异常");
    }
}
