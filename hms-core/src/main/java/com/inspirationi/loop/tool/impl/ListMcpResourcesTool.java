package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.mcp.McpClient;
import com.inspirationi.loop.mcp.McpManager;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;

/**
 * ListMcpResources 工具 —— 列出 MCP 服务器提供的资源。
 * <p>
 * 浏览所有已连接 MCP 服务器的资源列表。
 * 显示所有已连接 MCP 服务器的资源列表，包括 URI、名称、描述和 MIME 类型。
 */
public class ListMcpResourcesTool implements Tool {

    /**
     * 返回工具名称（"ListMcpResources"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "ListMcpResources";
    }

    /**
     * 返回工具描述，说明用于发现已连接 MCP 服务器可用的资源。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            列出已连接 MCP（Model Context Protocol）服务器可用的资源。
            显示所有资源的 URI、名称、描述和 MIME 类型。
            在读取资源之前用它来发现可用的数据源。可按服务器名称过滤。
            """);
    }

    /**
     * 返回输入 JSON Schema，定义可选的 server 过滤参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "server": {
                  "type": "string",
                  "description": "可选：按 MCP 服务器名称过滤资源"
                }
              }
            }""");
    }

    /**
     * 遍历所有已连接的 MCP 服务器，列出其资源（URI、名称、描述、MIME 类型）。
     * 可按服务器名过滤；未连接或无资源的服务器给出相应提示。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        McpManager mcpManager = context.getOrDefault("MCP_MANAGER", null);
        if (mcpManager == null) {
            return "No MCP servers configured.";
        }

        String serverFilter = (String) input.getOrDefault("server", null);
        var clients = mcpManager.getClients();

        if (clients.isEmpty()) {
            return "No MCP servers connected.";
        }

        StringBuilder sb = new StringBuilder();
        int totalResources = 0;

        for (var entry : clients.entrySet()) {
            String serverName = entry.getKey();
            McpClient client = entry.getValue();

            if (serverFilter != null && !serverFilter.isBlank()
                    && !serverName.equalsIgnoreCase(serverFilter)) {
                continue;
            }

            if (!client.isInitialized() || !client.isConnected()) {
                sb.append("⚠ Server '").append(serverName).append("': not connected\n");
                continue;
            }

            var resources = client.getResources();
            if (resources.isEmpty()) {
                sb.append("Server '").append(serverName).append("': no resources\n");
                continue;
            }

            sb.append("## ").append(serverName).append(" (").append(resources.size()).append(" resources)\n\n");

            for (var resource : resources) {
                sb.append("- **").append(resource.name()).append("**\n");
                sb.append("  URI: `").append(resource.uri()).append("`\n");
                if (!resource.description().isBlank()) {
                    sb.append("  ").append(resource.description()).append("\n");
                }
                sb.append("  Type: ").append(resource.mimeType()).append("\n\n");
                totalResources++;
            }
        }

        if (totalResources == 0) {
            return serverFilter != null
                    ? "No resources found for server '" + serverFilter + "'."
                    : "No MCP resources available from any connected server.";
        }

        return sb.toString().stripTrailing();
    }

    /**
     * 生成用于界面展示的执行摘要，固定返回"📋 Listing MCP resources"。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        return "📋 Listing MCP resources";
    }
}
