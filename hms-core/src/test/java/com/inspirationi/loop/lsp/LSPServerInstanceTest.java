package com.inspirationi.loop.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LSPServerInstanceTest {

    @TempDir Path temp;

    /** 启动成功但 initialize 失败的客户端，用来检查失败路径是否收拾进程。 */
    private static final class FailingClient extends LSPClient {
        volatile boolean closed;
        volatile Runnable crashHook;

        FailingClient() {
            super("fake");
        }

        @Override
        public void start(String command, List<String> args, Map<String, String> env, String cwd) {
            // 假装进程已拉起，不再真正 spawn
        }

        @Override
        public JsonNode initialize(Map<String, Object> initParams, long timeoutMs) throws Exception {
            throw new IOException("initialize failed");
        }

        @Override
        public void setOnCrash(Runnable listener) {
            crashHook = listener;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private LSPServerConfig config() {
        return new LSPServerConfig("fake", "fake-lsp",
                List.of(), Map.of(".java", "java"), null, null, null, null, 1_000, 3);
    }

    /**
     * initialize 失败时子进程已经拉起来了，不关就是孤儿进程 ——
     * 会话结束后这个 LSP 进程会一直挂着。
     */
    @Test
    void closesClientWhenInitializeFails() {
        FailingClient client = new FailingClient();
        LSPServerInstance instance =
                new LSPServerInstance("fake", config(), temp, () -> client);

        assertFalse(instance.ensureStarted(), "initialize 失败时 ensureStarted 必须返回 false");
        assertEquals(LSPServerInstance.State.ERROR, instance.getState());
        assertTrue(client.closed, "initialize 失败后必须关闭已拉起的客户端/子进程");
    }

    /** 崩溃回调必须挂到客户端上，否则 readLoop 断开后状态永远停在 RUNNING。 */
    @Test
    void registersCrashHookOnClient() {
        FailingClient client = new FailingClient();
        LSPServerInstance instance =
                new LSPServerInstance("fake", config(), temp, () -> client);

        instance.ensureStarted();

        assertNotNull(client.crashHook, "必须向客户端注册崩溃回调");
    }

    /**
     * initialize 的等待时长必须来自 {@code LSPServerConfig.startupTimeout}：
     * 写死的 60s 会让「启动很快但初始化慢」的服务器配置形同虚设。
     */
    @Test
    void initializeUsesConfiguredStartupTimeout() {
        class TimeoutCapturingClient extends LSPClient {
            volatile long captured = -1;

            TimeoutCapturingClient() {
                super(null, new java.io.ByteArrayOutputStream(), new java.io.ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void start(String command, List<String> args, Map<String, String> env, String cwd) {
                // 不真正 spawn
            }

            @Override
            public JsonNode initialize(Map<String, Object> initParams, long timeoutMs) {
                captured = timeoutMs;
                return null;
            }
        }

        TimeoutCapturingClient client = new TimeoutCapturingClient();
        LSPServerConfig config = new LSPServerConfig("fake", "fake-lsp",
                List.of(), Map.of(".java", "java"), null, null, null, null, 1_234, 3);
        LSPServerInstance instance = new LSPServerInstance("fake", config, temp, () -> client);

        assertTrue(instance.ensureStarted());
        assertEquals(1_234, client.captured,
                "initialize 超时必须取自配置的 startupTimeout");
    }

    /** 崩溃回调把实例置为 ERROR —— 之后 ensureStarted 才可能按 maxRestarts 重启。 */
    @Test
    void crashHookMarksInstanceAsError() {
        FailingClient client = new FailingClient();
        LSPServerInstance instance =
                new LSPServerInstance("fake", config(), temp, () -> client);
        instance.ensureStarted();

        client.crashHook.run();

        assertEquals(LSPServerInstance.State.ERROR, instance.getState());
        assertEquals(1, instance.getCrashCount());
    }
}
