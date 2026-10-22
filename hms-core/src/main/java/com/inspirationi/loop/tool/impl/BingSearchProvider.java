package com.inspirationi.loop.tool.impl;

import java.net.URI;
import java.net.URLEncoder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bing HTML 搜索提供者 —— 免费、无需 API Key。
 * <p>
 * 抓取 {@code cn.bing.com} 的结果页并解析。选它作为
 * {@link DuckDuckGoSearchProvider} 的回落后端，是因为 DuckDuckGo 在国内网络
 * 不可达（TLS 握手被中断），而 Bing 国内站点可直连；两者都是 HTML 抓取，
 * 没有 API Key / 配额依赖。
 * <p>
 * 结果页结构（2026-10 实测）：每条结果是一个 {@code <li class="b_algo">}，标题与
 * 链接在 {@code <h2><a href>}，摘要在其后的 {@code <p class="b_lineclampN">}。
 * 页面改版会让主解析失效，因此解析失败时返回空列表而不抛异常 —— 由回落链换下一个
 * 后端，而不是把「解析不到」当成网络错误。
 */
public class BingSearchProvider implements SearchProvider {

    /** 结果页地址。国内可直连；国际访问会重定向到对应区域站点。 */
    private static final String BING_URL = "https://cn.bing.com/search";

    /** 单条结果块起点。 */
    private static final Pattern RESULT_START =
            Pattern.compile("<li[^>]*class=\"[^\"]*\\bb_algo\\b[^\"]*\"", Pattern.DOTALL);

    /** 块内标题与链接。 */
    private static final Pattern TITLE_LINK =
            Pattern.compile("<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    /** 块内摘要（Bing 用 b_lineclamp / b_paractl 等类名，统一按第一个 p 取）。 */
    private static final Pattern SNIPPET = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);

    /** HTML 标签，用于剥出纯文本。 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** 数字字符引用：{@code &#123;} 或 {@code &#x7B;}。 */
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    /**
     * 执行搜索：抓取 Bing 结果页并解析出结果列表。
     *
     * @param query      搜索查询字符串
     * @param maxResults 最大返回结果数
     * @param options    额外选项（可含 userAgent、region 等）
     */
    @Override
    public List<Map<String, String>> search(String query, int maxResults, Map<String, String> options)
            throws Exception {
        String html = fetchSearchPage(query, options);
        return parseResults(html, maxResults);
    }

    /**
     * 请求结果页并返回 HTML。连接与响应超时均为 15 秒，跟随重定向。
     */
    private String fetchSearchPage(String query, Map<String, String> options) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String region = options.getOrDefault("region", "zh-CN");

        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String userAgent = options.getOrDefault("userAgent",
                "Mozilla/5.0 (compatible; HmsCore-Java/0.2)");

        var request = HttpRequest.newBuilder()
                .uri(URI.create(BING_URL + "?q=" + encodedQuery
                        + "&setlang=" + URLEncoder.encode(region, StandardCharsets.UTF_8)))
                .header("User-Agent", userAgent)
                .header("Accept-Language", region)
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
     * 从结果页 HTML 中解析标题、URL 与摘要。
     * <p>
     * 包级可见以便单测直接喂 HTML，不必起 HTTP 服务。
     */
    List<Map<String, String>> parseResults(String html, int maxResults) {
        List<Map<String, String>> results = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return results;
        }

        // 按 b_algo 块起点切分：块内再找标题与摘要，避免把侧栏、相关搜索混进结果。
        List<Integer> starts = new ArrayList<>();
        Matcher startMatcher = RESULT_START.matcher(html);
        while (startMatcher.find()) {
            starts.add(startMatcher.start());
        }

        var seenUrls = new HashSet<String>();
        for (int i = 0; i < starts.size() && results.size() < maxResults; i++) {
            String block = html.substring(starts.get(i),
                    i + 1 < starts.size() ? starts.get(i + 1) : html.length());

            Matcher titleMatcher = TITLE_LINK.matcher(block);
            if (!titleMatcher.find()) {
                continue;
            }

            String url = normalizeUrl(titleMatcher.group(1));
            String title = stripHtml(titleMatcher.group(2));
            if (url.isBlank() || title.isBlank() || !seenUrls.add(url)) {
                continue;
            }

            String snippet = "";
            Matcher snippetMatcher = SNIPPET.matcher(block);
            if (snippetMatcher.find()) {
                snippet = stripHtml(snippetMatcher.group(1));
            }

            Map<String, String> result = new LinkedHashMap<>();
            result.put("title", title);
            result.put("url", url);
            result.put("snippet", snippet);
            results.add(result);
        }

        return results;
    }

    /** 相对链接补上 Bing 域名，HTML 实体解码后返回。 */
    private String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String decoded = url.replace("&amp;", "&").strip();
        if (decoded.startsWith("//")) {
            return "https:" + decoded;
        }
        if (decoded.startsWith("/")) {
            return "https://cn.bing.com" + decoded;
        }
        return decoded;
    }

    /**
     * 去除 HTML 标签并解码实体，得到纯文本。
     * <p>
     * 除常见命名实体外还要处理数字引用：Bing 的摘要里大量出现 {@code &#0183;}/{@code &ensp;}
     * 这类分隔符，不解码就会被当成正文喂给模型。
     */
    private String stripHtml(String html) {
        if (html == null) return "";
        String text = HTML_TAG.matcher(html).replaceAll("")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&ensp;", " ")
                .replace("&emsp;", " ")
                .replace("&middot;", "·")
                .replace("&hellip;", "…")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–");
        return decodeNumericReferences(text).strip();
    }

    /** 把 {@code &#123;} / {@code &#x1F600;} 还原成对应字符，非法码点原样保留。 */
    private String decodeNumericReferences(String text) {
        Matcher m = NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group(1);
            int codePoint;
            try {
                codePoint = digits.startsWith("x") || digits.startsWith("X")
                        ? Integer.parseInt(digits.substring(1), 16)
                        : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            if (!Character.isValidCodePoint(codePoint)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        new String(Character.toChars(codePoint))));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
