package com.inspirationi.loop.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 使用量追踪器 —— 记录 API 调用的 token 消耗并监控上下文窗口。
 * <p>
 * 从 ChatResponse 的 usage 元数据中提取 token 统计信息，
 * 支持按会话累计、费用估算和上下文窗口阈值监控。
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

    /** 模型定价（每百万 token 的美元价格） */
    private double inputPricePerMillion = 3.0;   // Claude Sonnet 4 input
    private double outputPricePerMillion = 15.0;  // Claude Sonnet 4 output
    private double cacheReadPricePerMillion = 0.3; // 缓存读取
    /** 当前模型名称（用于定价匹配与展示） */
    private String modelName = "claude-sonnet-4-20250514";
    /** 当前定价是否对应已识别的模型（见 {@link #isPricingKnown()}） */
    private boolean pricingKnown = true;

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
        this.contextWindowSize = contextWindowSize > 0
                ? contextWindowSize : DEFAULT_CONTEXT_WINDOW;
        this.reservedTokens = (reservedTokens > 0 && reservedTokens < this.contextWindowSize)
                ? reservedTokens : DEFAULT_RESERVED_TOKENS;
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
     * 设置模型和对应定价。
     * <p>
     * 未识别的模型名保留当前定价，并将 {@link #isPricingKnown()} 置为 false ——
     * 此时 {@link #estimateCost()} 的返回值是按 Claude Sonnet 价目表算出的
     * 参考值，不代表该模型的实际费用。
     */
    public void setModel(String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        this.modelName = model;
        String m = model.toLowerCase();
        // 根据模型设置定价
        if (m.contains("opus")) {
            setPricing(15.0, 75.0, 1.5);
        } else if (m.contains("sonnet")) {
            setPricing(3.0, 15.0, 0.3);
        } else if (m.contains("haiku")) {
            setPricing(0.25, 1.25, 0.03);
        } else if (m.contains("gpt-4o-mini")) {
            setPricing(0.15, 0.6, 0.075);
        } else if (m.contains("gpt-4o")) {
            setPricing(2.5, 10.0, 1.25);
        } else {
            // 未知模型：沿用既有定价，但标记费用估算不可信，避免静默给出错误金额
            pricingKnown = false;
        }
    }

    /** 应用一组定价并标记为已知。 */
    private void setPricing(double input, double output, double cacheRead) {
        this.inputPricePerMillion = input;
        this.outputPricePerMillion = output;
        this.cacheReadPricePerMillion = cacheRead;
        this.pricingKnown = true;
    }

    /**
     * 当前模型的定价是否已知。
     * <p>
     * 为 false 时 {@link #estimateCost()} 使用的是默认（Claude Sonnet）价目表，
     * 结果仅供参考。调用方展示费用前应检查此标志。
     */
    public boolean isPricingKnown() {
        return pricingKnown;
    }

    public long getInputTokens() { return totalInputTokens.get(); }
    public long getOutputTokens() { return totalOutputTokens.get(); }
    public long getCacheReadTokens() { return totalCacheReadTokens.get(); }
    public long getCacheCreationTokens() { return totalCacheCreationTokens.get(); }
    public long getTotalTokens() { return totalInputTokens.get() + totalOutputTokens.get(); }
    public long getApiCallCount() { return apiCallCount.get(); }
    public String getModelName() { return modelName; }

    /** 估算当前会话费用（美元） */
    public double estimateCost() {
        double inputCost = totalInputTokens.get() * inputPricePerMillion / 1_000_000.0;
        double outputCost = totalOutputTokens.get() * outputPricePerMillion / 1_000_000.0;
        double cacheCost = totalCacheReadTokens.get() * cacheReadPricePerMillion / 1_000_000.0;
        return inputCost + outputCost + cacheCost;
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

    public void setContextWindowSize(long size) { this.contextWindowSize = size; }

    public long getReservedTokens() { return reservedTokens; }

    public void setReservedTokens(long reserved) { this.reservedTokens = reserved; }

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
