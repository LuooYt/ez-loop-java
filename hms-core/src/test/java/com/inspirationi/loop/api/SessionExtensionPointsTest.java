package com.inspirationi.loop.api;

import com.inspirationi.loop.core.HookManager;
import com.inspirationi.loop.core.HookManager.HookResult;
import com.inspirationi.loop.core.HookManager.HookType;
import com.inspirationi.loop.permission.DenialTracker;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级扩展点必须真正可用。
 * <p>
 * {@code HookManager} 与 {@code DenialTracker} 长期存在却对外不可达：两者都挂在
 * {@code AgentLoop} 上，而 {@code HmsSessionManager} 不暴露它，
 * {@code getSessionInternal} 是包私有。于是 {@code register} /
 * {@code addDenialCallback} 全无调用点 —— 工具拦截与权限审计这两项能力等于没开放。
 * <p>
 * 本测试不满足于「getter 存在」，而是验证拦截、改写、观测确实生效。
 */
class SessionExtensionPointsTest {

    /** 记录工具实际收到的参数，供断言「PRE 阶段改参数」是否生效。 */
    private static final class RecordingTool implements Tool {
        final List<Map<String, Object>> received = new ArrayList<>();
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public String name() {
            return "Recorder";
        }

        @Override
        public String description() {
            return "records its arguments";
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public String execute(Map<String, Object> input, ToolContext context) {
            received.add(Map.copyOf(input));
            executions.incrementAndGet();
            return "ORIGINAL_RESULT";
        }
    }

