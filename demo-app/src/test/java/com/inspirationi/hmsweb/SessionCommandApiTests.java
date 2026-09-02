package com.inspirationi.hmsweb;

import com.inspirationi.loop.api.HmsSessionManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slash 命令新增端点的契约测试 —— 覆盖 {@code /compact} 与 {@code /prompt}。
 * <p>
 * 这两组端点是前端 slash 命令的后端支撑，各有一处容易回归的坑：
 * <ul>
 *   <li>{@code GET /prompt} 必须用 {@code HashMap} 组装响应 —— 会话以默认提示词创建
 *       时 {@code getSessionPrompt()} 返回 null，{@code Map.of} 遇 null 会 500</li>
 *   <li>{@code POST /compact} 的「历史太短」是正常结果，必须是 200 + {@code
 *       compacted:false}，而不是错误</li>
 * </ul>
 * 状态码约定同 {@link ApiErrorHandlingTests}：Controller 主动校验失败返 200 +
 * {@code success:false}，只有 SDK 抛出的异常才经 {@code ApiExceptionHandler} 转 400。
 * <p>
 * 用 JDK 自带 {@link HttpClient} 起真实 HTTP，原因见 {@link ApiErrorHandlingTests}。
 * 全部用例都不发起真实 AI 调用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.ai.anthropic.api-key=sk-test-placeholder",
        "spring.ai.openai.api-key=sk-test-placeholder",
        "hms-core.i18n.enabled=false"
})
class SessionCommandApiTests {

    /** 一个必定不存在的会话 ID。 */
    private static final String MISSING = "no-such-session";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private HmsSessionManager sessionManager;

    // ==================== POST /{id}/compact ====================

