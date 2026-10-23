package com.inspirationi.loop.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpSseTransport} 的监听循环与断线语义。
 * <p>
 * 旧实现有两个致命问题：
 * <ul>
 *   <li>监听循环的条件是 {@code connected.get()}，而 {@code connected} 要等
 *       {@code connect()} 的 100ms 轮询醒来才置位。服务端握手后紧接着推送的事件
 *       （通知、甚至第一个响应）会被这个尚未置位的标志挡在门外，循环直接退出 ——
 *       此后传输层收不到任何响应。</li>
 *   <li>连上之后监听线程死掉时 {@code connected} 仍是 true：请求照常登记 Future、
 *       干等满超时，而且再也不会重连。</li>
 * </ul>
 * 用例用一个本地 HTTP 服务器复现这两个场景。
 */
class HttpSseTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;

    /** 服务端要推给客户端的事件（原始 SSE 文本）。 */
    private final BlockingQueue<String> pushes = new LinkedBlockingQueue<>();

    /** 置位后当前 SSE 流结束（模拟服务端断线）。 */
    private final AtomicBoolean closeStream = new AtomicBoolean(false);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/sse", this::handleSse);
        server.createContext("/message", this::handleMessage);

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 握手后<b>立刻</b>再推一条通知，然后按需推送后续事件。
     * <p>
     * 「立刻」是关键：旧实现的监听循环正是在这一刻因为 connected 尚未置位而退出。
     */
    private void handleSse(HttpExchange exchange) throws IOException {
        closeStream.set(false);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            write(out, "event: endpoint\ndata: /message\n\n");
            write(out, "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n\n");

            while (!closeStream.get()) {
                String payload = pushes.poll(100, TimeUnit.MILLISECONDS);
                if (payload != null) {
                    write(out, payload);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端断开
        }
    }

    /** 收到 JSON-RPC 请求后通过 SSE 推回响应（MCP HTTP 传输的正常形式）。 */
    private void handleMessage(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode request = MAPPER.readTree(body);
        String id = request.has("id") ? request.get("id").asText() : null;
        String method = request.has("method") ? request.get("method").asText() : "";

        exchange.sendResponseHeaders(202, -1);
        exchange.close();

        // "nostop" 用来验证「响应永远不会来」时断线能否让调用方立刻失败
        if (id != null && !"nostop".equals(method)) {
            pushes.add("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"" + id
                    + "\",\"result\":{\"ok\":true}}\n\n");
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Test
    void listenerSurvivesEventsSentRightAfterHandshake() throws Exception {
        HttpSseTransport transport = new HttpSseTransport(baseUrl, Map.of(), Duration.ofSeconds(5));
        try {
            transport.connect();
            assertTrue(transport.isConnected(), "握手应成功");

            JsonNode response = transport.sendRequest(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

            assertTrue(response.path("result").path("ok").asBoolean(),
                    "握手后紧跟着的推送不能让监听线程退出，否则这里会等到超时：" + response);
        } finally {
            transport.close();
        }
    }

    @Test
    void disconnectIsDetectedAndNextRequestReconnects() throws Exception {
        HttpSseTransport transport = new HttpSseTransport(baseUrl, Map.of(), Duration.ofSeconds(5));
        try {
            transport.connect();

            closeStream.set(true);
            awaitDisconnected(transport);

            JsonNode response = transport.sendRequest(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
            assertTrue(response.path("result").path("ok").asBoolean(),
                    "断线后的下一个请求应自动重连：" + response);
        } finally {
            transport.close();
        }
    }

    @Test
    void disconnectFailsPendingRequestImmediately() throws Exception {
        // 超时设得足够长：若断线没有让等待方立刻失败，用例会在 5 秒处超时
        HttpSseTransport transport = new HttpSseTransport(baseUrl, Map.of(), Duration.ofSeconds(30));
        try {
            transport.connect();

            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    transport.sendRequest("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"nostop\"}");
                    outcome.complete(null);
                } catch (Throwable e) {
                    outcome.complete(e);
                }
            });

            // 等服务端收到请求、传输层登记好等待项
            Thread.sleep(300);
            closeStream.set(true);

            Throwable error = outcome.get(5, TimeUnit.SECONDS);
            assertNotNull(error, "断线后等待中的请求必须失败，而不是继续等到 30 秒超时");
            assertTrue(error.getMessage().toLowerCase().contains("sse"),
                    "错误信息应说明是 SSE 断线：" + error.getMessage());
        } finally {
            transport.close();
        }
    }

    private void awaitDisconnected(HttpSseTransport transport) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (transport.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(transport.isConnected(), "服务端关流后传输层应回到未连接状态");
    }
}
