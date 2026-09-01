package com.inspirationi.loop.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 拒绝追踪器 —— 跟踪连续和总计的权限拒绝次数。
 * <p>
 * 当连续拒绝达到阈值（3 次）或总计拒绝达到阈值（20 次）时，
 * 触发 {@link DenialCallback} 回调，供 SDK 集成方采取相应措施。
 * <p>
 * Web 场景集成方可通过 {@link #addDenialCallback(DenialCallback)} 注册回调，
 * 实现：发告警通知、写入审计日志、自动封禁会话等。
 */
public class DenialTracker {

    private static final Logger log = LoggerFactory.getLogger(DenialTracker.class);

    /** 连续拒绝阈值 —— 超过后触发回调 */
    public static final int MAX_CONSECUTIVE_DENIALS = 3;

    /** 总计拒绝阈值 —— 超过后触发回调 */
    public static final int MAX_TOTAL_DENIALS = 20;

    /** 当前连续拒绝次数（线程安全计数） */
    private final AtomicInteger consecutiveDenials = new AtomicInteger(0);
    /** 当前累计拒绝总次数（线程安全计数，成功时不重置） */
    private final AtomicInteger totalDenials = new AtomicInteger(0);

    /** 拒绝回调列表（线程安全） */
    private final List<DenialCallback> denialCallbacks = new CopyOnWriteArrayList<>();

    /** 记录一次拒绝 */
    public void recordDenial() {
        int consecutive = consecutiveDenials.incrementAndGet(); // 递增连续拒绝次数
        int total = totalDenials.incrementAndGet();             // 递增总计拒绝次数
        if (shouldFallbackToPrompting()) {
            log.warn("Denial threshold reached: {} consecutive, {} total",
                    consecutive, total);
            notifyCallbacks(consecutive, total);
        }
    }

    /** 记录一次成功（重置连续计数，但不重置总计） */
    public void recordSuccess() {
        consecutiveDenials.set(0);
    }

    /**
     * 是否已达到阈值（供 AgentToolExecutor 在 evaluate 后检查）。
     */
    public boolean shouldFallbackToPrompting() {
        return consecutiveDenials.get() >= MAX_CONSECUTIVE_DENIALS
                || totalDenials.get() >= MAX_TOTAL_DENIALS;
    }

    /** 完全重置计数器 */
    public void reset() {
        consecutiveDenials.set(0);
        totalDenials.set(0);
    }

    /** 获取当前连续拒绝次数 */
    public int getConsecutiveDenials() {
        return consecutiveDenials.get();
    }

    /** 获取当前总计拒绝次数 */
    public int getTotalDenials() {
        return totalDenials.get();
    }

    // ── 回调机制 ──

    /**
     * 注册拒绝阈值回调。
     * 当连续拒绝或总计拒绝达到阈值时，回调将被调用。
     */
    public void addDenialCallback(DenialCallback callback) {
        if (callback != null) {
            denialCallbacks.add(callback);
        }
    }

    /**
     * 移除回调。
     */
    public void removeDenialCallback(DenialCallback callback) {
        denialCallbacks.remove(callback);
    }

    /**
     * 通知所有已注册回调阈值已触发；单个回调抛异常不影响其余回调。
     */
    private void notifyCallbacks(int consecutive, int total) {
        for (DenialCallback cb : denialCallbacks) {
            try {
                cb.onDenialThreshold(consecutive, total);
            } catch (Exception e) {
                log.warn("DenialCallback error: {}", e.getMessage());
            }
        }
    }

    /**
     * 拒绝阈值回调接口。
     * <p>
     * SDK 集成方实现此接口以自定义阈值触发后的行为：
     * <pre>{@code
     * tracker.addDenialCallback((consecutive, total) -> {
     *     auditLog.warn("Denial threshold exceeded: {}/{}", consecutive, total);
     *     alarmService.send("权限拒绝异常，请检查会话");
     * });
     * }</pre>
     */
    @FunctionalInterface
    public interface DenialCallback {
        /**
         * 当拒绝次数达到阈值时触发。
         *
         * @param consecutiveDenials 当前连续拒绝次数
         * @param totalDenials       当前总计拒绝次数
         */
        void onDenialThreshold(int consecutiveDenials, int totalDenials);
    }
}
