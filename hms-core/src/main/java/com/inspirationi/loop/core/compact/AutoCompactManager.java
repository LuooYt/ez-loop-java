package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.core.TokenTracker;
import com.inspirationi.loop.core.compact.CompactionResult.CompactLayer;
import com.inspirationi.loop.core.compact.SessionMemoryCompact.CompactAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 自动压缩编排器 —— 根据 token 使用量自动选择并执行压缩策略。
 * <p>
 *  的自动压缩编排逻辑。在 AgentLoop 中每次 API 响应后调用。
 * 流程：检查阈值 → 微压缩 → Session Memory 压缩 → 全量压缩（兜底）
 * 熔断器：连续失败 {@value MAX_CONSECUTIVE_FAILURES} 次后暂停自动压缩。
 */
public class AutoCompactManager {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactManager.class);

    /** 连续失败阈值，超过后暂停自动压缩 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 微压缩策略 —— 纯本地裁剪旧 tool 结果，不消耗 API */
    private final MicroCompact microCompact;
    /** Session Memory 压缩策略 —— 用 AI 摘要旧消息，保留近期段 */
    private final SessionMemoryCompact sessionMemoryCompact;
    /** 全量压缩策略 —— 用 AI 摘要全部历史（兜底） */
    private final FullCompact fullCompact;
    /** Token 追踪器 —— 判断当前上下文占用是否达到压缩阈值 */
    private final TokenTracker tokenTracker;

    /** 连续压缩失败次数 */
    private int consecutiveFailures = 0;

    /** 是否已触发过熔断 */
    private boolean circuitBroken = false;

    /** 压缩事件回调（用于通知 UI） */
    private Consumer<CompactionResult> onCompactionEvent;

    /**
     * 构造自动压缩管理器。
     * Session Memory 与全量压缩均需直接调用 ChatModel 生成摘要。
     */
    public AutoCompactManager(ChatModel chatModel, TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
        this.microCompact = new MicroCompact();
        this.sessionMemoryCompact = new SessionMemoryCompact(chatModel);
        this.fullCompact = new FullCompact(chatModel);
    }

    /**
     * 设置压缩事件回调 —— 观测压缩何时发生、发生在哪一层。
     * <p>
     * 由 {@code AgentLoop} 在每次压缩检查前注册本轮的回调，最终来自使用方覆写的
     * {@link com.inspirationi.loop.api.HmsCallbacks#onCompaction}。
     * <p>
     * <b>每轮重设而非装配时一次性绑定</b>：本类实例是会话级持久对象，而回调是
     * 请求级的 —— 绑死会让它永远指向首个请求的接收端（SSE 场景下那个 emitter
     * 早已 complete）。传 {@code null} 即本轮无人观测，{@code notifyEvent} 退化
     * 为空操作。
     */
    public void setOnCompactionEvent(Consumer<CompactionResult> onCompactionEvent) {
        this.onCompactionEvent = onCompactionEvent;
    }

    /**
     * 在每次 API 响应后调用，根据 token 使用状态自动执行压缩。
     *
     * @param historySupplier  获取当前消息历史的函数
     * @param historyReplacer  替换消息历史的函数
     * @return 如果执行了压缩返回结果，否则返回 null
     */
    public CompactionResult autoCompactIfNeeded(
            Supplier<List<Message>> historySupplier,
            Consumer<List<Message>> historyReplacer) {

        // 每次 API 响应后更新活跃时间（用于 MicroCompact 时间感知策略）
        microCompact.recordActivity();

        // 熔断器检查
        if (circuitBroken) {
            return null;
        }

        // 检查是否需要压缩
        if (!tokenTracker.shouldAutoCompact()) {
            // 即使不需要自动压缩，也执行微压缩（成本极低）
            List<Message> history = historySupplier.get();
            microCompact.compact(history);
            return null;
        }

        log.info("Auto-compact triggered at {}% token usage",
                String.format("%.1f", tokenTracker.getUsagePercentage() * 100));

        List<Message> history = historySupplier.get();

        // 阶段 1：微压缩
        CompactionResult microResult = microCompact.compact(history);
        if (microResult.success()) {
            notifyEvent(microResult);
            consecutiveFailures = 0;
            // 微压缩生效后即返回，不再重查 shouldAutoCompact()：它读的是
            // lastPromptTokens，只在下一次 API 调用后更新，此刻必然仍高于阈值。
            // 原先的重查恒为 true，使微压缩每次都白做一遍，又立刻走进阶段 2/3
            // 的付费 AI 摘要。裁剪效果留待下一轮 API 响应后体现；届时若仍超阈值，
            // 且已无可裁剪内容（微压缩返回 noAction），自然会升级到深度压缩。
            //
            // 例外：已达阻塞阈值时下一次 API 调用可能直接超限，不能延后。
            if (!tokenTracker.isBlocking()) {
                log.info("Micro compact freed tool results; deferring deeper compaction "
                        + "until the next API response refreshes the token estimate");
                return microResult;
            }
            log.info("Micro compact done but usage is at the blocking threshold; "
                    + "proceeding to deep compaction");
        }

        // 阶段 2：Session Memory 压缩
        CompactAttempt sessionAttempt = attempt(
                () -> sessionMemoryCompact.tryCompact(history), "Session memory");
        if (sessionAttempt.isCompacted()) {
            return succeed(CompactLayer.SESSION_MEMORY, "Auto session memory compact",
                    history, sessionAttempt.history(), historyReplacer);
        }

        // 阶段 3：全量压缩（兜底）
        CompactAttempt fullAttempt = attempt(
                () -> fullCompact.tryCompact(history), "Full");
        if (fullAttempt.isCompacted()) {
            return succeed(CompactLayer.FULL, "Auto full compact (fallback)",
                    history, fullAttempt.history(), historyReplacer);
        }

        // 走到这里说明两层都没压出结果。是否计入熔断预算，取决于原因：
        // 「无可压缩」是正常状态（历史还短、上次压缩后新增不多），只有摘要通路
        // 确实出了问题才算失败。
        boolean realFailure = sessionAttempt.isFailure() || fullAttempt.isFailure();
        if (!realFailure) {
            log.debug("Nothing to compact at either layer; not counting as a failure");
            return CompactionResult.noAction(CompactLayer.SESSION_MEMORY,
                    "Nothing to compact");
        }

        // 一次尝试只记一次失败。此前阶段 2、阶段 3、末尾兜底各累加一次，使
        // MAX_CONSECUTIVE_FAILURES=3 实际变成「首次失败即熔断」—— 而熔断是永久的，
        // 一次偶发的摘要限流就让该会话此后再不压缩，上下文一路涨到 PTL。
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            circuitBroken = true;
            log.error("Auto-compact circuit breaker triggered after {} consecutive failed attempts",
                    consecutiveFailures);
            CompactionResult result = CompactionResult.failure(CompactLayer.FULL,
                    "Circuit breaker: auto-compact disabled after " + consecutiveFailures
                            + " failed attempts");
            notifyEvent(result);
            return result;
        }

        log.warn("All compaction layers failed (attempt #{} of {})",
                consecutiveFailures, MAX_CONSECUTIVE_FAILURES);
        return CompactionResult.failure(CompactLayer.SESSION_MEMORY,
                "All compaction strategies failed");
    }

    /**
     * 执行一层压缩尝试，把抛出的异常归一为 {@code FAILED}。
     * <p>
     * 各压缩层已在内部处理了自己的异常，这里只兜住意料之外的抛出 ——
     * 让「一层炸了」不至于中断另一层的兜底机会。
     */
    private static CompactAttempt attempt(Supplier<CompactAttempt> layer, String label) {
        try {
            return layer.get();
        } catch (Exception e) {
            log.warn("{} compact threw unexpectedly: {}", label, e.getMessage());
            return CompactAttempt.failed();
        }
    }

    /** 落地一次成功压缩：替换历史、清零失败计数、通知观测方。 */
    private CompactionResult succeed(CompactLayer layer, String reason,
                                     List<Message> before, List<Message> after,
                                     Consumer<List<Message>> historyReplacer) {
        historyReplacer.accept(after);
        CompactionResult result = CompactionResult.success(
                layer, before.size(), after.size(), reason);
        consecutiveFailures = 0;
        notifyEvent(result);
        log.info("{} compact: {} → {} messages", layer, before.size(), after.size());
        return result;
    }

    /** 手动重置熔断器 */
    public void resetCircuitBreaker() {
        circuitBroken = false;
        consecutiveFailures = 0;
        log.info("Auto-compact circuit breaker reset");
    }

    public boolean isCircuitBroken() {
        return circuitBroken;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** 通知压缩事件回调，回调异常不影响主流程 */
    private void notifyEvent(CompactionResult result) {
        if (onCompactionEvent != null) {
            try {
                onCompactionEvent.accept(result);
            } catch (Exception e) {
                log.debug("Compaction event notification failed", e);
            }
        }
    }
}
