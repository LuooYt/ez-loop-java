package com.inspirationi.loop.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * MCP 客户端 —— 管理与单个 MCP 服务器的通信和工具/资源发现。
 * <p>
 * 负责与单个 MCP 服务器的完整生命周期管理：
 * <ol>
 *   <li>通过 {@link McpTransport} 建立连接</li>
 *   <li>发送 {@code initialize} 握手请求</li>
 *   <li>发现服务器提供的工具（{@code tools/list}）和资源（{@code resources/list}）</li>
 *   <li>调用工具（{@code tools/call}）和读取资源（{@code resources/read}）</li>
 * </ol>
 * <p>
 * MCP 协议使用 JSON-RPC 2.0 格式通信。
 *
 * @see McpTransport
 * @see McpManager
 */
public class McpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON-RPC 请求 ID 生成器 */
    private final AtomicInteger idCounter = new AtomicInteger(1);

    /** 服务器名称标识 */
    private final String serverName;

    /** 底层传输层 */
    private final McpTransport transport;

    /** 已发现的工具集合：toolName -> McpTool */
    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    /** 已发现的资源集合：uri -> McpResource */
    private final Map<String, McpResource> resources = new ConcurrentHashMap<>();

    /** 服务器能力信息 */
    private volatile JsonNode serverCapabilities;

    /** 服务器信息 */
    private volatile JsonNode serverInfo;

    /** 是否已完成初始化 */
    private volatile boolean initialized = false;

    /**
     * 创建 MCP 客户端。
     *
     * @param serverName 服务器标识名称
     * @param transport  传输层实现
     */
    public McpClient(String serverName, McpTransport transport) {
        this.serverName = Objects.requireNonNull(serverName, "Server name cannot be null");
        this.transport = Objects.requireNonNull(transport, "Transport cannot be null");
    }

    /**
     * 初始化连接 —— MCP 协议握手流程。
     * <p>
     * 步骤：
     * <ol>
     *   <li>发送 {@code initialize} 请求，声明客户端能力和协议版本</li>
     *   <li>解析服务器返回的能力信息</li>
     *   <li>发送 {@code notifications/initialized} 通知</li>
     *   <li>发现服务器提供的工具和资源</li>
     * </ol>
     *
     * @throws McpException 初始化失败
     */
    public void initialize() throws McpException {
        log.info("Initializing MCP server '{}'...", serverName);

        // 1. 发送 initialize 请求
        int initId = nextId();
        var initRequest = Map.of(
                "jsonrpc", "2.0",
                "id", initId,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "hms-core",
                                "version", "1.0.0"
                        )
                )
        );

        JsonNode response;
        try {
            response = transport.sendRequest(MAPPER.writeValueAsString(initRequest));
        } catch (Exception e) {
            throw new McpException("MCP initialize request failed: " + e.getMessage(), e);
        }

        // 2. 解析服务器能力
        JsonNode result = response.get("result");
        if (result != null) {
            serverCapabilities = result.get("capabilities");
            serverInfo = result.get("serverInfo");
            String serverVersion = result.has("protocolVersion")
                    ? result.get("protocolVersion").asText() : "unknown";
            log.info("MCP server '{}' protocol version: {}", serverName, serverVersion);
            if (serverInfo != null) {
                log.info("MCP server info: {}", serverInfo);
            }
        }

        // 3. 发送 initialized 通知
        var initializedNotif = Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"
        );
        try {
            transport.sendNotification(MAPPER.writeValueAsString(initializedNotif));
        } catch (Exception e) {
            throw new McpException("Failed to send initialized notification: " + e.getMessage(), e);
        }

        // 4. 发现工具
        discoverTools();

        // 5. 发现资源
        discoverResources();

        initialized = true;
        log.info("MCP server '{}' initialization complete: {} tools, {} resources",
                serverName, tools.size(), resources.size());
    }

    /** 发现服务器提供的工具 —— 发送 {@code tools/list} 请求。 */
    private void discoverTools() {
        discover("tools/list", "tools", "tool", toolNode -> {
            String name = textOrNull(toolNode, "name");
            if (name == null) {
                return false;   // 无可用名称 → 计入 skipped
            }
            String description = textOrDefault(toolNode, "description", "");
            tools.put(name, new McpTool(name, description, toolNode.get("inputSchema")));
            log.debug("Discovered MCP tool: {} - {}", name, description);
            return true;
        });
    }

    /** 发现服务器提供的资源 —— 发送 {@code resources/list} 请求。 */
    private void discoverResources() {
        // resources 能力未声明时直接跳过：与 tools 不同，资源不是必备能力，
        // 多数服务器不实现 resources/list，无谓的请求只会换回一个 -32601。
        if (serverCapabilities != null && !serverCapabilities.has("resources")) {
            log.debug("MCP server '{}' did not declare resources capability, skipping discovery", serverName);
            return;
        }

        discover("resources/list", "resources", "resource", resNode -> {
            String uri = textOrNull(resNode, "uri");
            if (uri == null) {
                return false;
            }
            String name = textOrDefault(resNode, "name", uri);
            String description = textOrDefault(resNode, "description", "");
            String mimeType = textOrDefault(resNode, "mimeType", "text/plain");
            resources.put(uri, new McpResource(uri, name, description, mimeType));
            log.debug("Discovered MCP resource: {} ({})", name, uri);
            return true;
        });
    }

    /**
     * {@code &#42;/list} 类发现请求的公共骨架：发请求 → 取数组 → 逐条交给
     * {@code entryParser} → 统计跳过数 → 吞掉「不支持该方法」。
     * <p>
     * <b>逐条容错是必需的</b>：MCP 服务器是外部进程，其输出不受本项目控制。
     * 一个字段缺失或为 JSON null 的条目不应让整批发现失败 —— 这也是不直接用
     * 严格 POJO 反序列化的原因。
     * <p>
     * 发现失败不抛异常：{@code &#42;/list} 未被实现（JSON-RPC -32601）属于正常情况，
     * 初始化不应因此中断。
     *
     * @param method      JSON-RPC 方法名，如 {@code tools/list}
     * @param arrayField  result 中承载数组的字段名，如 {@code tools}
     * @param entryLabel  日志中对单个条目的称呼，如 {@code tool}
     * @param entryParser 解析并登记单个条目，返回 {@code false} 表示该条目不可用
     */
    private void discover(String method, String arrayField, String entryLabel,
                          Predicate<JsonNode> entryParser) {
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", nextId(),
                "method", method,
                "params", Map.of()
        );

        try {
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");
            JsonNode arrayNode = result != null ? result.get(arrayField) : null;
            if (arrayNode == null || !arrayNode.isArray()) {
                return;
            }

            int skipped = 0;
            int accepted = 0;
            for (JsonNode entry : arrayNode) {
                if (entryParser.test(entry)) {
                    accepted++;
                } else {
                    skipped++;
                    log.warn("MCP server '{}' returned an unusable {} entry, skipping it: {}",
                            serverName, entryLabel, entry);
                }
            }
            if (skipped > 0) {
                log.warn("MCP server '{}': skipped {} malformed {} entries, {} usable",
                        serverName, skipped, entryLabel, accepted);
            }
        } catch (McpException e) {
            if (e.isJsonRpcError() && e.getErrorCode() == -32601) {
                log.debug("MCP server '{}' does not support {}", serverName, method);
            } else {
                log.warn("Failed to discover MCP {}s: {}", entryLabel, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("MCP {} discovery serialization exception: {}", entryLabel, e.getMessage());
        }
    }

    /**
     * 调用 MCP 工具 —— 发送 {@code tools/call} 请求。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数（键值对）
     * @return 工具执行结果文本
     * @throws McpException 调用失败或工具不存在
     */
    public String callTool(String toolName, Map<String, Object> arguments) throws McpException {
        if (!initialized) {
            throw new McpException("MCP client not yet initialized");
        }
        if (!tools.containsKey(toolName)) {
            throw new McpException("MCP tool does not exist: " + toolName);
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "tools/call",
                "params", Map.of(
                        "name", toolName,
                        "arguments", arguments != null ? arguments : Map.of()
                )
        );

        try {
            log.debug("Calling MCP tool: {} (args: {})", toolName, arguments);
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");

            if (result == null) {
                return "";
            }

            // MCP tools/call 返回 { content: [{ type: "text", text: "..." }, ...] }
            if (result.has("content")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode contentItem : result.get("content")) {
                    String type = contentItem.has("type") ? contentItem.get("type").asText() : "text";
                    if ("text".equals(type) && contentItem.has("text")) {
                        if (!sb.isEmpty()) {
                            sb.append("\n");
                        }
                        sb.append(contentItem.get("text").asText());
                    }
                }

                // 检查 isError 标志
                if (result.has("isError") && result.get("isError").asBoolean()) {
                    throw new McpException("MCP tool '" + toolName + "' execution error: " + sb);
                }

                return sb.toString();
            }

            // 兜底：直接返回 result 的文本形式
            return result.toString();

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Failed to call MCP tool '" + toolName + "': " + e.getMessage(), e);
        }
    }

    /**
     * 读取 MCP 资源 —— 发送 {@code resources/read} 请求。
     *
     * @param uri 资源 URI
     * @return 资源内容文本
     * @throws McpException 读取失败或资源不存在
     */
    public String readResource(String uri) throws McpException {
        if (!initialized) {
            throw new McpException("MCP client not yet initialized");
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "resources/read",
                "params", Map.of("uri", uri)
        );

        try {
            log.debug("Reading MCP resource: {}", uri);
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");

            if (result == null) {
                return "";
            }

            // MCP resources/read 返回 { contents: [{ uri, text/blob }] }
            if (result.has("contents")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode contentItem : result.get("contents")) {
                    if (contentItem.has("text")) {
                        if (!sb.isEmpty()) {
                            sb.append("\n");
                        }
                        sb.append(contentItem.get("text").asText());
                    }
                }
                return sb.toString();
            }

            return result.toString();

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Failed to read MCP resource '" + uri + "': " + e.getMessage(), e);
        }
    }

    /**
     * 获取所有已发现的工具（不可变视图）。
     */
    public Collection<McpTool> getTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 获取所有已发现的资源（不可变视图）。
     */
    public Collection<McpResource> getResources() {
        return Collections.unmodifiableCollection(resources.values());
    }

    /**
     * 按名称查找工具。
     *
     * @param toolName 工具名称
     * @return 工具定义，若不存在则返回 {@link Optional#empty()}
     */
    public Optional<McpTool> findTool(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    /** 获取服务器名称标识 */
    public String getServerName() {
        return serverName;
    }

    /** 是否已完成初始化 */
    public boolean isInitialized() {
        return initialized;
    }

    /** 传输层是否仍然连接 */
    public boolean isConnected() {
        return transport.isConnected();
    }

    /** 获取服务器能力信息 */
    public JsonNode getServerCapabilities() {
        return serverCapabilities;
    }

    /** 获取服务器信息 */
    public JsonNode getServerInfo() {
        return serverInfo;
    }

    /**
     * 关闭客户端：重置初始化状态、清理工具/资源缓存并关闭底层传输层。
     */
    @Override
    public void close() throws Exception {
        initialized = false;
        tools.clear();
        resources.clear();
        transport.close();
        log.info("MCP client '{}' closed", serverName);
    }

    /** 生成下一个 JSON-RPC 请求 ID */
    private int nextId() {
        return idCounter.getAndIncrement();
    }

    /**
     * 读取节点的字符串字段，缺失 / JSON null / 空白一律返回 {@code null}。
     * <p>
     * 用于必填字段的容错解析：直接 {@code get(field).asText()} 在字段缺失时抛 NPE，
     * 而 {@code asText()} 对 JSON null 会返回字符串 {@code "null"} —— 两者都会
     * 污染后续逻辑（前者拖垮整批发现，后者产生名为 "null" 的幽灵工具）。
     */
    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return (text == null || text.isBlank()) ? null : text;
    }

    /**
     * 读取节点的可选字符串字段，缺失 / JSON null / 空白时返回 {@code fallback}。
     * <p>
     * 走 {@link #textOrNull} 的严格判断而非 {@code has(field) ? get(field).asText() : d}：
     * 后者在字段存在但值为 JSON null 时会得到字符串 {@code "null"}，于是工具描述、
     * 资源 mimeType 这类字段会带着 "null" 一路进到提示词里。
     */
    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String text = textOrNull(node, field);
        return text != null ? text : fallback;
    }

    // ========== 内部记录类型 ==========

    /**
     * MCP 工具定义 —— 服务器暴露的可调用工具。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param inputSchema 输入参数的 JSON Schema
     */
    public record McpTool(String name, String description, JsonNode inputSchema) {
    }

    /**
     * MCP 资源定义 —— 服务器暴露的可读取资源。
     *
     * @param uri         资源 URI
     * @param name        资源名称
     * @param description 资源描述
     * @param mimeType    MIME 类型
     */
    public record McpResource(String uri, String name, String description, String mimeType) {
    }
}
