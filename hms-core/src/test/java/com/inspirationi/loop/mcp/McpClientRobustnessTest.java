package com.inspirationi.loop.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpClient} 对不规范服务器响应与参数的健壮性。
 * <p>
 * MCP 服务器是<b>外部进程</b>（{@code npx} 拉起的第三方包、远端 HTTP 服务），
 * 其响应不可信：字段可能缺失、类型可能不符。工具参数则来自大模型生成的 JSON，
 * 同样可能含 null 值。任何一处解析崩溃都会让整个 MCP 集成不可用。
 */
class McpClientRobustnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 按脚本返回预设响应的假传输层。 */
    private static class ScriptedTransport implements McpTransport {
        private final Map<String, String> responsesByMethod = new HashMap<>();
        private final List<String> sentRequests = new ArrayList<>();

        ScriptedTransport on(String method, String jsonResult) {
            responsesByMethod.put(method, jsonResult);
            return this;
        }

        @Override
        public JsonNode sendRequest(String jsonRpcRequest) throws McpException {
            sentRequests.add(jsonRpcRequest);
            try {
                JsonNode req = MAPPER.readTree(jsonRpcRequest);
                String method = req.get("method").asText();
                String result = responsesByMethod.get(method);
                if (result == null) {
                    // 未预设 → 模拟 method not found
                    return MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":"
                            + req.get("id") + ",\"error\":{\"code\":-32601,"
                            + "\"message\":\"Method not found\"}}");
                }
                return MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":"
                        + req.get("id") + ",\"result\":" + result + "}");
            } catch (Exception e) {
                throw new McpException("scripted transport failure: " + e.getMessage(), e);
            }
        }

        @Override
        public void sendNotification(String jsonRpcNotification) {
            sentRequests.add(jsonRpcNotification);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void close() {
        }

        List<String> sent() {
            return sentRequests;
        }
    }

    private static final String CAPABILITIES_BOTH = """
            {"protocolVersion":"2024-11-05",
             "capabilities":{"tools":{},"resources":{}},
             "serverInfo":{"name":"fake","version":"1"}}""";

    @Test
    void toolEntryMissingNameDoesNotAbortDiscovery() throws Exception {
        // 第一个条目缺 name（不规范服务器），后面的合法条目仍应被发现
        ScriptedTransport transport = new ScriptedTransport()
                .on("initialize", CAPABILITIES_BOTH)
                .on("tools/list", """
                        {"tools":[
                          {"description":"no name field"},
                          {"name":"valid_tool","description":"ok"}
                        ]}""")
                .on("resources/list", "{\"resources\":[]}");

        McpClient client = new McpClient("fake", transport);
        assertDoesNotThrow(client::initialize,
                "单个工具条目缺 name 不应让整个初始化抛异常");

        assertTrue(client.getTools().stream().anyMatch(t -> "valid_tool".equals(t.name())),
                "缺字段条目之后的合法工具仍应被发现，实际发现："
                        + client.getTools().stream().map(McpClient.McpTool::name).toList());
    }

    @Test
    void resourceEntryMissingUriDoesNotAbortDiscovery() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .on("initialize", CAPABILITIES_BOTH)
                .on("tools/list", "{\"tools\":[]}")
                .on("resources/list", """
                        {"resources":[
                          {"name":"no uri field"},
                          {"uri":"file:///ok","name":"valid"}
                        ]}""");

        McpClient client = new McpClient("fake", transport);
        assertDoesNotThrow(client::initialize,
                "单个资源条目缺 uri 不应让整个初始化抛异常");

        assertTrue(client.getResources().stream().anyMatch(r -> "file:///ok".equals(r.uri())),
                "缺字段条目之后的合法资源仍应被发现，实际："
                        + client.getResources().stream().map(McpClient.McpResource::uri).toList());
    }

    @Test
    void nullValuedToolArgumentDoesNotThrowNpe() throws Exception {
        // 大模型生成的参数完全可能含 null 值（如可选字段显式传 null）。
        // Map.of() 不接受 null value —— 若实现用它包装 params 会抛 NPE。
        ScriptedTransport transport = new ScriptedTransport()
                .on("initialize", CAPABILITIES_BOTH)
                .on("tools/list", """
                        {"tools":[{"name":"echo","description":"d",
                          "inputSchema":{"type":"object"}}]}""")
                .on("resources/list", "{\"resources\":[]}")
                .on("tools/call", "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");

        McpClient client = new McpClient("fake", transport);
        client.initialize();

        Map<String, Object> argsWithNull = new HashMap<>();
        argsWithNull.put("present", "value");
        argsWithNull.put("optional", null);

        String result = assertDoesNotThrow(() -> client.callTool("echo", argsWithNull),
                "含 null 值的工具参数不应抛 NullPointerException");
        assertEquals("ok", result.strip());
    }

    @Test
    void nullArgumentsMapIsAccepted() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .on("initialize", CAPABILITIES_BOTH)
                .on("tools/list", """
                        {"tools":[{"name":"noargs","description":"d",
                          "inputSchema":{"type":"object"}}]}""")
                .on("resources/list", "{\"resources\":[]}")
                .on("tools/call", "{\"content\":[{\"type\":\"text\",\"text\":\"fine\"}]}");

        McpClient client = new McpClient("fake", transport);
        client.initialize();

        assertDoesNotThrow(() -> client.callTool("noargs", null),
                "arguments 为 null 应被当作空参数");
    }

    @Test
    void serverWithoutToolsCapabilityStillInitializes() throws Exception {
        // 服务器只声明 resources，tools/list 返回 method-not-found
        ScriptedTransport transport = new ScriptedTransport()
                .on("initialize", """
                        {"protocolVersion":"2024-11-05",
                         "capabilities":{"resources":{}},
                         "serverInfo":{"name":"fake","version":"1"}}""")
                .on("resources/list", "{\"resources\":[{\"uri\":\"file:///a\"}]}");

        McpClient client = new McpClient("fake", transport);
        assertDoesNotThrow(client::initialize,
                "不支持 tools/list 的服务器仍应完成初始化");
        assertTrue(client.getTools().isEmpty());
        assertTrue(client.getResources().stream().anyMatch(r -> "file:///a".equals(r.uri())));
    }

    @Test
    void callingUnknownToolFailsWithClearMessage() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .on("initialize", CAPABILITIES_BOTH)
                .on("tools/list", "{\"tools\":[]}")
                .on("resources/list", "{\"resources\":[]}");

        McpClient client = new McpClient("fake", transport);
        client.initialize();

        McpException e = org.junit.jupiter.api.Assertions.assertThrows(
                McpException.class, () -> client.callTool("nonexistent", Map.of()));
        assertTrue(e.getMessage().contains("nonexistent"),
                "错误信息应指出是哪个工具，实际：" + e.getMessage());
    }

    @Test
    void callToolBeforeInitializeIsRejected() {
        McpClient client = new McpClient("fake", new ScriptedTransport());
        McpException e = org.junit.jupiter.api.Assertions.assertThrows(
                McpException.class, () -> client.callTool("any", Map.of()));
        assertTrue(e.getMessage().toLowerCase().contains("initial"),
                "未初始化时的错误应说明原因，实际：" + e.getMessage());
    }

    @Test
    void requestIdsAreUniquePerRequest() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .on("initialize", CAPABILITIES_BOTH)
                .on("tools/list", "{\"tools\":[]}")
                .on("resources/list", "{\"resources\":[]}");

        McpClient client = new McpClient("fake", transport);
        client.initialize();

        // JSON-RPC 要求同一连接上未完成请求的 id 不重复，
        // 否则响应无法正确配对（StdioTransport 按 id 查 pendingRequests）
        List<String> ids = new ArrayList<>();
        for (String raw : transport.sent()) {
            JsonNode node = MAPPER.readTree(raw);
            if (node.has("id")) {
                ids.add(node.get("id").asText());
            }
        }
        assertFalse(ids.isEmpty(), "应至少发出一个带 id 的请求");
        assertEquals(ids.size(), ids.stream().distinct().count(),
                "请求 id 不应重复，实际：" + ids);
    }
}
