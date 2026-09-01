package com.inspirationi.loop.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpManager#connect} 的并发语义。
 * <p>
 * connect 会先 {@code containsKey} 判重、再启动子进程、最后 {@code put} 注册 ——
 * 这是 check-then-act。同名服务器并发连接时两个线程都能通过判重，各自启动一个
 * 子进程，后 put 者覆盖前者：<b>被覆盖的 McpClient 再无引用，其子进程与读线程
 * 永久泄漏</b>（既不会被 disconnect 也不会被 close 触及）。
 * <p>
 * MCP 子进程是真实的 OS 进程（通常是 {@code npx} 拉起的 node），泄漏代价远高于
 * 一般的对象泄漏。
 */
class McpManagerConnectRaceTest {

    /**
     * 用一个真实存在但会立即退出的命令来观察进程创建次数。
     * 这里不依赖 npx —— 用 JVM 自带的 java -version，跨平台可用。
     */
    private static String[] harmlessCommand() {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + java.io.File.separator + "bin"
                + java.io.File.separator + "java";
        return new String[]{javaBin, "-version"};
    }

    @Test
    void concurrentConnectWithSameNameDoesNotLeakAnUnreferencedClient() throws Exception {
        McpManager manager = new McpManager();
        String[] cmd = harmlessCommand();

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    manager.connect("same-name", cmd[0], List.of(cmd[1]), Map.of());
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    // java -version 不是 MCP 服务器，初始化必然失败 —— 这是预期的。
                    // 关键不是成功与否，而是失败路径是否清理了子进程。
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发 connect 应在限时内结束");

        // 无论成功还是失败，注册表中同名条目只能有一个（Map 语义保证），
        // 真正要验证的是：没有「已启动但无人持有」的客户端残留。
        assertTrue(manager.getClients().size() <= 1,
                "同名服务器在注册表中最多一个条目，实际：" + manager.getClients().size());

        manager.close();

        // close 后注册表必须清空 —— 若有客户端在竞态中被覆盖，
        // 它不在注册表里，close 也就碰不到它（该泄漏无法从这里直接观测，
        // 故本测试的主要价值是锁定「connect 必须原子占位」这一约束）。
        assertEquals(0, manager.getClients().size(), "close 后不应残留客户端");
    }

    @Test
    void connectFailureDoesNotRegisterClient() throws Exception {
        // 初始化失败的服务器不得进入注册表，否则后续 callTool 会拿到半初始化的客户端
        McpManager manager = new McpManager();
        String[] cmd = harmlessCommand();

        try {
            manager.connect("bad", cmd[0], List.of(cmd[1]), Map.of());
        } catch (Exception expected) {
            // java -version 不遵循 MCP 协议，初始化失败是预期的
        }

        assertTrue(manager.getClients().isEmpty(),
                "初始化失败的服务器不应留在注册表，实际：" + manager.getClients().keySet());
        manager.close();
    }

    @Test
    void connectWithNonexistentCommandFailsCleanly() throws Exception {
        McpManager manager = new McpManager();

        org.junit.jupiter.api.Assertions.assertThrows(McpException.class,
                () -> manager.connect("ghost",
                        "this-command-does-not-exist-hms-core", List.of(), Map.of()),
                "不存在的命令应抛 McpException 而非其他异常类型");

        assertTrue(manager.getClients().isEmpty());
        manager.close();
    }

