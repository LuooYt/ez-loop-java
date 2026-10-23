package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.tool.ToolContext;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebFetchTool} 的响应体读取测试。
 * <p>
 * 这些用例守护两件事：超大响应不会被整个读进内存（{@code ofString()} 的
 * 老做法会），以及参数类型不对时不会抛 {@code ClassCastException}。
 */
class WebFetchBoundedReadTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/big", exchange -> {
            // 5MB：远超字节上限，也远超任何合理的内存预算
            byte[] chunk = "x".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, chunk.length * 80L);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 80; i++) {
                    out.write(chunk);
                }
            }
        });
        server.createContext("/gbk", exchange -> {
            byte[] body = "中文内容".getBytes(Charset.forName("GBK"));
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=GBK");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/small", exchange -> {
            byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ToolContext context() {
        ToolContext context = ToolContext.defaultContext();
        context.set(WebFetchTool.CTX_ALLOW_INTERNAL, true);
        return context;
    }

    /**
     * 5MB 响应体、{@code maxLength=100}：只应读约 400 字节（100 字符 × 4），
     * 并明确标注被截断 —— 让模型知道拿到的不是全文。
     */
    @Test
    void truncatesOversizedResponseBodyAndSaysSo() {
        String result = new WebFetchTool().execute(
                Map.of("url", baseUrl + "/big", "maxLength", 100), context());

        assertTrue(result.contains("response body truncated at 400 bytes"),
                "必须标注按字节截断，实际：" + abbreviate(result));
        assertTrue(result.length() < 1000,
                "输出必须远小于原始响应，实际长度 " + result.length());
    }

    /** 参数以字符串形式到达时不能抛 ClassCastException。 */
    @Test
    void acceptsStringValuesForTypedParameters() {
        Map<String, Object> input = new HashMap<>();
        input.put("url", baseUrl + "/small");
        input.put("maxLength", "5");
        input.put("raw", "false");

        String result = new WebFetchTool().execute(input, context());

        assertTrue(result.startsWith("URL: "), result);
        // maxLength=5 生效：正文只剩 "hello"
        assertTrue(result.contains("hello"), result);
        assertFalse(result.contains("hello world"), "maxLength 必须生效，实际：" + result);
    }

    /** 按 {@code Content-Type} 的 charset 解码，而不是一律当 UTF-8。 */
    @Test
    void decodesBodyUsingContentTypeCharset() {
        String result = new WebFetchTool().execute(
                Map.of("url", baseUrl + "/gbk"), context());

        assertTrue(result.contains("中文内容"),
                "应按 GBK 解码，实际：" + abbreviate(result));
    }

    /** 未超限的响应按原样返回，不应出现截断标记。 */
    @Test
    void doesNotMarkSmallResponsesAsTruncated() {
        String result = new WebFetchTool().execute(
                Map.of("url", baseUrl + "/small"), context());

        assertEquals("URL: " + baseUrl + "/small\n"
                + "Status: 200\n"
                + "Content-Type: text/plain\n"
                + "---\n"
                + "hello world", result);
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }
}
