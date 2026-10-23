package com.inspirationi.loop.core;

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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消必须传下去：子 Agent 是在父循环的一次工具调用内部<b>同步</b>执行的，
 * 父循环此时正阻塞在工具里，根本走不到自己的取消检查点。
 * <p>
 * 曾经的缺陷：{@code cancel()} 只翻转父循环自己的标志，子 Agent 收不到任何信号。
 * 用户点了取消、界面也显示已中断，后台那个子 Agent 却继续跑满 50 轮迭代 ——
 * 一个「涵盖所有工具」的任务要等它自己耗尽才会停。
 */
class ChildLoopCancelTest {

    /** 只在首轮阻塞的模型：其余轮返回工具调用，未取消时会跑满迭代上限。 */
    private static class BlockingToolCallModel implements ChatModel {
        private static final long MAX_BLOCK_MILLIS = 5000;

        final CountDownLatch inCall = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean firstCall = calls.incrementAndGet() == 1;
            inCall.countDown();
            if (firstCall) {
                try {
                    proceed.await(MAX_BLOCK_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            AssistantMessage msg = AssistantMessage.builder()
                    .content("thinking")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-" + calls.get(), "function", "Ghost", "{}")))
                    .build();
            return new ChatResponse(List.of(new Generation(msg, ChatGenerationMetadata.NULL)));
        }

        @Override
        public ChatOptions getOptions() {
            return null;
        }
    }

    /** 一次就收尾的模型 —— 用来验证「未登记的子循环不受影响」。 */
    private static class FinalAnswerModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("done"), ChatGenerationMetadata.NULL)));
        }

        @Override
        public ChatOptions getOptions() {
            return null;
        }
    }

    private static AgentLoop newLoop(ChatModel model) {
        return new AgentLoop(model, new ToolRegistry(), ToolContext.defaultContext(), "sys");
    }

    @Test
    void parentCancelStopsARunningChild() throws Exception {
        AgentLoop parent = newLoop(new FinalAnswerModel());
        BlockingToolCallModel childModel = new BlockingToolCallModel();
        AgentLoop child = newLoop(childModel);

        parent.registerChild(child);
        Thread childThread = Thread.ofVirtual().start(() -> child.run("sub task"));
        assertTrue(childModel.inCall.await(5, TimeUnit.SECONDS), "子循环应已开始执行");

        // 父循环此刻正卡在「Agent 工具」里，取消它必须能穿透到子循环
        parent.cancel();
        childModel.proceed.countDown();

        assertTrue(waitForExit(childThread, 10_000),
                "父循环取消后，子循环必须尽快退出，实际调用模型 " + childModel.calls.get() + " 次");
        assertTrue(child.wasLastRunInterrupted(), "子循环应记录「被中断」");
        assertTrue(childModel.calls.get() < AgentLoop.DEFAULT_MAX_ITERATIONS,
                "子循环不应跑满迭代上限");
    }

    @Test
    void unregisteredChildIsNotAffected() throws Exception {
        AgentLoop parent = newLoop(new FinalAnswerModel());
        AgentLoop child = newLoop(new FinalAnswerModel());

        // 登记后立刻摘除（子 Agent 执行完毕的清理路径）—— 此后的取消不应波及它
        parent.registerChild(child);
        parent.unregisterChild(child);
        parent.cancel();

        String result = child.run("next task");
        assertFalse(child.wasLastRunInterrupted(),
                "已摘除的子循环不应受父循环取消影响，结果=" + result);
    }

    private static boolean waitForExit(Thread thread, long millis) throws InterruptedException {
        thread.join(millis);
        return !thread.isAlive();
    }
}
