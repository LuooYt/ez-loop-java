package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.mcp.McpClient;
import com.inspirationi.loop.mcp.McpManager;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;

/**
 * ReadMcpResource 工具 —— 读取 MCP 服务器的指定资源。
 * <p>
 * 通过 URI 从 MCP 服务器读取资源内容。
 * 通过 URI 从 MCP 服务器读取资源内容。
 */
public class ReadMcpResourceTool implements Tool {

    /**
     * 返回工具名称（"ReadMcpResource"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "ReadMcpResource";
    }

    /**
     * 返回工具描述，说明按 URI 从 MCP 服务器读取资源内容的用途。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            从已连接 MCP（Model Context Protocol）服务器读取指定资源。
            提供资源 URI（通过 ListMcpResources 获取）来获取其内容。
            服务器名称可选——如果省略，将在所有服务器中搜索该 URI。
            """);
    }

    /**
     * 返回输入 JSON Schema，定义 uri（必填）与 server（可选）参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "uri": {
                  "type": "string",
                  "description": "要读取的资源 URI（例如 'file:///path' 或 'custom://resource'）"
                },
                "server": {
                  "type": "string",
                  "description": "可选：提供该资源的 MCP 服务器名称"
                }
              },
              "required": ["uri"]
            }""");
    }

    /**
     * 按 URI 读取 MCP 资源：若指定 server 则只尝试该服务器；
     * 否则先在已连接服务器中查找包含该资源的服务器，最后回退到尝试任意服务器读取。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String uri = (String) input.get("uri");
        String serverFilter = (String) input.getOrDefault("server", null);

        if (uri == null || uri.isBlank()) {
            return "Error: 'uri' is required. Use ListMcpResources to discover available resources.";
        }

        McpManager mcpManager = context.getOrDefault("MCP_MANAGER", null);
        if (mcpManager == null) {
            return "Error: No MCP servers configured.";
        }

        var clients = mcpManager.getClients();
        if (clients.isEmpty()) {
            return "Error: No MCP servers connected.";
        }

        // If server specified, try only that server
        if (serverFilter != null && !serverFilter.isBlank()) {
            McpClient client = clients.get(serverFilter);
            if (client == null) {
                return "Error: MCP server '" + serverFilter + "' not found. "
                        + "Available servers: " + String.join(", ", clients.keySet());
            }
            return readFromClient(client, serverFilter, uri);
        }

        // Try all connected servers
        for (var entry : clients.entrySet()) {
            McpClient client = entry.getValue();
            if (!client.isInitialized() || !client.isConnected()) continue;

            // Check if this server has the resource
            boolean hasResource = client.getResources().stream()
                    .anyMatch(r -> r.uri().equals(uri));
            if (hasResource) {
                return readFromClient(client, entry.getKey(), uri);
            }
        }

        // No server has this resource — try reading anyway (some servers allow arbitrary URIs)
        for (var entry : clients.entrySet()) {
            McpClient client = entry.getValue();
            if (!client.isInitialized() || !client.isConnected()) continue;
            try {
                String result = client.readResource(uri);
                if (result != null && !result.isBlank()) {
                    return result;
                }
            } catch (Exception ignored) {
                // Try next server
            }
        }

        return "Error: Resource '" + uri + "' not found on any connected MCP server. "
                + "Use ListMcpResources to see available resources.";
    }

    /**
     * 从指定 MCP 客户端读取资源内容，处理服务器未连接与读取失败的错误情况。
     */
    private String readFromClient(McpClient client, String serverName, String uri) {
        if (!client.isInitialized() || !client.isConnected()) {
            return "Error: MCP server '" + serverName + "' is not connected.";
        }
        try {
            String content = client.readResource(uri);
            if (content == null || content.isBlank()) {
                return "(Resource returned empty content)";
            }
            return content;
        } catch (Exception e) {
            return "Error reading resource '" + uri + "' from server '" + serverName + "': " + e.getMessage();
        }
    }

    /**
     * 生成用于界面展示的执行摘要，标明要读取的资源 URI。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        String uri = (String) input.getOrDefault("uri", "?");
        return "📖 Reading MCP resource: " + uri;
    }
}
