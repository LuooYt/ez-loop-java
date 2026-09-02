package com.inspirationi.loop.telemetry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内置价目表 —— 按模型名子串匹配已知模型的费率，支持配置覆盖。
 * <p>
 * 默认覆盖 Claude（opus / sonnet / haiku）与 OpenAI（gpt-4o / gpt-4o-mini）。
 * 集成方可经 {@code hms-core.pricing.models.*} 覆盖或新增费率，无需等 SDK 发版 ——
 * 见 {@code PricingProperties}。要接自己的计费系统则直接实现 {@link TokenPricing}。
 * <p>
 * <b>匹配是子串包含而非精确相等</b>：模型名带日期后缀（{@code claude-opus-4-20250514}）
 * 或网关前缀（{@code us.anthropic.claude-sonnet-4}），精确匹配会让绝大多数真实模型名
 * 都落空。代价是必须保证<b>更长的模式先匹配</b> —— 否则 {@code gpt-4o-mini} 会被
 * {@code gpt-4o} 抢先命中，按 16 倍的价格计费。这一点由 {@link #sortByPatternLength}
 * 在构造时结构性保证，不依赖声明顺序（此前那套 if-else 靠人工维护顺序，加一个模式就
 * 可能悄悄破坏它）。
 * <p>
 * <b>未知模型返回 {@link Optional#empty()}</b>，不套用任何默认费率。此前的实现会给
 * 未知模型套 Claude Sonnet 的价格并照常返回金额，那个数字看起来完全合理却与实际账单
 * 无关 —— 一个显式的「不知道」远比一个可信的错数有用。
 * <p>
 * 线程安全（构造后不可变）。
 */
public class BuiltinModelPricing implements TokenPricing {

    /** 每百万 token 的美元单价 —— 除以此值得到单个 token 的价格。 */
    private static final BigDecimal TOKENS_PER_UNIT = new BigDecimal("1000000");

    /**
     * 费用计算的精度 —— 单价除法需要有限精度，否则 1/3 这类除不尽的比值会抛
     * {@link ArithmeticException}。34 位有效数字（DECIMAL128）远超金额所需。
     */
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;

    /**
     * 一个模型的费率（每百万 token 的美元价）。
     *
     * @param input     普通输入
     * @param output    输出
     * @param cacheRead 缓存读取
     */
    public record ModelRate(BigDecimal input, BigDecimal output, BigDecimal cacheRead) {

        /** 便于从 yml/代码用字面量构造。 */
        public static ModelRate of(double input, double output, double cacheRead) {
            return new ModelRate(BigDecimal.valueOf(input),
                    BigDecimal.valueOf(output), BigDecimal.valueOf(cacheRead));
        }
    }

    /**
     * 内置默认费率（每百万 token 美元）—— 与重构前 {@code TokenTracker} 的价目表一致。
     * <p>
     * 这些是<b>撰写时</b>的公开价格，会随 provider 调价而过期。不确定时用
     * {@code hms-core.pricing.models.*} 覆盖，或注入自定义 {@link TokenPricing}。
     */
    public static Map<String, ModelRate> defaultRates() {
        Map<String, ModelRate> rates = new LinkedHashMap<>();
        rates.put("opus", ModelRate.of(15.0, 75.0, 1.5));
        rates.put("sonnet", ModelRate.of(3.0, 15.0, 0.3));
        rates.put("haiku", ModelRate.of(0.25, 1.25, 0.03));
        rates.put("gpt-4o-mini", ModelRate.of(0.15, 0.6, 0.075));
        rates.put("gpt-4o", ModelRate.of(2.5, 10.0, 1.25));
        return rates;
    }

    /** 匹配模式 → 费率，已按模式长度降序排列（长的先匹配）。 */
    private final List<Map.Entry<String, ModelRate>> rates;

    /** 使用内置默认费率。 */
    public BuiltinModelPricing() {
        this(null);
    }

    /**
     * 使用自定义费率覆盖内置默认值。
     *
     * @param overrides 模式（模型名子串，大小写不敏感）→ 费率。同名键覆盖内置项，
     *                  新键追加。{@code null} 或空表示全用内置默认值
     */
    public BuiltinModelPricing(Map<String, ModelRate> overrides) {
        Map<String, ModelRate> merged = defaultRates();
        if (overrides != null) {
            overrides.forEach((pattern, rate) -> {
                if (pattern != null && !pattern.isBlank() && rate != null) {
                    merged.put(pattern.toLowerCase(), rate);
                }
            });
        }
        this.rates = sortByPatternLength(merged);
    }

    /**
     * 按模式长度降序排列 —— 让 {@code gpt-4o-mini} 必然先于 {@code gpt-4o} 被检查。
     * <p>
     * 更长的模式一定更具体，因此「长者优先」是可靠的消歧规则，且不依赖声明顺序或
     * 配置文件里的键顺序 —— 集成方在 yml 里以任意顺序添加模式都不会破坏匹配。
     */
    private static List<Map.Entry<String, ModelRate>> sortByPatternLength(
            Map<String, ModelRate> merged) {
        List<Map.Entry<String, ModelRate>> sorted = new ArrayList<>(merged.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        return List.copyOf(sorted);
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>缓存写入（{@code cacheCreationTokens}）不计费</b> —— 沿用重构前的行为，
     * 以免同一份用量在升级前后给出不同金额。这是一处已知的低估：Anthropic 的缓存
     * 写入约为基础输入价的 1.25 倍，写入量大的会话费用会被少算。需要精确计费的
     * 集成方应实现自己的 {@link TokenPricing}，把这一项纳入。
     */
    @Override
    public Optional<BigDecimal> cost(String model, TokenUsage usage) {
        if (model == null || model.isBlank() || usage == null) {
            return Optional.empty();
        }
        ModelRate rate = findRate(model.toLowerCase());
        if (rate == null) {
            return Optional.empty();
        }
        BigDecimal total = priceOf(usage.inputTokens(), rate.input())
                .add(priceOf(usage.outputTokens(), rate.output()))
                .add(priceOf(usage.cacheReadTokens(), rate.cacheRead()));
        return Optional.of(total);
    }

    /** 找到首个模式命中的费率（列表已按模式长度降序）。 */
    private ModelRate findRate(String lowerCaseModel) {
        for (Map.Entry<String, ModelRate> entry : rates) {
            if (lowerCaseModel.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** token 数 × 单价 ÷ 一百万。 */
    private static BigDecimal priceOf(long tokens, BigDecimal pricePerMillion) {
        if (tokens <= 0 || pricePerMillion == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(pricePerMillion)
                .divide(TOKENS_PER_UNIT, MATH_CONTEXT);
    }

    /** 当前生效的模式列表（按匹配优先级，便于诊断配置是否被正确加载）。 */
    public List<String> patterns() {
        return rates.stream().map(Map.Entry::getKey).toList();
    }
}
