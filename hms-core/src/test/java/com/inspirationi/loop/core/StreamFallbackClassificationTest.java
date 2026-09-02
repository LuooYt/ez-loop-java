package com.inspirationi.loop.core;

import com.inspirationi.loop.api.HmsErrorCode;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.util.UpstreamErrors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式失败后的降级必须按错误类型区分。
 * <p>
 * {@code streamIteration} 原先 {@code catch (Exception)} 后无条件降级到阻塞模式。
 * 对 403（凭证/权限被拒）这类<b>必然复现</b>的错误，重试一次只是白发一个请求、
 * 把故障暴露时间拖长一倍，还让同一个异常在日志里打两遍完整堆栈 —— 生产日志里
 * 一次 403 会刷出四段几乎相同的 stack trace，掩盖真正的信息。
 */
class StreamFallbackClassificationTest {

    /** 流式抛指定异常；阻塞调用单独计数，用于判断是否发生了降级。 */
    private static final class FailingStreamModel implements ChatModel {
        final AtomicInteger streamCalls = new AtomicInteger();
        final AtomicInteger blockingCalls = new AtomicInteger();
        private final RuntimeException failure;

        FailingStreamModel(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            blockingCalls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("blocking fallback"), ChatGenerationMetadata.NULL)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCalls.incrementAndGet();
            return Flux.error(failure);
        }

        @Override
        public ChatOptions getOptions() {
            return null;
        }
    }

    private static AgentLoop newLoop(ChatModel model) {
        return new AgentLoop(model, new ToolRegistry(), ToolContext.defaultContext(),
                "sys", new TokenTracker());
    }

    /** 403 不该降级 —— 阻塞调用必然同样被拒。 */
    @Test
    void forbiddenDoesNotFallBackToBlocking() {
        FailingStreamModel model = new FailingStreamModel(new RuntimeException(
                "403: {\"error\":{\"type\":\"forbidden\",\"message\":\"Request not allowed\"}}"));
        AgentLoop loop = newLoop(model);

        assertThrows(RuntimeException.class, () -> loop.runStreaming("hi", token -> { }),
                "不可重试的错误应直接抛出，而非静默降级");

        assertEquals(1, model.streamCalls.get(), "流式应只尝试一次");
        assertEquals(0, model.blockingCalls.get(),
                "403 必然复现，不得再发一次阻塞请求 —— 那只是把故障暴露时间拖长一倍");
    }

    /** 401 同理（凭证无效）。 */
    @Test
    void unauthorizedDoesNotFallBackToBlocking() {
        FailingStreamModel model = new FailingStreamModel(
                new RuntimeException("401: invalid api key"));
        AgentLoop loop = newLoop(model);

        assertThrows(RuntimeException.class, () -> loop.runStreaming("hi", token -> { }));
        assertEquals(0, model.blockingCalls.get(), "401 不得触发降级重试");
    }

    /**
     * 网络类失败仍应降级 —— 这是降级机制存在的理由，不能一并砍掉。
     */
    @Test
    void transientFailureStillFallsBackToBlocking() {
        FailingStreamModel model = new FailingStreamModel(
                new RuntimeException("Connection reset by peer"));
        AgentLoop loop = newLoop(model);

        String result = loop.runStreaming("hi", token -> { });

        assertEquals(1, model.blockingCalls.get(),
                "识别不出的失败应保留降级兜底，否则本可成功的调用会直接失败");
        assertTrue(result.contains("blocking fallback"), "应返回阻塞调用的结果");
    }

    /** 429 属于暂时性限流，重试有意义，应当降级。 */
    @Test
    void rateLimitStillFallsBackToBlocking() {
        FailingStreamModel model = new FailingStreamModel(
                new RuntimeException("429: rate limit exceeded"));
        AgentLoop loop = newLoop(model);

        loop.runStreaming("hi", token -> { });

        assertEquals(1, model.blockingCalls.get(), "429 是暂时的，应保留重试机会");
    }

    /** 5xx 可能只影响流式端点，应当降级。 */
    @Test
    void serverErrorStillFallsBackToBlocking() {
        FailingStreamModel model = new FailingStreamModel(
                new RuntimeException("503: service unavailable"));
        AgentLoop loop = newLoop(model);

        loop.runStreaming("hi", token -> { });

        assertEquals(1, model.blockingCalls.get(),
                "5xx 可能只影响流式端点，阻塞调用仍有机会成功");
    }

    /** 状态码藏在 cause 链深处也要能识别 —— Reactor 会包装原始异常。 */
    @Test
    void statusCodeInCauseChainIsDetected() {
        RuntimeException root = new RuntimeException("403: forbidden");
        RuntimeException wrapped = new RuntimeException("#block terminated with an error",
                new IllegalStateException("reactive error", root));
        FailingStreamModel model = new FailingStreamModel(wrapped);
        AgentLoop loop = newLoop(model);

        assertThrows(RuntimeException.class, () -> loop.runStreaming("hi", token -> { }));
        assertEquals(0, model.blockingCalls.get(),
                "Reactor 包装后的 403 同样不该降级");
    }

    // ==================== 状态码匹配的边界 ====================

    /** 数字相邻时不得误命中 —— token 数、耗时里的数字很容易撞上。 */
    @Test
    void adjacentDigitsDoNotFalselyMatch() {
        assertFalse(UpstreamErrors.hasStatus("used 1403 tokens", 403),
                "\"1403\" 里的 403 不是状态码");
        assertFalse(UpstreamErrors.hasStatus("took 4030 ms", 403),
                "\"4030\" 里的 403 不是状态码");
        assertTrue(UpstreamErrors.hasStatus("403: forbidden", 403),
                "独立出现的 403 应被识别");
        assertTrue(UpstreamErrors.hasStatus("status code 403", 403),
                "行末的 403 应被识别");
    }

    /** 自引用的 cause 链不得死循环。 */
    @Test
    void selfReferencingCauseDoesNotLoop() {
        RuntimeException selfRef = new RuntimeException("boom") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(UpstreamErrors.isNonRetryable(selfRef), "应正常返回而非死循环");
    }

    // ==================== 错误码归类 ====================

    /** 403 / 401 归为认证失败 —— 此前该错误码从未被赋予过。 */
    @Test
    void authFailuresAreClassified() {
        assertEquals(HmsErrorCode.AI_AUTH_FAILED,
                HmsErrorCode.classifyUpstream(new RuntimeException("403: forbidden")));
        assertEquals(HmsErrorCode.AI_AUTH_FAILED,
                HmsErrorCode.classifyUpstream(new RuntimeException("401: unauthorized")));
    }

    /** 429 归为配额超限。 */
    @Test
    void quotaFailureIsClassified() {
        assertEquals(HmsErrorCode.AI_QUOTA_EXCEEDED,
                HmsErrorCode.classifyUpstream(new RuntimeException("429: too many requests")));
    }

    /** 认不出来的归为通用调用失败，而不是猜一个具体原因。 */
    @Test
    void unknownFailureFallsBackToGenericCode() {
        assertEquals(HmsErrorCode.AI_CALL_FAILED,
                HmsErrorCode.classifyUpstream(new RuntimeException("something odd")));
        assertEquals(HmsErrorCode.AI_CALL_FAILED,
                HmsErrorCode.classifyUpstream(null));
    }
}