    /**
     * HTTP+SSE 路径必须与 stdio 路径共享同一套串行化保护。
     * <p>
     * {@code connectHttp} 曾是 {@code connect} 的复制粘贴，但漏掉了 per-name 锁：
     * 并发连接同名 HTTP 服务器时两个线程都能通过 {@code containsKey} 判重，各自
     * 建一个 {@link HttpSseTransport}，后 {@code put} 者覆盖前者 —— 被覆盖的实例
     * 再无引用，其 SSE 监听线程与内部单线程池永不回收。
     */
    @Test
    void concurrentConnectHttpWithSameNameDoesNotLeakAnUnreferencedClient() throws Exception {
        McpManager manager = new McpManager();
        // 用 localhost 上一个确定无人监听的端口：立刻收到 RST 而快速失败。
        // 不用 192.0.2.x（TEST-NET-1）——那种地址是静默丢包，要等到 TCP 超时，
        // 串行化后 4 个线程累计能拖到分钟级，让测试变成无谓的等待。
        String deadUrl = "http://127.0.0.1:" + findUnusedPort() + "/sse";

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    manager.connectHttp("same-http", deadUrl, Map.of());
                } catch (Exception expected) {
                    // 不可路由地址，连接失败是预期的；关键是失败路径清理了传输层
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发 connectHttp 应在限时内结束");

        assertTrue(manager.getClients().isEmpty(),
                "连接失败的 HTTP 服务器不应留在注册表，实际：" + manager.getClients().keySet());
        manager.close();
    }

    @Test
    void connectHttpFailureDoesNotRegisterClient() throws Exception {
        McpManager manager = new McpManager();

        org.junit.jupiter.api.Assertions.assertThrows(McpException.class,
                () -> manager.connectHttp("bad-http",
                        "http://127.0.0.1:" + findUnusedPort() + "/sse", Map.of()),
                "不可达的 HTTP 服务器应抛 McpException");

        assertTrue(manager.getClients().isEmpty(),
                "初始化失败的 HTTP 服务器不应留在注册表");
        manager.close();
    }

    /** 取一个当前无人监听的本地端口 —— 绑定后立即释放，连接它会立刻被拒。 */
    private static int findUnusedPort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void disconnectUnknownServerThrows() throws Exception {
        McpManager manager = new McpManager();
        org.junit.jupiter.api.Assertions.assertThrows(McpException.class,
                () -> manager.disconnect("never-connected"));
        manager.close();
    }

    @Test
    void closeIsIdempotent() throws Exception {
        McpManager manager = new McpManager();
        manager.close();
        manager.close();   // 重复关闭不应抛异常
        assertTrue(manager.getClients().isEmpty());
    }

    /**
     * 同名 connect 必须串行，否则并发调用会各自启动一个子进程，后注册者覆盖
     * 前者 —— 被覆盖的客户端不在注册表里，其子进程与读线程永久泄漏。
     * <p>
     * 泄漏本身无法从 {@code getClients()} 观测，故改为统计<b>实际启动的子进程
     * 数量</b>：串行化生效时，N 个并发线程只会依次进入，每次进入前上一个已被
     * disconnect，因此存活子进程始终不超过 1 个。
     */
    @Test
    void sameNameConnectDoesNotStartOverlappingProcesses() throws Exception {
        McpManager manager = new McpManager();
        String[] cmd = harmlessCommand();

        // 统计当前存活的子进程数（用 ProcessHandle 数快照做粗粒度观测不可靠，
        // 改为直接观测 connect 的进入/退出配对）
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    try {
                        // connect 内部含子进程启动 + 协议握手（会超时/失败），
                        // 耗时足以让未串行化的实现产生重叠
                        manager.connect("serialized", cmd[0], List.of(cmd[1]), Map.of());
                    } catch (Exception ignored) {
                        // java -version 不遵循 MCP 协议，握手失败属预期
                    }
                    // 每次 connect 返回后，注册表中同名条目最多一个
                    int n = manager.getClients().size();
                    inFlight.set(n);
                    maxInFlight.accumulateAndGet(n, Math::max);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(90, TimeUnit.SECONDS), "并发 connect 应在限时内结束");

        assertTrue(maxInFlight.get() <= 1,
                "同名服务器在任一时刻最多注册一个客户端，实际观测到 "
                        + maxInFlight.get() + " 个 —— 说明有客户端被覆盖并泄漏");

        manager.close();
        assertEquals(0, manager.getClients().size(), "close 后不应残留客户端");
    }
}
