package com.inspirationi.loop.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LSPClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static byte[] frame(String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[header.length + payload.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(payload, 0, out, header.length, payload.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * Content-Length 是<b>字节</b>数。按字符读会把多字节正文读错长度，
     * 随后整条消息错位 —— 中文诊断信息一出现就会触发。
     */
    @Test
    void readsBodyByByteLengthNotCharLength() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"text\":\"你好世界\"}}";

        byte[] read = LSPClient.readFrame(new ByteArrayInputStream(frame(body)));

        assertNotNull(read, "一帧完整正文必须能读出");
        assertEquals(body.getBytes(StandardCharsets.UTF_8).length, read.length,
                "读出的字节数必须等于 Content-Length");
        assertEquals(body, new String(read, StandardCharsets.UTF_8));
    }

    /** 多字节正文之后，下一帧的边界仍然要对齐。 */
    @Test
    void keepsFramingAlignedAfterMultibyteBody() throws Exception {
        String first = "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\","
                + "\"params\":{\"diagnostics\":[{\"message\":\"未使用的变量\"}]}}";
        String second = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":null}";
        var in = new ByteArrayInputStream(concat(frame(first), frame(second)));

        assertEquals(first, new String(LSPClient.readFrame(in), StandardCharsets.UTF_8));
        assertEquals(second, new String(LSPClient.readFrame(in), StandardCharsets.UTF_8));
        assertNull(LSPClient.readFrame(in), "流结束时必须返回 null");
    }

    /**
     * 优雅关闭必须真的把 shutdown / exit 发给服务端：先把 running 置 false 再发，
     * 请求会被 sendRaw 直接拒掉，服务端只能等着被杀。
     */
    @Test
    void closeSendsShutdownAndExitBeforeStopping() throws Exception {
        try (FakeServer server = new FakeServer()) {
            LSPClient client = new LSPClient("fake", server.clientStdin(), server.clientStdout());
            client.initialize(Map.of());

            client.close();
            server.awaitExit();

            assertTrue(server.methods().contains("shutdown"),
                    "关闭时必须先发 shutdown，实际收到: " + server.methods());
            assertTrue(server.methods().contains("exit"),
                    "关闭时必须发 exit，实际收到: " + server.methods());
        }
    }

    /**
     * 服务端进程异常退出时，客户端必须通知宿主（LSPServerInstance），
     * 否则状态永远停在 RUNNING，后续请求全部超时。
     */
    @Test
    void notifiesCrashListenerWhenServerDiesUnexpectedly() throws Exception {
        FakeServer server = new FakeServer();
        LSPClient client = new LSPClient("fake", server.clientStdin(), server.clientStdout());
        AtomicBoolean crashed = new AtomicBoolean(false);
        client.setOnCrash(() -> crashed.set(true));

        server.kill(); // 模拟 LSP 进程崩溃：输出流关闭

        assertTrue(waitFor(crashed, 3_000), "进程断开后必须触发 crash 回调");
        assertFalse(client.isRunning(), "崩溃后客户端不得继续报告运行中");
        client.close();
    }

    private static boolean waitFor(AtomicBoolean flag, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (flag.get()) return true;
            Thread.sleep(10);
        }
        return flag.get();
    }

    /** 极简内存版 LSP 服务端：收请求、回复、记录方法名，可模拟崩溃。 */
    private static final class FakeServer implements AutoCloseable {

        private final PipedOutputStream stdinForClient = new PipedOutputStream();
        private final PipedInputStream stdoutForClient = new PipedInputStream(64 * 1024);
        private final PipedInputStream stdinFromClient;
        private final PipedOutputStream stdoutFromClient = new PipedOutputStream(stdoutForClient);
        private final List<String> methods = Collections.synchronizedList(new ArrayList<>());
        private final Thread thread;

        FakeServer() throws IOException {
            stdinFromClient = new PipedInputStream(stdinForClient, 64 * 1024);
            thread = Thread.ofVirtual().name("fake-lsp").start(this::serve);
        }

        OutputStream clientStdin() {
            return stdinForClient;
        }

        InputStream clientStdout() {
            return stdoutForClient;
        }

        List<String> methods() {
            return methods;
        }

        private void serve() {
            try {
                while (true) {
                    byte[] body = LSPClient.readFrame(stdinFromClient);
                    if (body == null) break;
                    JsonNode message = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
                    String method = message.path("method").asText("");
                    if (!method.isEmpty()) methods.add(method);

                    if (message.has("id")) {
                        ObjectNode response = MAPPER.createObjectNode();
                        response.put("jsonrpc", "2.0");
                        response.set("id", message.get("id"));
                        response.putNull("result");
                        write(response);
                    }
                    if ("exit".equals(method)) break;
                }
            } catch (IOException ignored) {
                // 连接断开属于预期
            }
        }

        private void write(JsonNode message) throws IOException {
            byte[] payload = MAPPER.writeValueAsBytes(message);
            stdoutFromClient.write(("Content-Length: " + payload.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            stdoutFromClient.write(payload);
            stdoutFromClient.flush();
        }

        void awaitExit() throws Exception {
            thread.join(3_000);
        }

        /** 关闭服务端输出流，模拟进程崩溃。 */
        void kill() throws IOException {
            stdoutFromClient.close();
        }

        @Override
        public void close() throws IOException {
            stdinForClient.close();
            stdoutFromClient.close();
            stdinFromClient.close();
            stdoutForClient.close();
        }
    }
}
