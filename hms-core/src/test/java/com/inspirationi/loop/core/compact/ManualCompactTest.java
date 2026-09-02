package com.inspirationi.loop.core.compact;

import com.inspirationi.loop.core.TokenTracker;
import com.inspirationi.loop.core.compact.CompactionResult.CompactLayer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动压缩 {@link AutoCompactManager#compactNow} 的语义 —— <b>无条件执行</b>。
 * <p>
 * 手动压缩不是「自动压缩的一次提前调用」，而是刻意绕过全部自动启发式：
 * 既不看 {@code TokenTracker.shouldAutoCompact()} 的阈值判断，也不看熔断器。
 * 理由是这两者服务的都是「自动」这一前提 —— 阈值回答「是否<em>需要</em>压」，
 * 熔断器防止<em>自动</em>压缩在故障时反复烧钱；而用户点下「压缩」按钮时，
 * 「压不压」已经由人决定了，程序只需回答「压成了没有」。
 * <p>
 * 因此本测试的重点不在「压缩本身能否工作」（那由
 * {@code CompactionCountReportingTest} 等覆盖），而在于钉住那些
 * <b>让 autoCompactIfNeeded 早退的条件，对 compactNow 一律不成立</b>。
 */
class ManualCompactTest {

    /** 只返回摘要文本的模型 —— 让全量压缩能走通摘要通路。 */
    private static ChatModel summarizingModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("SUMMARY"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    /**
     * 摘要返回空串的模型 —— 摘要通路的「失败」路径（非异常，而是拿不到内容）。
     *
     * @param failing 置 true 时返回空摘要（失败），置 false 后返回正常摘要。
     *                用于在同一个 manager 上先把熔断器打开，再验证手动压缩仍能成功。
     */
    private static ChatModel switchableSummarizer(AtomicBoolean failing) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String text = failing.get() ? "" : "SUMMARY";
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage(text), ChatGenerationMetadata.NULL)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    /**
     * 20 组 user/assistant 共 40 条<b>短</b>消息。
     * <p>
     * 短消息是刻意的：微压缩无可裁剪的 tool 结果，压缩才会真正落到深度层。
     */
    private static List<Message> shortHistory() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(new UserMessage("u" + i));
            history.add(new AssistantMessage("a" + i));
        }
        return history;
    }

    /** 带长填充的历史 —— 用于把自动压缩推到「真失败」而非「无可压缩」。 */
    private static List<Message> longHistory(int rounds) {
        String filler = "x".repeat(2000);
        List<Message> h = new ArrayList<>();
        h.add(new SystemMessage("sys"));
        for (int i = 0; i < rounds; i++) {
            h.add(new UserMessage("u" + i + filler));
            h.add(new AssistantMessage("a" + i + filler));
        }
        return h;
    }

    /** 就地替换的 replacer —— 与 {@code AgentLoop.replaceHistory} 的行为一致。 */
    private static void replaceInPlace(List<Message> history, List<Message> replacement) {
        history.clear();
        history.addAll(replacement);
    }

    /**
     * 没到 token 阈值也必须压 —— 这是 compactNow 与 autoCompactIfNeeded 的核心差异。
     * <p>
     * 全程不调 {@code recordUsage}，{@code shouldAutoCompact()} 因此为 false：
     * 同样的输入交给 {@code autoCompactIfNeeded} 会在第二道闸门直接早退并返回 null。
     * 手动压缩必须无视它 —— 否则用户点了「压缩」却什么也没发生，只能靠先灌满上下文
     * 才能手动压缩，这与该功能的意图完全相反。
     */
    @Test
    void compactsEvenWhenTokenThresholdIsNotReached() {
        TokenTracker tracker = new TokenTracker();
        assertFalse(tracker.shouldAutoCompact(),
                "前置条件：本用例要求「尚未达到自动压缩阈值」");

        AutoCompactManager manager = new AutoCompactManager(summarizingModel(), tracker);
        List<Message> history = shortHistory();

        CompactionResult result = manager.compactNow(
                () -> List.copyOf(history), replacement -> replaceInPlace(history, replacement));

        assertTrue(result.success(),
                "手动压缩必须忽略 token 阈值。未达阈值就不压，等于让用户点了按钮却毫无反应："
                        + result.reason());
        assertEquals(CompactLayer.MANUAL, result.layer(),
                "手动压缩直接走全量层，结果应标记为 MANUAL 以便使用方区分自动与手动");
    }

    /**
     * 熔断器打开后，手动压缩仍必须执行。
     * <p>
     * 熔断是<b>永久</b>的（只能由 {@code resetCircuitBreaker()} 清除），它防的是自动
     * 压缩在摘要通路故障时每轮都白付一次摘要调用。用户显式触发不在此列 ——「要不要
     * 再试一次」由人判断。若手动压缩也被熔断挡住，一次偶发的摘要限流就会让该会话
     * 此后连手动压缩都永久不可用。
     */
    @Test
    void bypassesCircuitBreaker() {
        AtomicBoolean failing = new AtomicBoolean(true);
        TokenTracker tracker = new TokenTracker();
        tracker.recordUsage((long) (tracker.getEffectiveWindow() * 0.95), 100);

        AutoCompactManager manager = new AutoCompactManager(switchableSummarizer(failing), tracker);
        List<Message> history = longHistory(30);

        // 连续 3 次自动压缩尝试均失败 → 熔断
        for (int i = 0; i < 3; i++) {
            manager.autoCompactIfNeeded(() -> history, replacement -> { });
        }
        assertTrue(manager.isCircuitBroken(),
                "前置条件：本用例要求熔断器已打开（连续 3 次失败）");

        // 摘要通路恢复后，手动压缩应当照常工作 —— 而自动压缩此时仍被熔断挡着
        failing.set(false);
        CompactionResult result = manager.compactNow(
                () -> List.copyOf(history), replacement -> replaceInPlace(history, replacement));

        assertTrue(result.success(),
                "熔断器已打开时手动压缩仍必须执行。熔断是永久的，若它也挡住手动压缩，"
                        + "该会话此后连人工干预都无法压缩上下文：" + result.reason());
        assertEquals(CompactLayer.MANUAL, result.layer());
    }

    /**
     * 压缩前后的条数必须是两个不同的真实值。
     * <p>
     * 钉住 {@code succeed} 里「先取 size 再替换」的顺序：replacer 是就地
     * {@code clear() + addAll()}，若在替换之后才读 before.size()，两个数字会恒等，
     * 使用方据此判断压缩效果时只会看到「4 → 4」这种压了却报没压的结果。
     */
    @Test
    void reportsDistinctBeforeAndAfterCounts() {
        AutoCompactManager manager = new AutoCompactManager(
                summarizingModel(), new TokenTracker());
        List<Message> history = shortHistory();
        int realBefore = history.size();

        CompactionResult result = manager.compactNow(
                () -> List.copyOf(history), replacement -> replaceInPlace(history, replacement));

        assertTrue(result.success(), "本用例期望压缩成功：" + result.reason());
        assertEquals(realBefore, result.messagesBefore(),
                "messagesBefore 必须是压缩前的真实条数");
        assertTrue(result.messagesAfter() < result.messagesBefore(),
                "压缩成功就该真的减少条数，报告才有意义："
                        + result.messagesBefore() + " → " + result.messagesAfter());
        assertEquals(result.messagesAfter(), history.size(),
                "历史应已被替换为压缩后的内容");
    }

    /**
     * 历史太短时返回「无操作」，且不得改动历史。
     * <p>
     * {@code FullCompact} 的门槛是历史长度 ≤ 4。这属于正常状态而非故障，因此返回
     * {@code success=false} 的 noAction 而不抛异常 —— 使用方只需照常展示 reason。
     */
    @Test
    void shortHistoryYieldsNoAction() {
        AutoCompactManager manager = new AutoCompactManager(
                summarizingModel(), new TokenTracker());
        List<Message> history = new ArrayList<>(List.of(
                new SystemMessage("sys"),
                new UserMessage("hi"),
                new AssistantMessage("hello")));

        CompactionResult result = manager.compactNow(
                () -> List.copyOf(history), replacement -> replaceInPlace(history, replacement));

        assertFalse(result.success(), "3 条消息未过 FullCompact 的长度门槛，不应报成功");
        assertTrue(result.reason().contains("Nothing to compact"),
                "无可压缩是正常状态，reason 应明确说明而非报成故障：" + result.reason());
        assertEquals(3, history.size(), "未发生压缩时历史必须保持原样");
    }

    /**
     * 手动压缩失败不得计入自动压缩的熔断预算。
     * <p>
     * 两者的预算不能混：熔断预算衡量的是「自动压缩连续失败了几次」。若手动失败也累加，
     * 用户手点三次不成功就会顺带把该会话的自动压缩永久熔断 —— 而自动压缩此时可能
     * 完全正常（比如失败只是因为手动压缩当时撞上一次限流）。
     */
    @Test
    void failureDoesNotConsumeCircuitBreakerBudget() {
        AutoCompactManager manager = new AutoCompactManager(
                switchableSummarizer(new AtomicBoolean(true)), new TokenTracker());
        List<Message> history = shortHistory();

        CompactionResult result = manager.compactNow(
                () -> List.copyOf(history), replacement -> replaceInPlace(history, replacement));

        assertFalse(result.success(), "前置条件：本用例要求摘要通路失败");
        assertEquals(CompactLayer.MANUAL, result.layer());
        assertEquals(0, manager.getConsecutiveFailures(),
                "手动压缩失败不该累加 consecutiveFailures —— 否则手点几次失败就会"
                        + "把自动压缩的熔断预算耗尽");
        assertFalse(manager.isCircuitBroken(),
                "手动压缩失败不得触发自动压缩的熔断器");
    }
}
