package com.inspirationi.loop.core;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息历史的多步改动必须整体原子。
 * <p>
 * {@code messageHistory} 是 {@code synchronizedList}，只保证单次调用原子。
 * {@code replaceHistory} 的 clear + addAll、{@code reset} 的 clear + add、
 * {@code updateSystemPrompt} 的 isEmpty + set(0) 都是多步操作，步骤之间锁会释放：
 * 并发读者（{@link AgentLoop#copyMessageHistory()}，会话 API 的 getSessionMessages
 * 走同一入口）会看到空历史。压缩在每轮工具调用后调 replaceHistory，读侧不持会话锁，
 * 因此该窗口真实可达 —— 表现为 GET 消息列表偶发返回空数组。
 */
class HistoryMutationAtomicityTest {

    private static ToolRegistry noopRegistry() {
        ToolRegistry r = new ToolRegistry();
        r.register(new Tool() {
            @Override public String name() { return "Noop"; }
            @Override public String description() { return "does nothing"; }
            @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public String execute(Map<String, Object> in, ToolContext c) { return "ok"; }
        });
        return r;
    }

    private static AgentLoop newLoop() {
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("x"), ChatGenerationMetadata.NULL)));
            }
            @Override public ChatOptions getOptions() { return null; }
        };
        return new AgentLoop(model, noopRegistry(), ToolContext.defaultContext(),
                "SYSTEM", new TokenTracker());
    }

    /** 压缩产出规模的替换列表 —— 足够大才能让 addAll 有可观察的时间窗。 */
    private static List<Message> compactedHistory() {
        List<Message> out = new ArrayList<>();
        out.add(new SystemMessage("SYSTEM"));
        for (int i = 0; i < 2000; i++) {
            out.add(new AssistantMessage("summary " + i));
        }
        return out;
    }

    /**
     * 并发读者在 replaceHistory 期间不得观察到空历史或首条非系统消息。
     */
    @Test
    void replaceHistoryIsAtomicForConcurrentReaders() throws Exception {
        AgentLoop loop = newLoop();
        loop.run("seed");
        List<Message> compacted = compactedHistory();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger empty = new AtomicInteger();
        AtomicInteger noSystem = new AtomicInteger();

        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                List<Message> snap = loop.copyMessageHistory();
                reads.incrementAndGet();
                if (snap.isEmpty()) {
                    empty.incrementAndGet();
                } else if (snap.get(0).getMessageType() != MessageType.SYSTEM) {
                    noSystem.incrementAndGet();
                }
            }
        });
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 400; i++) {
                loop.replaceHistory(compacted);
            }
            stop.set(true);
        });

        reader.start();
        writer.start();
        writer.join(60_000);
        stop.set(true);
        reader.join(5_000);

        assertTrue(reads.get() > 100, "读者应完成足量采样，实际 " + reads.get());
        assertEquals(0, empty.get(),
                "replaceHistory 期间读到空历史 " + empty.get() + "/" + reads.get() + " 次");
        assertEquals(0, noSystem.get(),
                "replaceHistory 期间读到首条非系统消息 " + noSystem.get() + " 次");
    }

    /** reset 的 clear + add 之间同样不得暴露空历史。 */
    @Test
    void resetIsAtomicForConcurrentReaders() throws Exception {
        AgentLoop loop = newLoop();
        loop.run("seed");

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger empty = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();

        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                if (loop.copyMessageHistory().isEmpty()) {
                    empty.incrementAndGet();
                }
                reads.incrementAndGet();
            }
        });
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) {
                loop.reset();
            }
            stop.set(true);
        });

        reader.start();
        writer.start();
        writer.join(60_000);
        stop.set(true);
        reader.join(5_000);

        assertEquals(0, empty.get(),
                "reset 期间读到空历史 " + empty.get() + "/" + reads.get() + " 次");
    }

    /**
     * updateSystemPrompt 的 isEmpty + set(0) 是 check-then-act：与清空历史的操作
     * 并发时，若不整体加锁会抛 IndexOutOfBoundsException。
     */
    @Test
    void updateSystemPromptSurvivesConcurrentClearing() throws Exception {
        AgentLoop loop = newLoop();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);

        for (int t = 0; t < 4; t++) {
            final int id = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 20_000; i++) {
                        if (id % 2 == 0) {
                            loop.updateSystemPrompt("P" + i);
                        } else {
                            loop.replaceHistory(List.of());
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "线程未在超时内结束");

        assertNull(failure.get(),
                "updateSystemPrompt 与清空历史并发时抛异常: " + failure.get());
    }
}
