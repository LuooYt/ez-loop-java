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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端点错误处理测试 —— 守护 {@link com.inspirationi.hmsweb.controller.ApiExceptionHandler}。
 * <p>
 * 这些端点直接把 sessionId 交给 hms-core，会话不存在时 SDK 抛
 * {@code IllegalArgumentException}。没有全局异常处理器时它们会变成 Spring 默认的
 * 500 白页，前端拿到一段 HTML 而非 {@code ApiResponse} —— 而「会话刚被删」是正常
 * 业务分支，不该按服务端故障对待。
 * <p>
 * 用 JDK 自带的 {@link HttpClient} 起真实 HTTP 调用：Spring Boot 4 移除了
 * {@code @AutoConfigureMockMvc} 与 {@code TestRestTemplate}，且真实请求能一并验证
 * 异常处理器返回的状态码与 JSON 结构。
 * <p>
 * 全部用例都不发起真实 AI 调用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.ai.anthropic.api-key=sk-test-placeholder",
        "spring.ai.openai.api-key=sk-test-placeholder",
        "hms-core.i18n.enabled=false"
})
class ApiErrorHandlingTests {

    /** 一个必定不存在的会话 ID。 */
    private static final String MISSING = "no-such-session";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private HmsSessionManager sessionManager;

    // ==================== 会话不存在 ====================

    /** 会话不存在时，只读查询应返回 400 + 失败体，而不是 500。 */
    @Test
    void readOnlyEndpointsRejectMissingSessionGracefully() throws Exception {
        String[] paths = {
                "/api/sessions/" + MISSING + "/tokens",
                "/api/metrics/" + MISSING,
        };
        for (String path : paths) {
            HttpResponse<String> resp = get(path);
            assertEquals(400, resp.statusCode(), path + " 应返回 400 而非 500");
            assertTrue(resp.body().contains("\"success\":false"),
                    path + " 应返回 ApiResponse 失败体，实际: " + resp.body());
        }
    }

    /**
     * 会话工具查询对不存在的会话返回空列表而非报错。
     * <p>
     * 这是 hms-core {@code DefaultToolManager.getSessionToolNames} 的既有设计
     * （{@code getSessionInfo} 为 null 时回落到 {@code List.of()}），与上面几个
     * 抛异常的端点不同。锁住它，避免日后被「统一成抛异常」而悄悄改变契约。
     */
    @Test
    void sessionToolsOnMissingSessionReturnsEmptyList() throws Exception {
        HttpResponse<String> resp = get("/api/tools/" + MISSING);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":true"));
        assertTrue(resp.body().contains("\"data\":[]"), "应返回空列表，实际: " + resp.body());
    }

    /** 会话不存在时，pause / resume 同样应优雅失败。 */
    @Test
    void lifecycleEndpointsRejectMissingSessionGracefully() throws Exception {
        String[] paths = {
                "/api/sessions/" + MISSING + "/pause",
                "/api/sessions/" + MISSING + "/resume",
        };
        for (String path : paths) {
            HttpResponse<String> resp = post(path, null);
            assertEquals(400, resp.statusCode(), path + " 应返回 400 而非 500");
            assertTrue(resp.body().contains("\"success\":false"), path + " 应返回失败体");
        }
    }

    /** 重复暂停会抛 IllegalStateException，也应被翻译成失败体。 */
    @Test
    void pausingAnAlreadyPausedSessionFailsGracefully() throws Exception {
        String sessionId = sessionManager.createSession("pause test");
        try {
            HttpResponse<String> first = post("/api/sessions/" + sessionId + "/pause", null);
            assertEquals(200, first.statusCode());
            assertTrue(first.body().contains("\"success\":true"));

            // 已暂停的会话再次暂停 → IllegalStateException
            HttpResponse<String> second = post("/api/sessions/" + sessionId + "/pause", null);
            assertEquals(400, second.statusCode(), "重复暂停应返回 400 而非 500");
            assertTrue(second.body().contains("\"success\":false"));
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    /** 取消不存在的会话是幂等的静默操作，不应报错。 */
    @Test
    void cancelOnMissingSessionIsSilent() throws Exception {
        HttpResponse<String> resp = post("/api/sessions/" + MISSING + "/cancel", null);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":true"));
    }

    /** 同步对话在会话不存在时走 Controller 内的前置检查，返回 200 + 失败体。 */
    @Test
    void chatSyncOnMissingSessionReturnsFailureBody() throws Exception {
        HttpResponse<String> resp = post("/api/chat/" + MISSING, "{\"message\":\"hi\"}");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":false"));
        assertFalse(sessionManager.sessionExists(MISSING));
    }

    // ==================== 序列化契约 ====================

    /**
     * 会话列表必须暴露 inputTokens / outputTokens 两个组件字段。
     * <p>
     * {@code SessionInfo.totalTokens()} 是 record 的派生方法，Jackson 不序列化它 ——
     * 前端只能自己相加（见 session-list.js）。这个用例锁住前端依赖的字段确实存在，
     * 同时记录下「totalTokens 不在列表响应里」这一事实。
     */
    @Test
    void sessionListExposesTokenComponentFields() throws Exception {
        String sessionId = sessionManager.createSession("token field test");
        try {
            HttpResponse<String> resp = get("/api/sessions");
            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"inputTokens\""), "列表响应应含 inputTokens");
            assertTrue(resp.body().contains("\"outputTokens\""), "列表响应应含 outputTokens");
            assertFalse(resp.body().contains("\"totalTokens\""),
                    "totalTokens 是派生方法，不应出现在列表响应中（前端需自行相加）");
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    /** /tokens 端点手工组装 Map，totalTokens 在这里反而应该存在。 */
    @Test
    void tokenStatsEndpointExposesTotal() throws Exception {
        String sessionId = sessionManager.createSession("token stats test");
        try {
            HttpResponse<String> resp = get("/api/sessions/" + sessionId + "/tokens");
            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"totalTokens\""),
                    "/tokens 端点应含 totalTokens（由 Controller 手工组装）");
        } finally {
            sessionManager.destroySession(sessionId);
        }
    }

    // ==================== 权限规则 ====================

    /** toolName 为空应被拒。 */
    @Test
    void addRuleRejectsBlankToolName() throws Exception {
        HttpResponse<String> resp = post("/api/permissions/rules",
                "{\"toolName\":\"\",\"description\":\"*\",\"action\":\"ALLOW\"}");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":false"));
    }

    /**
     * description 为 "*" 时应落工具级规则 —— 前端「始终允许」走这条路径。
     */
    @Test
    void addRuleWithWildcardCreatesToolWideRule() throws Exception {
        try {
            HttpResponse<String> added = post("/api/permissions/rules",
                    "{\"toolName\":\"Bash\",\"description\":\"*\",\"action\":\"ALLOW\"}");
            assertEquals(200, added.statusCode());
            assertTrue(added.body().contains("\"success\":true"), added.body());

            HttpResponse<String> state = get("/api/permissions");
            assertTrue(state.body().contains("Bash"), "规则应出现在权限状态中");
        } finally {
            delete("/api/permissions/rules");
        }
    }

    /** 无效的权限模式应返回失败体而非 500。 */
    @Test
    void invalidPermissionModeIsRejected() throws Exception {
        HttpResponse<String> resp = put("/api/permissions/mode", "{\"mode\":\"NOT_A_MODE\"}");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":false"));
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

    private HttpResponse<String> delete(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return CLIENT.send(builder.timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
