package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具搜索工具 —— 在 ToolRegistry 中搜索已注册的工具。
 * <p>
 * 在 ToolRegistry 中搜索已注册的工具，按名称或描述关键字匹配。
 * 用于帮助 LLM 发现可用工具。
 */
public class ToolSearchTool implements Tool {

    /** ToolContext 中 ToolRegistry 的存储键 */
    private static final String TOOL_REGISTRY_KEY = "TOOL_REGISTRY";

    /**
     * 返回工具名称（"ToolSearch"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "ToolSearch";
    }

    /**
     * 返回工具描述，说明按名称或关键字搜索可用工具、帮助发现能力的用途。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            按名称或关键字搜索可用工具。返回匹配的工具名称及其描述。\
            当你需要确定哪些工具可用于特定任务，或用户询问可用能力时使用。
            """);
    }

    /**
     * 返回输入 JSON Schema，定义 query（必填）参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "用于匹配工具名称和描述的搜索查询。空字符串列出所有工具。"
                }
              },
              "required": ["query"]
            }""");
    }

    /**
     * 该工具仅查询工具列表、不修改任何状态，标记为只读。
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 在 ToolRegistry 中按名称或描述关键字匹配已注册工具；
     * 空查询列出全部工具，并展示匹配工具的只读标记与截断描述。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String query = (String) input.get("query");
        if (query == null) query = "";
        String queryLower = query.toLowerCase().trim();

        ToolRegistry registry = context.getOrDefault(TOOL_REGISTRY_KEY, null);
        if (registry == null) {
            return "Error: ToolRegistry is not available";
        }

        List<Tool> allTools = registry.getTools();
        List<Tool> matches;

        if (queryLower.isEmpty()) {
            matches = allTools;
        } else {
            matches = allTools.stream()
                    .filter(t -> t.name().toLowerCase().contains(queryLower)
                            || t.description().toLowerCase().contains(queryLower))
                    .collect(Collectors.toList());
        }

        if (matches.isEmpty()) {
            return String.format("""
                {"query": "%s", "total_tools": %d, "matches": 0, \
                "message": "No tools matched the query."}""",
                    escapeJson(query), allTools.size());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d tool(s)", matches.size()));
        if (!queryLower.isEmpty()) {
            sb.append(" matching \"").append(query).append("\"");
        }
        sb.append(" (").append(allTools.size()).append(" total):\n\n");

        for (Tool t : matches) {
            sb.append("• **").append(t.name()).append("**");
            if (t.isReadOnly()) sb.append(" [read-only]");
            sb.append("\n");
            // Truncate description to first 120 chars for overview
            String desc = t.description().strip();
            if (desc.length() > 120) {
                desc = desc.substring(0, 117) + "...";
            }
            sb.append("  ").append(desc).append("\n\n");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * 生成用于界面展示的执行摘要，标明搜索关键字。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔍 Searching tools: " + input.getOrDefault("query", "*");
    }

    /**
     * 转义字符串中的 JSON 特殊字符（反斜杠、引号）。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
