package com.inspirationi.loop.tool.impl;

import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * DuckDuckGo HTML 搜索提供者 —— 免费、无需 API Key。
 * <p>
 * 通过解析 DuckDuckGo HTML 搜索结果页面提取搜索结果。
 * 适合开发/测试场景。生产环境建议使用 Google/Bing API 等商业搜索后端。
 * <p>
 * 直接使用：
 * <pre>{@code
 * SearchProvider provider = new DuckDuckGoSearchProvider();
 * List<Map<String, String>> results = provider.search("query", 10, Map.of());
 * }</pre>
 * 通过 ToolContext 注册：
 * <pre>{@code
 * toolContext.set(WebSearchTool.CTX_SEARCH_PROVIDER, new DuckDuckGoSearchProvider());
 * }</pre>
 */
public class DuckDuckGoSearchProvider implements SearchProvider {

    /** DuckDuckGo HTML 搜索接口地址 */
    private static final String DDG_URL = "https://html.duckduckgo.com/html/";

    /**
     * 执行搜索：抓取 DuckDuckGo HTML 搜索页并解析出搜索结果列表。
     *
     * @param query      搜索查询字符串
     * @param maxResults 最大返回结果数
     * @param options    额外选项（可含 userAgent 等）
     * @return 搜索结果列表，每个元素含 title、url、snippet 字段
     */
    @Override
    public List<Map<String, String>> search(String query, int maxResults, Map<String, String> options)
            throws Exception {
        String html = fetchSearchPage(query, options);
        return parseResults(html, maxResults);
    }

    /**
     * 请求 DuckDuckGo 搜索页面并返回 HTML 内容，支持自定义 User-Agent。
     * 连接超时与响应超时均为 15 秒，跟随重定向。
     */
    private String fetchSearchPage(String query, Map<String, String> options) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String userAgent = options.getOrDefault("userAgent",
                "Mozilla/5.0 (compatible; HmsCore-Java/0.2)");

        var request = HttpRequest.newBuilder()
                .uri(URI.create(DDG_URL + "?q=" + encodedQuery))
                .header("User-Agent", userAgent)
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new java.io.IOException("HTTP " + response.statusCode());
        }

        return response.body();
    }

    /**
     * 从搜索结果 HTML 中解析标题、URL 与摘要。
     * URL 若为 DuckDuckGo 跳转链接（uddg= 参数）则解码还原为真实地址；
     * 主解析无结果时回退到备用解析。
     */
    private List<Map<String, String>> parseResults(String html, int maxResults) {
        List<Map<String, String>> results = new ArrayList<>();

        var resultPattern = Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>",
                Pattern.DOTALL);

        var snippetPattern = Pattern.compile(
                "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>",
                Pattern.DOTALL);

        var resultMatcher = resultPattern.matcher(html);
        var snippetMatcher = snippetPattern.matcher(html);

        while (resultMatcher.find() && results.size() < maxResults) {
            String url = resultMatcher.group(1);
            String title = stripHtml(resultMatcher.group(2));

            if (url.contains("uddg=")) {
                try {
                    String decoded = java.net.URLDecoder.decode(
                            url.substring(url.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                    int ampIdx = decoded.indexOf('&');
                    if (ampIdx > 0) decoded = decoded.substring(0, ampIdx);
                    url = decoded;
                } catch (Exception ignored) {}
            }

            String snippet = "";
            if (snippetMatcher.find()) {
                snippet = stripHtml(snippetMatcher.group(1));
            }

            Map<String, String> result = new LinkedHashMap<>();
            result.put("title", title);
            result.put("url", url);
            result.put("snippet", snippet);
            results.add(result);
        }

        if (results.isEmpty()) {
            results = parseResultsFallback(html, maxResults);
        }

        return results;
    }

    /**
     * 备用解析：从 HTML 中提取所有外部 http(s) 链接作为搜索结果。
     * 跳过 DuckDuckGo 自身域名、空标题及重复 URL。
     */
    private List<Map<String, String>> parseResultsFallback(String html, int maxResults) {
        List<Map<String, String>> results = new ArrayList<>();
        var linkPattern = Pattern.compile(
                "<a[^>]+href=\"(https?://[^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        var matcher = linkPattern.matcher(html);
        var seenUrls = new HashSet<String>();

        while (matcher.find() && results.size() < maxResults) {
            String url = matcher.group(1);
            String title = stripHtml(matcher.group(2));

            if (url.contains("duckduckgo.com") || title.isBlank() || !seenUrls.add(url)) {
                continue;
            }

            Map<String, String> result = new LinkedHashMap<>();
            result.put("title", title);
            result.put("url", url);
            result.put("snippet", "");
            results.add(result);
        }

        return results;
    }

    /**
     * 去除字符串中的 HTML 标签并解码常见 HTML 实体，得到纯文本。
     */
    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#x27;", "'")
                .replaceAll("&nbsp;", " ")
                .strip();
    }
}
