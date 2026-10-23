package com.inspirationi.loop.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inspirationi.loop.api.HmsCallbacks;
import com.inspirationi.loop.api.HmsSessionManager;
import com.inspirationi.loop.api.PendingResponses;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 连接与「一轮执行」必须一一对应。
 * <p>
 * 旧实现把事件推送到 <b>按 sessionId 现查</b> 的发射器上。用户刷新页面时，
 * 新连接会顶掉旧连接，但上一轮仍在执行 —— 它之后产生的 token、工具事件、
 * 甚至权限确认请求，全部落到新连接上。前端看到的是两轮回答混在一起，
 * 还可能对一条早已作废的工具调用点「允许」。
 * <p>
 * 这些用例把「两条连接同时存在」这一刻固定下来，断言旧的执行不再影响新的连接。
 */
class SseEmitterIsolationTest {

    private static final String SESSION = "s1";

    /** 记录事件与完成状态，不依赖真实 Servlet 环境。 */
    static final class RecordingEmitter extends SseEmitter {

        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void send(SseEventBuilder builder) {
            events.add("event");
        }

        @Override
        public void complete() {
            completed.set(true);
        }
    }

    /** 把内部构造的发射器换成可记录的实现。 */
    static final class TestBridge extends HmsSseBridge {

        final List<RecordingEmitter> created = new CopyOnWriteArrayList<>();

        TestBridge(HmsSessionManager manager, PendingResponses pending, ExecutorService executor) {
            super(manager, pending, executor, new ObjectMapper(), 60_000);
        }

        @Override
        protected SseEmitter createEmitter() {
            RecordingEmitter emitter = new RecordingEmitter();
            created.add(emitter);
            return emitter;
        }
    }

    /** 只实现 bridge 用到的两个方法（sessionExists / send）的会话管理器替身。 */
    private final BlockingQueue<HmsCallbacks> handedOut = new LinkedBlockingQueue<>();

    /** 首次 send 的闸门：让第一轮「卡在执行中」，便于制造连接顶替。 */
    private volatile CountDownLatch firstSendGate;

    private final AtomicInteger sendCount = new AtomicInteger();

    private HmsSessionManager fakeManager() {
        return (HmsSessionManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{HmsSessionManager.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "FakeHmsSessionManager";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return switch (method.getName()) {
                        case "sessionExists" -> true;
                        case "send" -> {
                            if (args != null && args.length == 3) {
                                handedOut.add((HmsCallbacks) args[2]);
                            }
                            CountDownLatch gate = firstSendGate;
                            if (gate != null && sendCount.incrementAndGet() == 1) {
                                gate.await(5, TimeUnit.SECONDS);
                            }
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    @Test
    void supersededRunDoesNotPolluteTheNewConnection() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        TestBridge bridge = new TestBridge(fakeManager(), new PendingResponses(5), executor);
        try {
            bridge.stream(SESSION, "第一轮");
            HmsCallbacks firstRun = handedOut.poll(5, TimeUnit.SECONDS);
            assertNotNull(firstRun, "第一轮应已开始执行");

            bridge.stream(SESSION, "第二轮");
            HmsCallbacks secondRun = handedOut.poll(5, TimeUnit.SECONDS);
            assertNotNull(secondRun, "第二轮应已开始执行");

            RecordingEmitter firstEmitter = bridge.created.get(0);
            RecordingEmitter secondEmitter = bridge.created.get(1);
            assertTrue(firstEmitter.completed.get(), "被顶替的旧连接应已关闭");

            // 此刻旧一轮才产生事件 —— 正是刷新页面后仍在跑的那一轮
            firstRun.onToken("旧一轮的残留输出");
            secondRun.onToken("新一轮的输出");

            assertEquals(0, firstEmitter.events.size(), "旧连接已被顶替，不应再有事件");
            assertEquals(1, secondEmitter.events.size(),
                    "新连接只应收到自己那一轮的事件，实际收到 " + secondEmitter.events);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releaseStopsInFlightRun() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        TestBridge bridge = new TestBridge(fakeManager(), new PendingResponses(5), executor);
        try {
            bridge.stream(SESSION, "hi");
            HmsCallbacks run = handedOut.poll(5, TimeUnit.SECONDS);
            assertNotNull(run);

            bridge.release(SESSION);

            RecordingEmitter emitter = bridge.created.get(0);
            assertTrue(emitter.completed.get(), "release 应关闭连接");

            run.onToken("会话已销毁后的残留输出");
            assertEquals(0, emitter.events.size(), "释放后的连接不应再收到事件");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void supersededRunDoesNotClearTheNewRunsPendingQuestion() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        PendingResponses pending = new PendingResponses(5);
        TestBridge bridge = new TestBridge(fakeManager(), pending, executor);
        firstSendGate = new CountDownLatch(1);
        try {
            // 第一轮卡在 send 里，稍后才会走收尾逻辑
            bridge.stream(SESSION, "第一轮");
            assertNotNull(handedOut.poll(5, TimeUnit.SECONDS));

            // 用户刷新：第二轮顶掉第一轮的连接，并很快结束
            bridge.stream(SESSION, "第二轮");
            assertNotNull(handedOut.poll(5, TimeUnit.SECONDS));

            // 新一轮的 Agent 正在等用户回答问题
            CompletableFuture<String> answer = pending.awaitAskUser(SESSION);

            // 现在第一轮才收尾 —— 它绝不能把新一轮的等待请求一起清掉
            firstSendGate.countDown();
            Thread.sleep(200);

            assertTrue(pending.isWaitingForUser(SESSION),
                    "被顶替的那一轮收尾时清掉了新一轮的等待请求，用户回答将无处可去");
            pending.submitAskUser(SESSION, "用户回答");
            assertEquals("用户回答", answer.get(1, TimeUnit.SECONDS));
        } finally {
            firstSendGate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedExecutionCompletesConnectionWithError() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();  // 已关闭：submit 必然被拒
        TestBridge bridge = new TestBridge(fakeManager(), new PendingResponses(5), executor);

        RecordingEmitter emitter = (RecordingEmitter) bridge.stream(SESSION, "hi");

        assertEquals(1, emitter.events.size(), "应推一条 error 而不是让连接一直挂着");
        assertTrue(emitter.completed.get(), "被拒绝的执行必须当场收尾连接");
    }
}
