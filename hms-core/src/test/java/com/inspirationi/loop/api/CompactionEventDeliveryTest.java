package com.inspirationi.loop.api;

import com.inspirationi.loop.core.compact.CompactionResult;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩事件必须真正送达使用方。
 * <p>
 * {@code AutoCompactManager} 一直备有 {@code setOnCompactionEvent}，但整条链路上
 * 无人注册：实例由会话管理器在内部创建并绑到 {@code AgentLoop}，而
 * {@link HmsSessionManager} 不对外暴露 AgentLoop，SDK 使用方拿不到它 ——
 * {@code notifyEvent} 因此全程空转。压缩会静默摘要掉旧消息，使用方只能观察到
 * 「AI 忘了早前的细节」，却收不到任何信号。
 * <p>
 * 修法是让它走 {@link HmsCallbacks} / {@link HmsEvent} 这条既有通路，而非新开
 * 一套 setter：压缩与 token、工具调用、thinking 一样，都是执行过程中发生的事。
 */
class CompactionEventDeliveryTest {

    /**
     * 每轮调一次工具（驱动压缩检查），用量分两段报告。
     * <p>
     * 前 {@code lowUsageRounds} 轮报低用量：压缩检查走「未达阈值」分支，只做
     * 无事件的微压缩，从而攒够 tool 结果 —— 微压缩保留最近 6 条完整内容，
     * 攒不够就无可裁剪、返回 noAction，测不到成功路径。此后各轮顶到阈值以上，
     * 让微压缩真正裁剪并发出成功事件。
     *
     * @param lowUsageRounds 前几轮报低用量（用于积累 tool 结果）
     * @param stopAfter      第几轮后停止发工具调用，避免无限循环
     * @param highTokens     越过自动压缩阈值的 promptTokens
     */
    private static ChatModel toolLoopingModel(AtomicInteger calls, int lowUsageRounds,
                                             int stopAfter, long highTokens) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int n = calls.incrementAndGet();
                AssistantMessage msg = (n <= stopAfter)
                        ? AssistantMessage.builder()
                                .content("working")
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "c" + n, "function", "Noop", "{}")))
                                .build()
                        : new AssistantMessage("done");
                long promptTokens = (n <= lowUsageRounds) ? 1_000 : highTokens;
                ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                        .usage(new DefaultUsage((int) promptTokens, 10))
                        .build();
                return new ChatResponse(
                        List.of(new Generation(msg, ChatGenerationMetadata.NULL)), metadata);
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

    /** 产出足够长结果的工具 —— 让微压缩确有可裁剪的内容。 */
    private static ToolRegistry registryWithNoisyTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.inspirationi.loop.tool.Tool() {
            @Override
            public String name() {
                return "Noop";
            }

            @Override
            public String description() {
                return "returns bulk text";
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\",\"properties\":{}}";
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public String execute(java.util.Map<String, Object> input,
                                  com.inspirationi.loop.tool.ToolContext context) {
                return "R".repeat(3000);   // 远超微压缩的 200 字符截断阈值
            }
        });
        return registry;
    }

    private static DefaultHmsSessionManager newManager(ChatModel model, ToolRegistry registry) {
        return DefaultHmsSessionManager.builder(
                        model, registry, new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(10)
                .build();
    }

    /**
     * 覆写 {@code onCompaction} 的使用方必须收到压缩通知。
     * <p>
     * 这是缺陷的核心：修复前该回调无处可注册，压缩发生了也没人知道。
     */
    @Test
    void compactionReachesCallbacks() {
        // promptTokens 顶到有效窗口的 95%，跨过 93% 的自动压缩阈值。
        // 轮次要多于微压缩保留的最近 6 条 tool 结果，否则无可裁剪内容、
        // 微压缩返回 noAction，测不到成功路径。
        long effectiveWindow = 200_000 - 20_000;
        AtomicInteger calls = new AtomicInteger();
        List<CompactionResult> received = new ArrayList<>();

        try (DefaultHmsSessionManager manager =
                     newManager(toolLoopingModel(calls, 8, 12, (long) (effectiveWindow * 0.95)),
                             registryWithNoisyTool())) {
            String sessionId = manager.createSession("s");

            manager.send(sessionId, "go", new HmsCallbacks() {
                @Override
                public void onCompaction(CompactionResult result) {
                    received.add(result);
                }
            });

            assertFalse(received.isEmpty(),
                    "上下文被压缩后，覆写 onCompaction 的使用方必须收到通知。"
                            + "收不到意味着压缩仍在静默改写历史 —— 使用方只能观察到"
                            + "「AI 忘了早前的细节」，拿不到任何结构化信号");

            // 每条事件都应是可用的结构化数据，而非空壳
            for (CompactionResult result : received) {
                assertNotNull(result.layer(), "压缩事件必须带上层级");
                assertNotNull(result.reason(), "压缩事件必须带上原因描述");
            }

            // 微压缩是纯本地操作，桩模型下必然成功且必然先到 —— 它证明送达的
            // 不只是「熔断」这类失败通知，成功路径同样接通了。
            assertTrue(received.stream().anyMatch(r -> r.success()
                            && r.layer() == CompactionResult.CompactLayer.MICRO),
                    "应收到一条成功的微压缩事件，实际收到: " + received);
        }
    }

    /** 未覆写 onCompaction 时不得抛异常 —— 回调为 null 应退化为空操作。 */
    @Test
    void missingCallbackIsHarmless() {
        long effectiveWindow = 200_000 - 20_000;
        AtomicInteger calls = new AtomicInteger();

        try (DefaultHmsSessionManager manager =
                     newManager(toolLoopingModel(calls, 8, 12, (long) (effectiveWindow * 0.95)),
                             registryWithNoisyTool())) {
            String sessionId = manager.createSession("s");
            // 默认实现是空方法，全程不应抛出
            HmsResponse response = manager.send(sessionId, "go", new HmsCallbacks() {});
            assertNotNull(response, "未覆写压缩回调不应影响正常返回");
        }
    }

    /**
     * 压缩事件能桥成 {@link HmsEvent.Compaction} —— SSE 等传输零改动即可收到。
     */
    @Test
    void compactionIsBridgedToEvent() {
        List<HmsEvent> events = new ArrayList<>();
        PendingResponses pending = new PendingResponses(1);
        HmsCallbacks bridge = new EventBridgeCallbacks(events::add, pending, "sid");

        bridge.onCompaction(CompactionResult.success(
                CompactionResult.CompactLayer.SESSION_MEMORY, 40, 12, "summarized"));

        assertEquals(1, events.size(), "应推出一个事件");
        HmsEvent.Compaction event = (HmsEvent.Compaction) events.getFirst();
        assertEquals("compaction", event.eventName(), "事件名是对外契约");
        assertEquals("SESSION_MEMORY", event.layer());
        assertEquals(40, event.messagesBefore());
        assertEquals(12, event.messagesAfter());
    }
}
