package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 网络搜索工具 —— 可插拔搜索后端的通用 Web SDK 搜索工具。
 * <p>
 * 默认使用 {@link DuckDuckGoSearchProvider}（免费，无需 API Key）。
 * SDK 调用方可通过 ToolContext 注入自定义 {@link SearchProvider} 实现：
 * <pre>{@code
 * toolContext.set(WebSearchTool.CTX_SEARCH_PROVIDER, new GoogleSearchProvider(apiKey));
 * }</pre>
 * <p>
 * <b>配置方式（通过 ToolContext）：</b>
 * <ul>
 *   <li>{@code WEBSEARCH_PROVIDER} — {@link SearchProvider} 实例</li>
 *   <li>{@code WEBSEARCH_API_KEY} — String 搜索 API Key（传递给 SearchProvider）</li>
 *   <li>{@code WEBSEARCH_OPTIONS} — Map&lt;String,String&gt; 搜索后端额外选项</li>
 * </ul>
 */
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    /** ToolContext key for custom SearchProvider */
    public static final String CTX_SEARCH_PROVIDER = "WEBSEARCH_PROVIDER";
    /** ToolContext key for API key */
    public static final String CTX_API_KEY = "WEBSEARCH_API_KEY";
    /** ToolContext key for extra options Map */
    public static final String CTX_OPTIONS = "WEBSEARCH_OPTIONS";

    /** 默认返回的最大搜索结果数 */
    private static final int DEFAULT_MAX_RESULTS = 8;

    /**
     * 返回工具名称（"WebSearch"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "WebSearch";
    }

    /**
     * 返回工具描述，说明进行网络搜索并返回结果的用途及可插拔搜索后端。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()),
                "搜索最新的网络信息。返回包含标题、URL 和摘要的搜索结果。"
                        + "支持可配置的搜索后端（DuckDuckGo、Google、Bing 等）。");
    }

    /**
     * 返回输入 JSON Schema，定义 query（必填）与 maxResults（可选）参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "搜索查询字符串"
                    },
                    "maxResults": {
                      "type": "integer",
                      "description": "返回的最大结果数（默认：8，最大：20）"
                    }
                  },
                  "required": ["query"]
                }
                """);
    }

    /**
     * 该工具仅发起搜索请求、不修改本地状态，标记为只读。
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 执行网络搜索：校验 query、解析并钳制 maxResults，
     * 从 ToolContext 获取可插拔 SearchProvider（默认 DuckDuckGo），
     * 注入 apiKey 后调用提供者搜索并格式化结果。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return "Error: query parameter is required";
        }

        int maxResults = DEFAULT_MAX_RESULTS;
        if (input.containsKey("maxResults")) {
            maxResults = ((Number) input.get("maxResults")).intValue();
            maxResults = Math.max(1, Math.min(maxResults, 20));
        }

        // 解析配置
        SearchProvider provider = context.get(CTX_SEARCH_PROVIDER);
        if (provider == null) {
            provider = new DuckDuckGoSearchProvider();
        }

        String apiKey = context.get(CTX_API_KEY);
        @SuppressWarnings("unchecked")
        Map<String, String> options = context.has(CTX_OPTIONS)
                ? (Map<String, String>) context.get(CTX_OPTIONS)
                : new HashMap<>();

        if (apiKey != null && !apiKey.isBlank()) {
            options.put("apiKey", apiKey);
        }

        try {
            List<Map<String, String>> results = provider.search(query, maxResults, options);
            return formatResults(results, query);
        } catch (Exception e) {
            log.debug("Search failed: query={}", query, e);
            return "Error: Search failed - " + Tool.describeError(e);
        }
    }

    /**
     * 将搜索结果列表格式化为带编号的文本，包含标题、URL 与摘要；
     * 无结果时返回提示信息。
     */
    private String formatResults(List<Map<String, String>> results, String query) {
        if (results.isEmpty()) {
            return "No results found for query: " + query + ". Try a different query.";
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map<String, String> r : results) {
            count++;
            sb.append(count).append(". ").append(r.getOrDefault("title", "Untitled")).append("\n");
            sb.append("   URL: ").append(r.getOrDefault("url", "N/A")).append("\n");
            String snippet = r.getOrDefault("snippet", "");
            if (!snippet.isBlank()) {
                sb.append("   ").append(snippet).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成用于界面展示的执行摘要，标明搜索关键字。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        String query = (String) input.getOrDefault("query", "");
        return "Searching: " + query;
    }
}
