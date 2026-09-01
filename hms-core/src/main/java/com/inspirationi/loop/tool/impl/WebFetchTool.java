package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页获取工具 —— 通用 Web SDK 的 HTTP 内容拉取工具。
 * <p>
 * 使用 HTTP GET 获取指定 URL 的内容，自动将 HTML 简化为纯文本。
 * 支持大小限制、超时控制、HTTP 代理、自定义 Header 注入和重试机制。
 * <p>
 * <b>配置方式（通过 ToolContext）：</b>
 * <ul>
 *   <li>{@code WEBFETCH_PROXY_HOST} / {@code WEBFETCH_PROXY_PORT} — HTTP 代理</li>
 *   <li>{@code WEBFETCH_CUSTOM_HEADERS} — Map&lt;String,String&gt; 自定义请求头</li>
 *   <li>{@code WEBFETCH_USER_AGENT} — String 自定义 User-Agent</li>
 *   <li>{@code WEBFETCH_MAX_RETRIES} — Integer 最大重试次数（默认 2）</li>
 *   <li>{@code WEBFETCH_RETRY_DELAY_MS} — Long 重试延迟毫秒（默认 1000）</li>
 * </ul>
 */
public class WebFetchTool implements Tool {

    /** 最大响应体大小：100KB */
    private static final int MAX_BODY_SIZE = 100 * 1024;

    /** HTTP 请求超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** 默认 User-Agent 标识 */
    private static final String DEFAULT_USER_AGENT = "HmsCore-Java/0.2 (WebFetchTool)";

    /** 默认最大重试次数 */
    private static final int DEFAULT_MAX_RETRIES = 2;

    /** 默认重试延迟（毫秒） */
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;

    // ToolContext 配置键
    public static final String CTX_PROXY_HOST = "WEBFETCH_PROXY_HOST";
    public static final String CTX_PROXY_PORT = "WEBFETCH_PROXY_PORT";
    public static final String CTX_CUSTOM_HEADERS = "WEBFETCH_CUSTOM_HEADERS";
    public static final String CTX_USER_AGENT = "WEBFETCH_USER_AGENT";
    public static final String CTX_MAX_RETRIES = "WEBFETCH_MAX_RETRIES";
    public static final String CTX_RETRY_DELAY_MS = "WEBFETCH_RETRY_DELAY_MS";