    /** 第一轮调一次工具，之后返回文本收尾。 */
    private static ChatModel callsToolOnce(String argsJson) {
        AtomicInteger n = new AtomicInteger();
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                AssistantMessage msg = (n.incrementAndGet() == 1)
                        ? AssistantMessage.builder()
                                .content("")
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "c1", "function", "Recorder", argsJson)))
                                .build()
                        : new AssistantMessage("done");
                return new ChatResponse(List.of(new Generation(msg, ChatGenerationMetadata.NULL)));
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

    private static DefaultHmsSessionManager newManager(ChatModel model, Tool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return DefaultHmsSessionManager.builder(
                        model, registry, new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(10)
                .build();
    }

    // ==================== HookManager ====================

    /** PRE_TOOL_USE 返回 ABORT 必须真正阻止工具执行。 */
    @Test
    void preHookCanBlockToolExecution() {
        RecordingTool tool = new RecordingTool();
        try (DefaultHmsSessionManager manager =
                     newManager(callsToolOnce("{\"path\":\"/prod/secrets\"}"), tool)) {
            String sessionId = manager.createSession("s");

            manager.getSessionHooks(sessionId).register(
                    HookType.PRE_TOOL_USE, "block-prod", ctx ->
                            String.valueOf(ctx.getArguments().get("path")).startsWith("/prod/")
                                    ? HookResult.ABORT : HookResult.CONTINUE);

            manager.send(sessionId, "go");

            assertEquals(0, tool.executions.get(),
                    "PRE_TOOL_USE 返回 ABORT 后工具不得执行 —— 这是拦截能力的根本");
        }
    }

    /** PRE_TOOL_USE 原地改写参数必须影响工具真正收到的入参。 */
    @Test
    void preHookCanRewriteArguments() {
        RecordingTool tool = new RecordingTool();
        try (DefaultHmsSessionManager manager =
                     newManager(callsToolOnce("{\"path\":\"/prod/db\"}"), tool)) {
            String sessionId = manager.createSession("s");

            manager.getSessionHooks(sessionId).register(
                    HookType.PRE_TOOL_USE, "redirect-to-sandbox", ctx -> {
                        ctx.getArguments().put("path", "/sandbox/db");
                        return HookResult.CONTINUE;
                    });

            manager.send(sessionId, "go");

            assertEquals(1, tool.executions.get(), "工具应已执行");
            assertEquals("/sandbox/db", tool.received.getFirst().get("path"),
                    "PRE 阶段原地改写的参数必须是工具实际收到的那份");
        }
    }

    /** POST_TOOL_USE 改写结果必须影响回传给模型的内容。 */
    @Test
    void postHookCanRewriteResult() {
        RecordingTool tool = new RecordingTool();
        List<String> toolResults = new ArrayList<>();

        try (DefaultHmsSessionManager manager =
                     newManager(callsToolOnce("{\"path\":\"/tmp/x\"}"), tool)) {
            String sessionId = manager.createSession("s");

            manager.getSessionHooks(sessionId).register(
                    HookType.POST_TOOL_USE, "redact", ctx -> {
                        ctx.setResult("[REDACTED]");
                        return HookResult.CONTINUE;
                    });

            manager.send(sessionId, "go", new HmsCallbacks() {
                @Override
                public void onToolUse(String toolName, String input, String result) {
                    // onToolUse 在 START / END 两阶段都会触发，START 时 result 为 null，
                    // 只有 END 阶段带着最终结果 —— 那才是回传给模型的内容。
                    if (result != null) {
                        toolResults.add(result);
                    }
                }
            });

            assertEquals(1, tool.executions.get(), "工具应已执行，产出原始结果");
            assertFalse(toolResults.isEmpty(), "应收到带结果的工具使用通知");
            assertEquals("[REDACTED]", toolResults.getFirst(),
                    "POST_TOOL_USE 改写后的结果才是回传给模型的内容，"
                            + "工具原始输出 ORIGINAL_RESULT 不应外泄");
        }
    }

    /** 钩子按优先级升序执行。 */
    @Test
    void hooksRunInPriorityOrder() {
        RecordingTool tool = new RecordingTool();
        List<String> order = new ArrayList<>();

        try (DefaultHmsSessionManager manager =
                     newManager(callsToolOnce("{\"path\":\"/tmp/x\"}"), tool)) {
            String sessionId = manager.createSession("s");
            HookManager hooks = manager.getSessionHooks(sessionId);

            hooks.register(HookType.PRE_TOOL_USE, "late", ctx -> {
                order.add("late");
                return HookResult.CONTINUE;
            }, 10);
            hooks.register(HookType.PRE_TOOL_USE, "early", ctx -> {
                order.add("early");
                return HookResult.CONTINUE;
            }, 1);

            manager.send(sessionId, "go");

            assertEquals(List.of("early", "late"), order, "优先级数字小者先执行");
        }
    }

    /** 钩子是会话级隔离的 —— 一个会话的钩子不应影响另一个。 */
    @Test
    void hooksAreIsolatedPerSession() {
        RecordingTool tool = new RecordingTool();
        try (DefaultHmsSessionManager manager =
                     newManager(callsToolOnce("{\"path\":\"/tmp/x\"}"), tool)) {
            String blocked = manager.createSession("blocked");
            String allowed = manager.createSession("allowed");

            manager.getSessionHooks(blocked).register(
                    HookType.PRE_TOOL_USE, "block-all", ctx -> HookResult.ABORT);

            assertNotNull(manager.getSessionHooks(allowed));
            manager.send(allowed, "go");

            assertEquals(1, tool.executions.get(),
                    "另一个会话注册的 ABORT 钩子不得影响本会话");
        }
    }

    /** 同一会话多次取用应是同一个实例，否则注册会丢。 */
    @Test
    void sameSessionReturnsSameHookManager() {
        RecordingTool tool = new RecordingTool();
        try (DefaultHmsSessionManager manager =
                     newManager(callsToolOnce("{}"), tool)) {
            String sessionId = manager.createSession("s");
            assertSame(manager.getSessionHooks(sessionId), manager.getSessionHooks(sessionId),
                    "必须返回同一实例，否则先注册的钩子会丢失");
        }
    }

    // ==================== DenialTracker ====================

    /** 拒绝追踪器可达，且回调在越过阈值时被触发。 */
    @Test
    void denialCallbackFiresOnThreshold() {
        RecordingTool tool = new RecordingTool();
        try (DefaultHmsSessionManager manager = newManager(callsToolOnce("{}"), tool)) {
            String sessionId = manager.createSession("s");
            DenialTracker tracker = manager.getSessionDenials(sessionId);

            List<int[]> fired = new ArrayList<>();
            tracker.addDenialCallback((consecutive, total) -> fired.add(new int[]{consecutive, total}));

            // 连续拒绝到阈值（MAX_CONSECUTIVE_DENIALS = 3）
            for (int i = 0; i < DenialTracker.MAX_CONSECUTIVE_DENIALS; i++) {
                tracker.recordDenial();
            }

            assertFalse(fired.isEmpty(),
                    "连续拒绝达阈值后必须触发回调 —— 此前该回调无处注册，"
                            + "权限拒绝审计能力等于没开放");
            int[] last = fired.getLast();
            assertEquals(DenialTracker.MAX_CONSECUTIVE_DENIALS, last[0], "应报告连续拒绝次数");
            assertTrue(last[1] >= DenialTracker.MAX_CONSECUTIVE_DENIALS, "应报告累计拒绝次数");
        }
    }

    /** 拒绝追踪器同样是会话级隔离的。 */
    @Test
    void denialTrackersAreIsolatedPerSession() {
        RecordingTool tool = new RecordingTool();
        try (DefaultHmsSessionManager manager = newManager(callsToolOnce("{}"), tool)) {
            String a = manager.createSession("a");
            String b = manager.createSession("b");

            manager.getSessionDenials(a).recordDenial();

            assertEquals(1, manager.getSessionDenials(a).getTotalDenials());
            assertEquals(0, manager.getSessionDenials(b).getTotalDenials(),
                    "一个会话的拒绝计数不得串到另一个会话");
        }
    }

    // ==================== 边界 ====================

    /** 会话不存在时两个扩展点都应明确报错，而非返回 null 让调用方 NPE。 */
    @Test
    void unknownSessionThrows() {
        RecordingTool tool = new RecordingTool();
        try (DefaultHmsSessionManager manager = newManager(callsToolOnce("{}"), tool)) {
            assertThrows(IllegalArgumentException.class,
                    () -> manager.getSessionHooks("no-such-session"));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.getSessionDenials("no-such-session"));
        }
    }

    /** 暂停中的会话仍可调整扩展点 —— 注册钩子不是一次「发消息」。 */
    @Test
    void pausedSessionStillExposesExtensionPoints() {
        RecordingTool tool = new RecordingTool();
        try (DefaultHmsSessionManager manager = newManager(callsToolOnce("{}"), tool)) {
            String sessionId = manager.createSession("s");
            manager.pauseSession(sessionId);

            assertNotNull(manager.getSessionHooks(sessionId),
                    "暂停中的会话应允许调整钩子（恢复后生效）");
            assertNotNull(manager.getSessionDenials(sessionId),
                    "暂停中的会话应允许查询拒绝计数");
        }
    }
}
