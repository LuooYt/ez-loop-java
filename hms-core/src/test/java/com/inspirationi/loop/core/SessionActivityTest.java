package com.inspirationi.loop.core;

import com.inspirationi.loop.api.SessionActivity;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话运行时活动状态（{@link SessionActivity}）的流转与复位。
 * <p>
 * <b>复位是这里最要紧的不变式</b>：{@code AgentLoop} 是会话级持久对象，一次请求
 * 结束后状态若停在 {@code USING_TOOL} 之类，该会话此后每次查询都会返回这个陈旧值，
 * 界面永久显示「调用工具」。因此正常结束、抛异常、用户取消、撞迭代上限四条出路
 * 都必须回到 {@link SessionActivity#IDLE} —— 由 {@code executeLoop} 的 finally 保证。
 */
class SessionActivityTest {

    /** 按给定分片序列输出的流式 ChatModel。 */
    private static ChatModel streamingModel(List<AssistantMessage> chunks) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                AssistantMessage last = chunks.get(chunks.size() - 1);
                return new ChatResponse(List.of(new Generation(last, ChatGenerationMetadata.NULL)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.fromIterable(chunks)
                        .map(msg -> new ChatResponse(
                                List.of(new Generation(msg, ChatGenerationMetadata.NULL))));
            }

            @Override
            public ChatOptions getOptions() {
                return ChatOptions.builder().model("stub").build();
            }
        };
    }

    /** 阻塞模式的 ChatModel —— 每次 call 返回固定文本。 */
    private static ChatModel blockingModel(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(
                        new Generation(new AssistantMessage(text), ChatGenerationMetadata.NULL)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return ChatOptions.builder().model("stub").build();
            }
        };
    }

    /** 调用即抛的 ChatModel —— 用于验证异常路径的状态复位。 */
    private static ChatModel failingModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("upstream exploded");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new IllegalStateException("upstream exploded"));
            }

            @Override
            public ChatOptions getOptions() {
                return ChatOptions.builder().model("stub").build();
            }
        };
    }

    private static AssistantMessage thinkingChunk(String text) {
        return AssistantMessage.builder()
                .content(text)
                .properties(Map.of("thinking", Boolean.TRUE))
                .build();
    }

    private static AgentLoop newLoop(ChatModel model) {
        return new AgentLoop(model, new ToolRegistry(), ToolContext.defaultContext(), "sys");
    }

    /** 收集活动状态序列的回调集合。 */
    private static AgentLoop.RequestCallbacks collecting(List<SessionActivity> sink) {
        return new AgentLoop.RequestCallbacks(
                null, null, null, null, null,
                (activity, detail) -> sink.add(activity));
    }

    // ==================== 正常流转 ====================

    /**
     * 流式 + extended thinking：思考与作答两个阶段都应被标出。
     * <p>
     * {@code CALLING_MODEL} 覆盖「请求已发出、首个内容未到」这段等待期，收到 thinking
     * 分片升级为 {@code THINKING}，首个正文 token 转 {@code RESPONDING}。
     */
    @Test
    void streamingWithThinkingReportsFullSequence() {
        AgentLoop loop = newLoop(streamingModel(List.of(
                thinkingChunk("先想一下"),
                new AssistantMessage("答案"))));

        List<SessionActivity> seen = new ArrayList<>();
        loop.runStreaming("问题", t -> { }, collecting(seen));

        assertEquals(List.of(SessionActivity.CALLING_MODEL,
                        SessionActivity.THINKING,
                        SessionActivity.RESPONDING,
                        SessionActivity.IDLE),
                seen,
                "应依次经过 等待模型 → 思考 → 作答 → 空闲");
    }

    /**
     * 流式 + 未开 thinking：没有 THINKING 一态，但等待期仍有 CALLING_MODEL 覆盖。
     * <p>
     * 这正是引入 {@code CALLING_MODEL} 的目的 —— 未开 extended thinking 时若只靠
     * thinking 分片标注状态，整段等待期将无状态可显示（界面空白）。
     */
    @Test
    void streamingWithoutThinkingHasNoBlankPeriod() {
        AgentLoop loop = newLoop(streamingModel(List.of(new AssistantMessage("直接回答"))));

        List<SessionActivity> seen = new ArrayList<>();
        loop.runStreaming("问题", t -> { }, collecting(seen));

        assertEquals(List.of(SessionActivity.CALLING_MODEL,
                        SessionActivity.RESPONDING,
                        SessionActivity.IDLE),
                seen,
                "无 thinking 时应为 等待模型 → 作答 → 空闲，中间不留空白");
    }

    /** 阻塞模式同样产出单调递进的序列，前端无需为两种模式分支。 */
    @Test
    void blockingModeReportsSameShapeOfSequence() {
        AgentLoop loop = newLoop(blockingModel("回答"));

        List<SessionActivity> seen = new ArrayList<>();
        loop.run("问题", collecting(seen));

        assertEquals(List.of(SessionActivity.CALLING_MODEL,
                        SessionActivity.RESPONDING,
                        SessionActivity.IDLE),
                seen);
    }

    /** 同一状态连续设置只推送一次 —— 否则多轮迭代会刷出一串重复事件。 */
    @Test
    void repeatedSameActivityIsNotPushedTwice() {
        AgentLoop loop = newLoop(streamingModel(List.of(
                thinkingChunk("第一段"),
                thinkingChunk("第二段"),
                thinkingChunk("第三段"),
                new AssistantMessage("结论"))));

        List<SessionActivity> seen = new ArrayList<>();
        loop.runStreaming("问题", t -> { }, collecting(seen));

        assertEquals(1, seen.stream().filter(a -> a == SessionActivity.THINKING).count(),
                "三个思考分片只应推送一次 THINKING");
    }

    // ==================== 复位（四条出路） ====================

    /** 正常结束后回到空闲。 */
    @Test
    void normalCompletionResetsToIdle() {
        AgentLoop loop = newLoop(blockingModel("回答"));
        loop.run("问题");

        assertEquals(SessionActivity.IDLE, loop.getActivity());
    }

    /**
     * 上游抛异常时也必须复位。
     * <p>
     * 没有 finally 的话状态会停在 {@code CALLING_MODEL}，而本类是会话级持久对象 ——
     * 该会话此后每次查询都返回这个陈旧值，界面永久显示「思考中」。
     */
    @Test
    void upstreamFailureResetsToIdle() {
        AgentLoop loop = newLoop(failingModel());

        assertThrows(RuntimeException.class, () -> loop.run("问题"));
        assertEquals(SessionActivity.IDLE, loop.getActivity(),
                "异常路径同样要回到空闲");
    }

    /** 用户取消后回到空闲。 */
    @Test
    void cancelResetsToIdle() {
        AgentLoop loop = newLoop(blockingModel("回答"));
        loop.cancel();
        loop.run("问题");

        assertEquals(SessionActivity.IDLE, loop.getActivity());
    }

    /** 撞上迭代上限后回到空闲。 */
    @Test
    void maxIterationsResetsToIdle() {
        // maxIterations=1 且模型每轮都请求工具调用 → 必然撞上限
        AssistantMessage withTool = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "NoSuchTool", "{}")))
                .build();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(
                        new Generation(withTool, ChatGenerationMetadata.NULL)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return ChatOptions.builder().model("stub").build();
            }
        };
        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), ToolContext.defaultContext(),
                "sys", new TokenTracker(), 1);

        loop.run("问题");

        assertEquals(SessionActivity.IDLE, loop.getActivity(),
                "撞迭代上限同样要回到空闲");
    }

    /** 初始状态是空闲 —— 新建会话不该显示「正在忙」。 */
    @Test
    void initialActivityIsIdle() {
        assertEquals(SessionActivity.IDLE, newLoop(blockingModel("x")).getActivity());
    }

    /**
     * 工具事件按阶段分发，同一次调用只在 END 阶段带结果。
     * <p>
     * 钉住 {@code ToolEvent.Phase} 不再被抹平 —— 此前 START/PROGRESS/END 三类事件
     * 在 {@code DefaultHmsSessionManager} 里被同等对待，导致工具用量统计翻几倍、
     * 前端也重复渲染同一次调用。
     */
    @Test
    void toolEventsCarryDistinctPhases() {
        AssistantMessage withTool = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "NoSuchTool", "{}")))
                .build();
        // 首轮请求工具、次轮给文本，避免撞迭代上限
        List<AssistantMessage> replies = List.of(withTool, new AssistantMessage("好了"));
        int[] round = {0};
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                AssistantMessage msg = replies.get(Math.min(round[0]++, replies.size() - 1));
                return new ChatResponse(List.of(new Generation(msg, ChatGenerationMetadata.NULL)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return ChatOptions.builder().model("stub").build();
            }
        };

        List<AgentLoop.ToolEvent.Phase> phases = new ArrayList<>();
        var callbacks = new AgentLoop.RequestCallbacks(
                event -> phases.add(event.phase()), null, null, null);
        newLoop(model).run("问题", callbacks);

        assertEquals(1, phases.stream().filter(p -> p == AgentLoop.ToolEvent.Phase.START).count(),
                "一次工具调用只应有一个 START");
        assertEquals(1, phases.stream().filter(p -> p == AgentLoop.ToolEvent.Phase.END).count(),
                "一次工具调用只应有一个 END —— 用量统计要挂在这里");
    }

    /** 观测回调抛异常不影响主流程 —— SSE 连接随时可能已断开。 */
    @Test
    void failingActivityCallbackDoesNotBreakTheLoop() {
        AgentLoop loop = newLoop(blockingModel("回答"));
        var callbacks = new AgentLoop.RequestCallbacks(
                null, null, null, null, null,
                (activity, detail) -> { throw new RuntimeException("sink is gone"); });

        String result = loop.run("问题", callbacks);

        assertEquals("回答", result, "回调失败不应影响返回值");
        assertTrue(loop.getActivity() == SessionActivity.IDLE,
                "回调在 finally 里抛出也不能妨碍复位");
    }
}
