package com.inspirationi.loop.core;

import com.inspirationi.loop.telemetry.BuiltinModelPricing;
import com.inspirationi.loop.telemetry.TokenPricing;
import com.inspirationi.loop.telemetry.TokenUsage;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 使用量追踪器 —— <b>只负责记账</b>：累计四类 token 消耗并监控上下文窗口。
 * <p>
 * 从 ChatResponse 的 usage 元数据中提取统计信息，按会话累计，并据
 * {@code lastPromptTokens} 判断是否该触发压缩。
 * <p>
 * <b>定价不在这里</b>。费用计算已抽象为 {@link TokenPricing} —— 查价目表是纯函数，
 * 却曾以「三个价格字段 + 模型名 + 定价是否已知」五个可变字段的形式长在本类上，还配
 * 一个 {@code setModel} 去改它们。要算费用，把 {@link #usageSnapshot()} 与模型名
 * 一起交给 {@code TokenPricing}：
 * <pre>{@code
 * Optional<BigDecimal> cost = pricing.cost(model, tracker.usageSnapshot());
 * }</pre>
 * 本类残留的 {@link #setModel}、{@link #estimateCost()}、{@link #isPricingKnown()}、
 * {@link #getModelName()} 均已废弃，仅为兼容既有集成方而保留。
 *
 * @see TokenPricing
 */
public class TokenTracker {

    // ── 上下文窗口阈值常量 ──
    /** 自动压缩触发百分比（有效窗口的 93%） */
    public static final double AUTO_COMPACT_THRESHOLD_PCT = 0.93;
    /** 警告阈值百分比（82%） */
    public static final double WARNING_THRESHOLD_PCT = 0.82;
    /** 阻塞阈值百分比（98%，必须压缩才能继续） */
    public static final double BLOCKING_THRESHOLD_PCT = 0.98;
    /** 自动压缩缓冲 token 数 */
    public static final long AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    /** 手动压缩缓冲 token 数 */
    public static final long MANUAL_COMPACT_BUFFER_TOKENS = 3_000;

    /** 上下文窗口警告状态 */
    public enum TokenWarningState {
        NORMAL,   // 正常（绿色）
        WARNING,  // 接近阈值（黄色）
        ERROR,    // 达到压缩阈值（红色）
        BLOCKING  // 必须压缩才能继续（闪烁红）
    }

    /** 累计输入 token 数 */
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    /** 累计输出 token 数 */
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    /** 累计缓存读取 token 数 */
    private final AtomicLong totalCacheReadTokens = new AtomicLong(0);
    /** 累计缓存写入（创建）token 数 */
    private final AtomicLong totalCacheCreationTokens = new AtomicLong(0);
    /** 累计 API 调用次数 */
    private final AtomicLong apiCallCount = new AtomicLong(0);

    /** 最近一次 API 调用报告的 prompt token 数（近似当前上下文大小） */
    private final AtomicLong lastPromptTokens = new AtomicLong(0);

    /**
     * 当前模型名称 —— 仅用于展示与已废弃的 {@link #estimateCost()}。
     * <p>
     * 定价本身已移出本类（见 {@link TokenPricing}）：查价目表是纯函数，不该以
     * 「三个价格字段 + 模型名 + 定价是否已知」这五个可变字段的形式存在于一个
     * 记账器上。本字段保留只为让废弃的兼容 API 仍能工作。
     */
    private String modelName;

    /** 默认上下文窗口大小 —— Claude / GPT-4o 级别模型的常见窗口。 */
    public static final long DEFAULT_CONTEXT_WINDOW = 200_000;

    /**
     * 默认预留 token 数 —— 从窗口中扣除，留给模型的输出与压缩摘要本身。
     * <p>
     * 有效窗口 = 窗口 - 预留，压缩阈值按有效窗口的百分比计算。因此预留值必须
     * 显著小于窗口，否则有效窗口归零、占用率恒为 0，压缩永不触发。
     */
    public static final long DEFAULT_RESERVED_TOKENS = 20_000;

    /** 上下文窗口总大小（token） */
    private long contextWindowSize;
    /** 预留给输出的 token 数 */
    private long reservedTokens;

    /**
     * 构造 TokenTracker，使用默认窗口（200K）与默认预留（20K）。
     * <p>
     * 仍支持环境变量 {@code HMS_CORE_CONTEXT_WINDOW} 覆盖窗口，用于无 Spring 容器
     * 的直接使用场景。配置优先级见 {@link #TokenTracker(long, long)}。
     */
    public TokenTracker() {
        this(envWindowOrDefault(), DEFAULT_RESERVED_TOKENS);
    }

    /**
     * 构造 TokenTracker，显式指定窗口与预留 token 数。
     * <p>
     * Spring 场景下由 {@code hms-core.context-window} / {@code hms-core.reserved-tokens}
     * 经 {@code ApiAutoConfiguration} 注入 —— yml 配置优先于环境变量，因为它是
     * 应用自己的声明式配置；环境变量仅在未配置 yml 时作为回退（见
     * {@link #envWindowOrDefault()}）。
     * <p>
     * 非正数一律回退到默认值：窗口为 0 会让占用率计算除零，预留 ≥ 窗口会让有效
     * 窗口归零或为负，两种情况都会使压缩逻辑彻底失效 —— 静默接受这种配置，症状
     * 会表现为"上下文涨到超限却从不压缩"，极难定位。
     *
     * @param contextWindowSize 上下文窗口总大小；{@code <= 0} 时用
     *                          {@link #DEFAULT_CONTEXT_WINDOW}
     * @param reservedTokens    预留 token 数；{@code <= 0} 或 {@code >= 窗口} 时用
     *                          {@link #DEFAULT_RESERVED_TOKENS}
     */
    public TokenTracker(long contextWindowSize, long reservedTokens) {
        this.contextWindowSize = normalizeWindow(contextWindowSize);
        this.reservedTokens = normalizeReserved(reservedTokens, this.contextWindowSize);
    }

    /** 窗口规范化：非正数回退到默认值。 */
    private static long normalizeWindow(long size) {
        return size > 0 ? size : DEFAULT_CONTEXT_WINDOW;
    }

    /** 预留规范化：非正数或不小于窗口时回退到默认值。 */
    private static long normalizeReserved(long reserved, long window) {
        return (reserved > 0 && reserved < window) ? reserved : DEFAULT_RESERVED_TOKENS;
    }

    /**
     * 读环境变量 {@code HMS_CORE_CONTEXT_WINDOW}，无效或缺失时返回默认窗口。
     * <p>
     * 保留它是为了兼容不经 Spring 直接 {@code new TokenTracker()} 的用法；
     * Spring 装配路径走带参构造，yml 优先。
     */
    private static long envWindowOrDefault() {
        String envWindow = System.getenv("HMS_CORE_CONTEXT_WINDOW");
        if (envWindow == null || envWindow.isBlank()) {
            return DEFAULT_CONTEXT_WINDOW;
        }
        try {
            long parsed = Long.parseLong(envWindow.trim());
            return parsed > 0 ? parsed : DEFAULT_CONTEXT_WINDOW;
        } catch (NumberFormatException e) {
            return DEFAULT_CONTEXT_WINDOW;
        }
    }

    /** 记录一次 API 调用的 token 使用 */
    public void recordUsage(long inputTokens, long outputTokens) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        lastPromptTokens.set(inputTokens);
        apiCallCount.incrementAndGet();
    }

    /** 记录一次包含缓存的 API 调用 */
    public void recordUsage(long inputTokens, long outputTokens, long cacheRead, long cacheCreation) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalCacheReadTokens.addAndGet(cacheRead);
        totalCacheCreationTokens.addAndGet(cacheCreation);
        lastPromptTokens.set(inputTokens);
        apiCallCount.incrementAndGet();
    }

    /**
     * 记录当前模型名 —— 仅供展示与已废弃的 {@link #estimateCost()} 查价使用。
     *
     * @deprecated 本方法曾同时承担「记下模型名」与「切换价目表」两件事。定价已抽象为
     *         {@link TokenPricing}，本类不再持有价格。仍需模型名时调用方自己保存即可，
     *         算费用请把模型名与 {@link #usageSnapshot()} 一起交给 {@code TokenPricing}。
     */
    @Deprecated(since = "0.2.0")
    public void setModel(String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        this.modelName = model;
    }

    /**
     * 当前模型的定价是否已知。
     *
     * @deprecated 「金额」与「金额可不可信」分两条通道返回，调用方几乎必然只读前者 ——
     *         本方法在整个代码库中从未被读取过，未知模型的费用因此被静默按 Claude
     *         Sonnet 价目表算出并当作真实金额。改用
     *         {@link TokenPricing#cost(String, TokenUsage)}，未知定价以
     *         {@code Optional.empty()} 表达，类型上无法被忽略。
     */
    @Deprecated(since = "0.2.0")
    public boolean isPricingKnown() {
        return modelName != null
                && new BuiltinModelPricing().cost(modelName, usageSnapshot()).isPresent();
    }

    public long getInputTokens() { return totalInputTokens.get(); }
    public long getOutputTokens() { return totalOutputTokens.get(); }
    public long getCacheReadTokens() { return totalCacheReadTokens.get(); }
    public long getCacheCreationTokens() { return totalCacheCreationTokens.get(); }
    public long getTotalTokens() { return totalInputTokens.get() + totalOutputTokens.get(); }
    public long getApiCallCount() { return apiCallCount.get(); }

    /**
     * 最近一次 {@link #setModel} 记下的模型名（从未设置时为 {@code null}）。
     *
     * @deprecated 模型名不是记账器的职责，且本方法从无消费方。需要它的调用方应从
     *         {@code ChatModel.getOptions().getModel()} 直接读取 —— 那才是唯一的
     *         权威来源，不会与本类的副本不同步。
     */
    @Deprecated(since = "0.2.0")
    public String getModelName() { return modelName; }

    /**
     * 四类累计用量的快照 —— 交给 {@link TokenPricing} 算费的标准入口。
     * <p>
     * 四项分开承载而非合并：缓存读取单价约为普通输入的 1/10，混入 input 会让费用
     * 按全价计算，长会话可高估数倍。
     */
    public TokenUsage usageSnapshot() {
        return new TokenUsage(
                totalInputTokens.get(), totalOutputTokens.get(),
                totalCacheReadTokens.get(), totalCacheCreationTokens.get());
    }

    /**
     * 估算当前会话费用（美元）。
     *
     * @deprecated 改用 {@link TokenPricing#cost(String, TokenUsage)} —— 它返回
     *         {@code BigDecimal}（金额不该用二进制浮点，累加会积累误差），并以
     *         {@code Optional.empty()} 明确表达「该模型定价未知」。
     *         <p>
     *         <b>行为变化</b>：模型名未识别（或从未调用 {@link #setModel}）时本方法
     *         现在返回 {@code 0.0}，而此前会按 Claude Sonnet 的价目表算出一个看似
     *         合理却与实际账单无关的金额。若你的代码依赖旧行为，请迁移到
     *         {@code TokenPricing} 并显式处理 empty。
     */
    @Deprecated(since = "0.2.0")
    public double estimateCost() {
        if (modelName == null) {
            return 0.0;
        }
        return new BuiltinModelPricing().cost(modelName, usageSnapshot())
                .map(BigDecimal::doubleValue)
                .orElse(0.0);
    }

    // ── 上下文窗口监控 ──

    /** 有效上下文窗口大小（总窗口 - 预留输出） */
    public long getEffectiveWindow() {
        return contextWindowSize - reservedTokens;
    }

    /** 最近一次 prompt 的 token 数（近似当前上下文大小） */
    public long getLastPromptTokens() {
        return lastPromptTokens.get();
    }

    /** 当前上下文使用百分比 */
    public double getUsagePercentage() {
        long effective = getEffectiveWindow();
        if (effective <= 0) return 0;
        return (double) lastPromptTokens.get() / effective;
    }

    /** 是否应触发自动压缩 */
    public boolean shouldAutoCompact() {
        return getUsagePercentage() >= AUTO_COMPACT_THRESHOLD_PCT;
    }

    /** 是否已达到阻塞阈值（必须压缩才能继续） */
    public boolean isBlocking() {
        return getUsagePercentage() >= BLOCKING_THRESHOLD_PCT;
    }

    /** 获取自动压缩触发的 token 阈值 */
    public long getAutoCompactThreshold() {
        return (long) (getEffectiveWindow() * AUTO_COMPACT_THRESHOLD_PCT);
    }

    /** 获取当前 token 警告状态 */
    public TokenWarningState getTokenWarningState() {
        double pct = getUsagePercentage();
        if (pct >= BLOCKING_THRESHOLD_PCT) return TokenWarningState.BLOCKING;
        if (pct >= AUTO_COMPACT_THRESHOLD_PCT) return TokenWarningState.ERROR;
        if (pct >= WARNING_THRESHOLD_PCT) return TokenWarningState.WARNING;
        return TokenWarningState.NORMAL;
    }

    public long getContextWindowSize() { return contextWindowSize; }

    /**
     * 运行时调整上下文窗口 —— 与构造器共用同一套校验。
     * <p>
     * <b>不能裸赋值</b>：窗口设成 0 会让 {@link #getUsagePercentage()} 因有效窗口
     * 非正而恒返回 0，压缩<b>永不触发</b>，上下文一路涨到被上游拒绝。这正是构造器
     * 注释里说的「极难定位」的症状 —— 绕过校验的 setter 等于把那个陷阱又挖了回来。
     * <p>
     * 调窗口可能让原本合法的预留值变得不再小于窗口，因此顺带重新规范化预留 ——
     * 否则「窗口 200K/预留 20K」改成「窗口 10K」会得到预留 ≥ 窗口的组合，
     * 有效窗口归零，同样使压缩失效。
     *
     * @param size 新的窗口大小；{@code <= 0} 时回退到 {@link #DEFAULT_CONTEXT_WINDOW}
     */
    public void setContextWindowSize(long size) {
        this.contextWindowSize = normalizeWindow(size);
        this.reservedTokens = normalizeReserved(this.reservedTokens, this.contextWindowSize);
    }

    public long getReservedTokens() { return reservedTokens; }

    /**
     * 运行时调整预留 token 数 —— 与构造器共用同一套校验。
     *
     * @param reserved 新的预留数；{@code <= 0} 或 {@code >= 当前窗口} 时回退到
     *                 {@link #DEFAULT_RESERVED_TOKENS}
     */
    public void setReservedTokens(long reserved) {
        this.reservedTokens = normalizeReserved(reserved, this.contextWindowSize);
    }

    /** 重置统计 */
    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalCacheReadTokens.set(0);
        totalCacheCreationTokens.set(0);
        lastPromptTokens.set(0);
        apiCallCount.set(0);
    }

    /** 格式化 token 数量（带千位分隔） */
    public static String formatTokens(long tokens) {
        if (tokens < 1000) return String.valueOf(tokens);
        if (tokens < 1_000_000) return String.format("%.1fK", tokens / 1000.0);
        return String.format("%.2fM", tokens / 1_000_000.0);
    }
}
