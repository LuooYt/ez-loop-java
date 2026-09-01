package com.inspirationi.loop.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 管理器 —— 管理多个 MCP 服务器连接的统一入口。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理服务器连接的生命周期（连接、断开）</li>
 *   <li>聚合所有服务器的工具和资源供上层使用</li>
 *   <li>路由工具调用到正确的服务器</li>
 * </ul>
 * <p>
 * SDK 场景：不再从文件（mcp.json）加载，完全由 API 调用方通过
 * {@link #connect(String, String, List, Map)} 和 {@link #connectHttp(String, String, Map)}
 * 编程式注册 MCP 服务器。
 *
 * @see McpClient
 * @see StdioTransport
 */
public class McpManager implements AutoCloseable {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(McpManager.class);

    /** 已连接的 MCP 客户端：serverName -> McpClient */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    /**
     * 每个服务器名对应的连接锁 —— 让同名的 {@link #connect} 串行执行。
     * <p>
     * 条目不清理：键是服务器名，数量由集成方配置决定（通常个位数），
     * 且断连后重连仍需复用同一把锁。
     */
    private final Map<String, Object> connectLocks = new ConcurrentHashMap<>();

    /** 工具名称到服务器名称的映射：toolName -> serverName（用于路由调用） */
    private final Map<String, String> toolToServer = new ConcurrentHashMap<>();

    /**
     * 连接单个 MCP 服务器。
     *
     * @param name    服务器名称标识
     * @param command 服务器可执行命令
     * @param args    命令参数列表
     * @param env     环境变量（可为 {@code null}）
     * @return 已初始化的 MCP 客户端
     * @throws McpException 连接或初始化失败
     */
    public McpClient connect(String name, String command, List<String> args, Map<String, String> env)
            throws McpException {
        log.info("Connecting MCP server '{}': {} {}", name, command, String.join(" ", args));
        return doConnect(name, () -> {
            StdioTransport transport = new StdioTransport(command, args, env);
            transport.start();
            return transport;
        });
    }

    /**
     * 建立并注册一个 MCP 连接 —— stdio 与 HTTP+SSE 两条路径的公共骨架。
     * <p>
     * 抽出公共实现而非各写一遍，是因为两条路径必须共享三处不可省略的处理：
     * <ul>
     *   <li><b>同名连接串行化</b> —— 用 {@code containsKey} 判重是 check-then-act，
     *       并发调用会各自建一个连接，后 {@code put} 者覆盖前者；被覆盖的
     *       {@link McpClient} 再无引用，{@code disconnect} 和 {@code close} 都碰不到它。
     *       stdio 泄漏的是 OS 子进程与读线程，HTTP 泄漏的是 SSE 监听线程与
     *       {@code HttpSseTransport} 内的单线程池 —— 两者都不会被回收。</li>
     *   <li><b>初始化失败必关传输层</b> —— 否则同样泄漏上述资源。</li>
     *   <li><b>工具名冲突告警</b> —— 后连接的服务器会静默顶掉同名工具的路由，
     *       不告警则排查时无从下手。</li>
     * </ul>
     *
     * @param name             服务器名称
     * @param transportFactory 传输层工厂 —— 需在返回前完成启动/连接
     */
    private McpClient doConnect(String name, TransportFactory transportFactory) throws McpException {
        Object nameLock = connectLocks.computeIfAbsent(name, k -> new Object());
        synchronized (nameLock) {
            // 如果已存在，先断开
            if (clients.containsKey(name)) {
                log.info("MCP server '{}' already exists, disconnecting old connection", name);
                try {
                    disconnect(name);
                } catch (Exception e) {
                    log.warn("Exception disconnecting old MCP connection '{}': {}", name, e.getMessage());
                }
            }

            McpTransport transport = null;
            McpClient client;
            try {
                transport = transportFactory.create();
                client = new McpClient(name, transport);
                client.initialize();
            } catch (Exception e) {
                // 初始化失败时必须关闭传输层，防止子进程 / SSE 线程泄漏
                if (transport != null) {
                    try {
                        transport.close();
                    } catch (Exception suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw (e instanceof McpException mcp) ? mcp
                        : new McpException("Failed to connect MCP server '" + name + "': " + e.getMessage(), e);
            }

            // 注册客户端
            clients.put(name, client);

            // 建立工具 -> 服务器的映射
            for (McpClient.McpTool tool : client.getTools()) {
                String existingServer = toolToServer.get(tool.name());
                if (existingServer != null) {
                    log.warn("MCP tool name conflict: '{}' exists in both server '{}' and '{}', using latter",
                            tool.name(), existingServer, name);
                }
                toolToServer.put(tool.name(), name);
            }

            log.info("MCP server '{}' connected successfully", name);
            return client;
        }
    }

    /** 传输层工厂 —— 实现需在返回前完成启动/连接，失败时抛异常。 */
    @FunctionalInterface
    private interface TransportFactory {
        McpTransport create() throws Exception;
    }

    /**
     * 断开 MCP 服务器连接。
     *
     * @param name 服务器名称
     * @throws McpException 断开失败
     */
    public void disconnect(String name) throws McpException {
        McpClient client = clients.get(name);
        if (client == null) {
            throw new McpException("MCP server '" + name + "' does not exist");
        }

        // 先关闭资源再移除（防止 close() 异常导致 client 从 map 泄漏）
        try {
            client.close();
        } catch (Exception e) {
            throw new McpException("Exception disconnecting MCP server '" + name + "': " + e.getMessage(), e);
        } finally {
            // 无论 close 成功或失败，都要清理映射
            clients.remove(name);
            toolToServer.entrySet().removeIf(entry -> entry.getValue().equals(name));
        }

        log.info("MCP server '{}' disconnected", name);
    }

    /**
     * 获取所有已连接的客户端（不可变视图）。
     */
    public Map<String, McpClient> getClients() {
        return Collections.unmodifiableMap(clients);
    }

    /**
     * 获取指定服务器的客户端。
     *
     * @param name 服务器名称
     * @return 客户端实例，若不存在则返回 {@link Optional#empty()}
     */
    public Optional<McpClient> getClient(String name) {
        return Optional.ofNullable(clients.get(name));
    }

    /**
     * 获取所有 MCP 工具（合并所有服务器的工具）。
     *
     * @return 所有已发现的工具列表
     */
    public List<McpClient.McpTool> getAllTools() {
        return clients.values().stream()
                .filter(McpClient::isInitialized)
                .flatMap(client -> client.getTools().stream())
                .toList();
    }

    /**
     * 获取指定服务器的工具。
     *
     * @param serverName 服务器名称
     * @return 工具列表，若服务器不存在则返回空列表
     */
    public List<McpClient.McpTool> getServerTools(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null || !client.isInitialized()) {
            return List.of();
        }
        return List.copyOf(client.getTools());
    }

    /**
     * 获取所有 MCP 资源（合并所有服务器的资源）。
     *
     * @return 所有已发现的资源列表
     */
    public List<McpClient.McpResource> getAllResources() {
        return clients.values().stream()
                .filter(McpClient::isInitialized)
                .flatMap(client -> client.getResources().stream())
                .toList();
    }

    /**
     * 获取指定服务器的资源。
     *
     * @param serverName 服务器名称
     * @return 资源列表，若服务器不存在则返回空列表
     */
    public List<McpClient.McpResource> getServerResources(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null || !client.isInitialized()) {
            return List.of();
        }
        return List.copyOf(client.getResources());
    }

    /**
     * 调用 MCP 工具 —— 自动路由到拥有该工具的服务器。
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @return 工具执行结果
     * @throws McpException 工具不存在或调用失败
     */
    public String callTool(String toolName, Map<String, Object> args) throws McpException {
        String serverName = toolToServer.get(toolName);
        if (serverName == null) {
            throw new McpException("MCP tool not found: " + toolName);
        }
        return callTool(serverName, toolName, args);
    }

    /**
     * 调用指定服务器的 MCP 工具。
     *
     * @param serverName 服务器名称
     * @param toolName   工具名称
     * @param args       工具参数
     * @return 工具执行结果
     * @throws McpException 服务器不存在或调用失败
     */
    public String callTool(String serverName, String toolName, Map<String, Object> args)
            throws McpException {
        McpClient client = clients.get(serverName);
        if (client == null) {
            throw new McpException("MCP server '" + serverName + "' does not exist");
        }
        if (!client.isInitialized()) {
            throw new McpException("MCP server '" + serverName + "' not yet initialized");
        }
        return client.callTool(toolName, args);
    }

    /**
     * 查找工具所属的服务器名称。
     *
     * @param toolName 工具名称
     * @return 服务器名称，若不存在则返回 {@link Optional#empty()}
     */
    public Optional<String> findServerForTool(String toolName) {
        return Optional.ofNullable(toolToServer.get(toolName));
    }

    /**
     * 获取状态摘要（用于 /mcp 命令或状态显示）。
     *
     * @return 格式化的状态摘要文本
     */
    public String getSummary() {
        if (clients.isEmpty()) {
            return "  No connected MCP servers";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            String name = entry.getKey();
            McpClient client = entry.getValue();

            String status;
            if (client.isConnected() && client.isInitialized()) {
                status = "✅ Connected";
            } else if (client.isConnected()) {
                status = "🔄 Connecting";
            } else {
                status = "❌ Disconnected";
            }

            sb.append(String.format("  %-20s %s (%d tools, %d resources)%n",
                    name, status, client.getTools().size(), client.getResources().size()));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 关闭所有 MCP 连接并清理映射。
     * <p>
     * 逐个关闭各客户端；若存在连接关闭失败，则聚合所有异常后统一抛出。
     *
     * @throws Exception 存在连接关闭失败时抛出聚合异常
     */
    @Override
    public void close() throws Exception {
        log.info("Closing all MCP connections...");
        List<Exception> errors = new ArrayList<>();

        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                errors.add(e);
                log.error("Exception closing MCP server '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        clients.clear();
        toolToServer.clear();

        if (!errors.isEmpty()) {
            McpException ex = new McpException("Errors closing MCP manager: " + errors.size() + " errors");
            errors.forEach(ex::addSuppressed);
            throw ex;
        }

        log.info("All MCP connections closed");
    }

    /**
     * 连接 HTTP+SSE MCP 服务器。
     *
     * @param name 服务器名称
     * @param url  服务器 URL
     * @param env  环境变量（用于请求头等）
     * @return 已初始化的 MCP 客户端
     * @throws McpException 连接或初始化失败
     */
    public McpClient connectHttp(String name, String url, Map<String, String> env) throws McpException {
        log.info("Connecting MCP HTTP server '{}': {}", name, url);

        // 从 env 中提取认证头
        Map<String, String> headers = new HashMap<>();
        if (env != null) {
            String authToken = env.get("AUTHORIZATION");
            if (authToken != null) {
                headers.put("Authorization", authToken);
            }
            String apiKey = env.get("API_KEY");
            if (apiKey != null) {
                headers.put("X-API-Key", apiKey);
            }
        }

        return doConnect(name, () -> {
            HttpSseTransport transport = new HttpSseTransport(url, headers, null);
            transport.connect();
            return transport;
        });
    }

}
