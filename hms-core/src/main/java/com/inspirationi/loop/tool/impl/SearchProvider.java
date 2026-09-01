package com.inspirationi.loop.tool.impl;

import java.util.List;
import java.util.Map;

/**
 * 搜索后端接口 —— WebSearchTool 的可插拔搜索提供者。
 * <p>
 * SDK 调用方可实现此接口注入自定义搜索后端（Google Custom Search、Bing API 等），
 * 并通过 {@code ToolContext.set(WebSearchTool.CTX_SEARCH_PROVIDER, myProvider)} 注册。
 * <p>
 * 默认实现为 {@link DuckDuckGoSearchProvider}（免费，无需 API Key）。
 */
@FunctionalInterface
public interface SearchProvider {

    /**
     * 执行搜索并返回结果。
     *
     * @param query      搜索查询字符串
     * @param maxResults 最大结果数
     * @param options    额外选项（API Key、区域、语言等），由调用方传入
     * @return 搜索结果列表，每个 Map 包含 title、url、snippet 等字段
     * @throws Exception 搜索失败
     */
    List<Map<String, String>> search(String query, int maxResults, Map<String, String> options) throws Exception;
}
