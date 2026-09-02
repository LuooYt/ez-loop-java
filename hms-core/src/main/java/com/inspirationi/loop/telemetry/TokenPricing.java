package com.inspirationi.loop.telemetry;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Token 计费策略 —— 集成方可覆写的扩展点。
 * <p>
 * <b>为什么是扩展点而非内置逻辑</b>：模型价格会变、新模型会出，硬编码在 SDK 里
 * 意味着每次调价都得等一个新版本，集成方在此期间拿不到任何补救手段。做成接口后，
 * 集成方可以接自己的计费系统、查配置中心、按租户区分费率，或直接用内置实现
 * （{@link BuiltinModelPricing}）配 {@code hms-core.pricing.*} 覆盖价格。
 * <p>
 * <b>为什么是无状态函数</b>：查价目表是纯计算 —— 同样的模型名与用量必然得出同样的
 * 费用。做成无状态接口后可以单 Bean 全局共享、天然线程安全、无需为测试构造会话。
 * 此前这段逻辑以 5 个可变字段（三个价格 + 模型名 + 定价是否已知）存在
 * {@code TokenTracker} 上，并靠一个 {@code setModel} 去改，等于把纯函数写成了状态机。
 * <p>
 * <b>为什么返回 {@code Optional} 而非直接给数</b>：定价未知是常态（新模型、私有部署、
 * 兼容层网关），而「算出的金额」与「这金额可不可信」若走两条独立通道，调用方几乎必然
 * 只读前者。此前 {@code TokenTracker} 正是如此：{@code estimateCost()} 给数、
 * {@code isPricingKnown()} 给可信度，而后者<b>从未被任何代码读取</b>，未知模型的费用
 * 被静默按 Claude Sonnet 价目表算出并当作真实金额。合成单一返回值后，未知定价在类型
 * 层面就无法被忽略。
 * <p>
 * <b>为什么是 {@code BigDecimal}</b>：金额不该用二进制浮点表示 —— 累加多次调用的
 * 费用时 {@code double} 会积累误差。
 *
 * @see BuiltinModelPricing
 */
@FunctionalInterface
public interface TokenPricing {

    /**
     * 计算一段用量的费用（美元）。
     * <p>
     * 实现应当是纯函数：不持有可变状态，可被多线程并发调用。
     *
     * @param model 模型名（可为 {@code null} 或空 —— 无法解析模型时应返回 empty）
     * @param usage 四类 token 用量
     * @return 费用；该模型定价未知时返回 {@link Optional#empty()}。
     *         <b>不要返回 0 来表示未知</b> —— 那与「确实没有消耗」不可区分
     */
    Optional<BigDecimal> cost(String model, TokenUsage usage);
}
