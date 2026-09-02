package com.inspirationi.loop.api;

import com.inspirationi.loop.telemetry.TokenPricing;
import com.inspirationi.loop.telemetry.TokenUsage;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Token 使用统计 —— 累加整个会话的 Token 消耗，并（可选）附带费用。
 * <p>
 * 四类 token 分开呈现：缓存读取的单价约为普通输入的 1/10，把它并入
 * {@link #inputTokens()} 会让调用方按全价估算，长会话可高估数倍。
 */
public record TokenStats(
        /** 累计输入 Token（不含缓存读取部分） */
        long inputTokens,

        /** 累计输出 Token */
        long outputTokens,

        /** 总 Token（输入 + 输出，<b>不含</b>缓存部分，与历史语义保持一致） */
        long totalTokens,

        /** 累计缓存读取 Token —— 单价约为普通输入的 1/10 */
        long cacheReadTokens,

        /** 累计缓存写入 Token —— 单价高于普通输入 */
        long cacheCreationTokens,

        /**
         * 预估费用（美元）。
         * <p>
         * <b>{@code null} 表示该模型定价未知，不要当作 0</b> —— 二者必须可区分，
         * 否则「没配价目表」会被读成「没花钱」。见 {@link TokenPricing}。
         */
        BigDecimal cost,

        /**
         * 算费所用的模型名（{@code null} 表示未能算出费用）。
         * <p>
         * 供调用方说明「这个金额是按哪个价目表算的」—— 价格会随 provider 调价而变，
         * 一个不注明依据的金额无法核对。
         */
        String pricingModel
) {

    /** 空统计 */
    public static final TokenStats ZERO = new TokenStats(0, 0, 0, 0, 0, null, null);

    /**
     * 创建仅含输入/输出的统计（无缓存、无费用信息）。
     * <p>
     * 保留此工厂以兼容既有调用方；新代码宜用
     * {@link #of(TokenUsage, TokenPricing, String)} 以带上缓存与费用。
     */
    public static TokenStats of(long input, long output) {
        return new TokenStats(input, output, input + output, 0, 0, null, null);
    }

    /**
     * 由用量快照与计费策略构建完整统计。
     * <p>
     * {@code pricing} 或 {@code model} 缺失、或该模型定价未知时，{@link #cost()}
     * 为 {@code null} —— 调用方应显式呈现「定价未知」而非 $0.00。
     *
     * @param usage   四类累计用量（{@code null} 视为全零）
     * @param pricing 计费策略，可为 {@code null}（不计费）
     * @param model   模型名，可为 {@code null}（无从查价）
     */
    public static TokenStats of(TokenUsage usage, TokenPricing pricing, String model) {
        TokenUsage u = usage != null ? usage : TokenUsage.NONE;
        BigDecimal cost = pricing == null ? null : pricing.cost(model, u).orElse(null);
        // 定价未知时不填 pricingModel —— 报一个「按 X 算的」却又没有金额只会让人困惑
        String appliedModel = cost != null ? model : null;
        return new TokenStats(
                u.inputTokens(), u.outputTokens(),
                u.inputTokens() + u.outputTokens(),
                u.cacheReadTokens(), u.cacheCreationTokens(),
                cost, appliedModel);
    }

    /** 费用（已知则有值）—— 便于调用方以 {@code Optional} 风格处理未知定价。 */
    public Optional<BigDecimal> costIfKnown() {
        return Optional.ofNullable(cost);
    }
}