    /**
     * 返回工具名称（"WebFetch"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "WebFetch";
    }

    /**
     * 返回工具描述，说明获取网页内容并转为纯文本的用途与大小/超时限制。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            获取指定 URL 的内容并以文本形式返回。HTML 页面会自动简化为可读文本。\
            适用于阅读文档、API 响应或网页。有 100KB 大小限制和 30 秒超时。\
            支持 HTTP 代理、自定义请求头和自动重试。
            """);
    }

    /**
     * 返回输入 JSON Schema，定义 url（必填）与 maxLength、raw（可选）参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "要获取的 URL（必须以 http:// 或 https:// 开头）"
                },
                "maxLength": {
                  "type": "integer",
                  "description": "返回的最大字符数（默认：50000）"
                },
                "raw": {
                  "type": "boolean",
                  "description": "如果为 true，则跳过 HTML 转文本转换，返回原始内容（默认：false）"
                }
              },
              "required": ["url"]
            }""");
    }

    /**
     * 该工具仅发起 HTTP GET 请求、不修改本地状态，标记为只读。
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 获取指定 URL 内容：校验 URL、读取配置（代理/自定义请求头/重试参数）、
     * 带重试执行请求，按内容类型将 HTML 转为纯文本，并按 maxLength 截断返回。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String url = (String) input.get("url");
        int maxLength = input.containsKey("maxLength")
                ? ((Number) input.get("maxLength")).intValue()
                : 50000;
        boolean raw = input.containsKey("raw") && (boolean) input.get("raw");

        // URL 校验
        if (url == null || url.isBlank()) {
            return "Error: URL is required";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Error: URL must start with http:// or https://";
        }

        // 读取配置（ToolContext 注入 > 环境变量 > 默认值）
        String proxyHost = getConfig(context, CTX_PROXY_HOST,
                System.getenv("WEBFETCH_PROXY_HOST"));
        int proxyPort = parseIntConfig(context, CTX_PROXY_PORT,
                System.getenv("WEBFETCH_PROXY_PORT"), -1);

        @SuppressWarnings("unchecked")
        Map<String, String> customHeaders = context.has(CTX_CUSTOM_HEADERS)
                ? (Map<String, String>) context.get(CTX_CUSTOM_HEADERS)
                : Map.of();

        String userAgent = getConfig(context, CTX_USER_AGENT,
                System.getenv("WEBFETCH_USER_AGENT"));
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = DEFAULT_USER_AGENT;
        }

        int maxRetries = parseIntConfig(context, CTX_MAX_RETRIES,
                System.getenv("WEBFETCH_MAX_RETRIES"), DEFAULT_MAX_RETRIES);
        long retryDelayMs = parseLongConfig(context, CTX_RETRY_DELAY_MS,
                System.getenv("WEBFETCH_RETRY_DELAY_MS"), DEFAULT_RETRY_DELAY_MS);

        try {
            URI uri = URI.create(url);

            // 构建 HttpClient（支持代理）
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL);

            if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
                clientBuilder.proxy(ProxySelector.of(
                        new InetSocketAddress(proxyHost, proxyPort)));
            }

            HttpClient client = clientBuilder.build();

            // 构建请求（支持自定义 Header）
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")
                    .timeout(TIMEOUT)
                    .GET();

            for (var entry : customHeaders.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }

            HttpRequest request = requestBuilder.build();

            // 带重试的请求
            HttpResponse<String> response = executeWithRetry(client, request, maxRetries, retryDelayMs);
            int statusCode = response.statusCode();
            String body = response.body();

            if (statusCode >= 400) {
                return "Error: HTTP " + statusCode + "\n" + truncate(body, 2000);
            }

            // 检查大小限制
            if (body.length() > MAX_BODY_SIZE) {
                body = body.substring(0, MAX_BODY_SIZE);
            }

            // 根据内容类型处理
            String contentType = response.headers().firstValue("Content-Type").orElse("text/plain");

            String result;
            if (!raw && (contentType.contains("text/html") || contentType.contains("application/xhtml"))) {
                result = htmlToText(body);
            } else {
                result = body;
            }

            // 截断到最大长度
            result = truncate(result, maxLength);

            StringBuilder sb = new StringBuilder();
            sb.append("URL: ").append(url).append("\n");
            sb.append("Status: ").append(statusCode).append("\n");
            sb.append("Content-Type: ").append(contentType).append("\n");
            sb.append("---\n");
            sb.append(result);

            return sb.toString();

        } catch (IllegalArgumentException e) {
            return "Error: Invalid URL: " + e.getMessage();
        } catch (java.net.http.HttpTimeoutException e) {
            return "Error: Request timed out after " + TIMEOUT.toSeconds() + " seconds";
        } catch (Exception e) {
            return "Error fetching URL: " + e.getMessage();
        }
    }

    /** 带重试的 HTTP 请求执行 */
    private HttpResponse<String> executeWithRetry(HttpClient client, HttpRequest request,
                                                   int maxRetries, long retryDelayMs) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                lastException = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }
        throw lastException;
    }

    /**
     * 简单的 HTML → 纯文本转换。
     * 移除脚本/样式块，转换常见标签为文本格式。
     */
    private String htmlToText(String html) {
        // 移除 script 和 style 块
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", "");

        // 移除 HTML 注释
        text = text.replaceAll("(?s)<!--.*?-->", "");

        // 将块级元素转为换行
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|pre|section|article|main|aside|header|footer|nav)>", "\n");
        text = text.replaceAll("(?i)<(p|div|h[1-6]|li|tr|blockquote|pre|section|article)[^>]*>", "\n");

        // 将链接转为 [text](url) 格式
        Pattern linkPattern = Pattern.compile("<a[^>]*href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE);
        Matcher linkMatcher = linkPattern.matcher(text);
        text = linkMatcher.replaceAll("[$2]($1)");

        // 移除所有剩余 HTML 标签
        text = text.replaceAll("<[^>]+>", "");

        // 解码常见 HTML 实体
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&apos;", "'");
        text = text.replace("&nbsp;", " ");
        text = text.replace("&#x27;", "'");
        // 数字实体
        java.util.regex.Pattern numEntity = java.util.regex.Pattern.compile("&#(\\d+);");
        java.util.regex.Matcher numMatcher = numEntity.matcher(text);
        text = numMatcher.replaceAll(mr -> {
            try {
                return String.valueOf((char) Integer.parseInt(mr.group(1)));
            } catch (Exception e) {
                return mr.group();
            }
        });

        // 压缩多余空行（3个以上连续空行压缩为2个）
        text = text.replaceAll("\\n{3,}", "\n\n");
        // 压缩行内多余空格
        text = text.replaceAll("[ \\t]+", " ");

        return text.strip();
    }

    /** 截断文本到指定长度 */
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n...[truncated at " + maxLength + " chars]";
    }

    /**
     * 生成用于界面展示的执行摘要，截断并标明要获取的 URL。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        String url = (String) input.getOrDefault("url", "");
        if (url.length() > 50) {
            url = url.substring(0, 47) + "...";
        }
        return "🌐 Fetching " + url;
    }

    // ── 配置读取辅助方法 ──

    /** 读取字符串配置：优先取 ToolContext 中的值，为空时回退到环境变量。 */
    private String getConfig(ToolContext context, String key, String envFallback) {
        String value = context.get(key);
        if (value != null && !value.isBlank()) return value;
        return envFallback;
    }

    /**
     * 读取整数配置：优先取 ToolContext（支持数字或字符串），
     * 其次取环境变量，均无法解析时返回默认值。
     */
    private int parseIntConfig(ToolContext context, String key, String envFallback, int defaultValue) {
        try {
            Object value = context.get(key);
            if (value instanceof Number n) return n.intValue();
            if (value instanceof String s && !s.isBlank()) return Integer.parseInt(s);
        } catch (Exception ignored) {}
        if (envFallback != null && !envFallback.isBlank()) {
            try { return Integer.parseInt(envFallback); } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    /**
     * 读取长整型配置：优先取 ToolContext（支持数字或字符串），
     * 其次取环境变量，均无法解析时返回默认值。
     */
    private long parseLongConfig(ToolContext context, String key, String envFallback, long defaultValue) {
        try {
            Object value = context.get(key);
            if (value instanceof Number n) return n.longValue();
            if (value instanceof String s && !s.isBlank()) return Long.parseLong(s);
        } catch (Exception ignored) {}
        if (envFallback != null && !envFallback.isBlank()) {
            try { return Long.parseLong(envFallback); } catch (Exception ignored) {}
        }
        return defaultValue;
    }
}