    /** 会话不存在时 SDK 抛 IllegalArgumentException，应转成 400。 */
    @Test
    void compactMissingSessionReturnsBadRequest() throws Exception {
        HttpResponse<String> resp = post("/api/sessions/" + MISSING + "/compact", null);

        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":false"),
                "会话不存在应返回失败体，实际: " + resp.body());
    }

    /**
     * 新建会话的历史只有系统提示词，压不动 —— 应是 200 + compacted:false。
     * <p>
     * 这条同时是整条链路的冒烟：HTTP → {@code compactNow} → {@code
     * synchronized (session)} → {@code AutoCompactManager}，在占位 API Key 下
     * 走完而不触碰 ChatModel。
     */
    @Test
    void compactShortHistoryReportsNoAction() throws Exception {
        String sessionId = sessionManager.createSession();
        try {
            HttpResponse<String> resp = post("/api/sessions/" + sessionId + "/compact", null);

            assertEquals(200, resp.statusCode(), "「没什么可压」不是错误，实际: " + resp.body());
            assertTrue(resp.body().contains("\"success\":true"));
            assertTrue(resp.body().contains("\"compacted\":false"),
                    "历史过短应报告未压缩，实际: " + resp.body());
            // layer 恒为 MANUAL：手动压缩不走自动分层
            assertTrue(resp.body().contains("\"layer\":\"MANUAL\""),
                    "手动压缩的 layer 应为 MANUAL，实际: " + resp.body());
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    // ==================== 运行时活动状态 ====================

    /** 新建会话应处于空闲 —— 不该一上来就显示「正在忙」。 */
    @Test
    void newSessionReportsIdleActivity() throws Exception {
        String sessionId = sessionManager.createSession();
        try {
            HttpResponse<String> resp = get("/api/sessions/" + sessionId);

            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"activity\":\"IDLE\""),
                    "新建会话的 activity 应为 IDLE，实际: " + resp.body());
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    /**
     * 会话列表每项都要带 activity。
     * <p>
     * 钉住 {@code SessionInfo} 的对外契约 —— 前端侧栏靠它区分忙/闲，字段丢了
     * 圆点就只能反映生命周期。
     */
    @Test
    void sessionListIncludesActivityField() throws Exception {
        String sessionId = sessionManager.createSession();
        try {
            HttpResponse<String> resp = get("/api/sessions");

            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"activity\":"),
                    "会话列表应包含 activity 字段，实际: " + resp.body());
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    // ==================== GET /{id}/prompt ====================

    /** 会话不存在：PromptManager 返回 null 不抛，靠前置检查返 200 + 失败体。 */
    @Test
    void getPromptMissingSessionReturnsFailureNotBadRequest() throws Exception {
        HttpResponse<String> resp = get("/api/sessions/" + MISSING + "/prompt");

        assertEquals(200, resp.statusCode(),
                "前置检查分支应返 200 而非 400，实际: " + resp.body());
        assertTrue(resp.body().contains("\"success\":false"));
    }

    /**
     * 以默认提示词创建的会话，{@code getSessionPrompt()} 返回 null。
     * <p>
     * 钉住 HashMap 而非 Map.of —— 后者遇 null value 抛 NPE，会让这里变成 500。
     */
    @Test
    void getPromptSerializesNullSessionPrompt() throws Exception {
        String sessionId = sessionManager.createSession();
        try {
            HttpResponse<String> resp = get("/api/sessions/" + sessionId + "/prompt");

            assertEquals(200, resp.statusCode(),
                    "sessionPrompt 为 null 不应导致 500，实际: " + resp.body());
            assertTrue(resp.body().contains("\"success\":true"));
            // globalPrompt 恒非 null，必须出现在响应里
            assertTrue(resp.body().contains("globalPrompt"),
                    "响应应包含 globalPrompt，实际: " + resp.body());
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    // ==================== PUT /{id}/prompt ====================

    /** 空白提示词是无意义输入，就地挡下 —— 200 + 失败体。 */
    @Test
    void updatePromptRejectsBlank() throws Exception {
        String sessionId = sessionManager.createSession();
        try {
            HttpResponse<String> resp = put("/api/sessions/" + sessionId + "/prompt",
                    "{\"sessionPrompt\":\"   \"}");

            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"success\":false"),
                    "空白提示词应被拒绝，实际: " + resp.body());
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    /** 缺失请求体同样应被挡下，而不是 NPE 变 500。 */
    @Test
    void updatePromptRejectsMissingBody() throws Exception {
        String sessionId = sessionManager.createSession();
        try {
            HttpResponse<String> resp = put("/api/sessions/" + sessionId + "/prompt", "{}");

            assertEquals(200, resp.statusCode(), "缺字段不应 500，实际: " + resp.body());
            assertTrue(resp.body().contains("\"success\":false"));
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    /** 会话不存在时 SDK 抛异常 → 400。 */
    @Test
    void updatePromptMissingSessionReturnsBadRequest() throws Exception {
        HttpResponse<String> resp = put("/api/sessions/" + MISSING + "/prompt",
                "{\"sessionPrompt\":\"你是测试助手\"}");

        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":false"));
    }

    /** 更新后应能立刻读回新值。 */
    @Test
    void updatePromptThenReadBack() throws Exception {
        String sessionId = sessionManager.createSession();
        try {
            HttpResponse<String> put = put("/api/sessions/" + sessionId + "/prompt",
                    "{\"sessionPrompt\":\"你是一名严谨的代码审查员\"}");
            assertEquals(200, put.statusCode());
            assertTrue(put.body().contains("\"success\":true"), "更新应成功，实际: " + put.body());

            HttpResponse<String> get = get("/api/sessions/" + sessionId + "/prompt");
            assertEquals(200, get.statusCode());
            assertTrue(get.body().contains("严谨的代码审查员"),
                    "应读回刚写入的提示词，实际: " + get.body());
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    /**
     * 已暂停的会话仍应允许更新提示词。
     * <p>
     * 钉住实现用的是 {@code requireExistingSession} 而非 {@code requireSession} ——
     * 「暂停 → 调整提示词 → 恢复」是这个能力最自然的用法，若换成后者会抛
     * {@code IllegalStateException} 变 400。
     */
    @Test
    void updatePromptWorksOnPausedSession() throws Exception {
        String sessionId = sessionManager.createSession();
        try {
            sessionManager.pauseSession(sessionId);

            HttpResponse<String> resp = put("/api/sessions/" + sessionId + "/prompt",
                    "{\"sessionPrompt\":\"暂停期间也能改\"}");

            assertEquals(200, resp.statusCode(),
                    "PAUSED 会话应允许更新提示词，实际: " + resp.body());
            assertTrue(resp.body().contains("\"success\":true"));
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    // ==================== HTTP 辅助 ====================

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest.BodyPublisher body = json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json);
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(body));
    }

    private HttpResponse<String> put(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return CLIENT.send(builder.timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
