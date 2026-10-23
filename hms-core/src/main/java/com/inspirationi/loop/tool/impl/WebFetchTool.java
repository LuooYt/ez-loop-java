package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页获取工具 —— 通用 Web SDK 的 HTTP 内容拉取工具。
 * <p>
 * 使用 HTTP GET 获取指定 URL 的内容，自动将 HTML 简化为纯文本。
 * 支持大小限制、超时控制、HTTP 代理、自定义 Header 注入和重试机制。
 * <p>
 * <b>SSRF 防护：</b>默认拒绝抓取本机与内网地址（含 169.254.0.0/16 云元数据端点）。
 * 校验基于 DNS 解析后的实际 IP，重定向的每一跳都会重新校验。本工具的 URL 通常
 * 由大模型决定、可被提示词注入操纵，故该防护默认开启；确有内网抓取需求时通过
 * {@code WEBFETCH_ALLOW_INTERNAL} 显式放开。
 * <p>
 * <b>配置方式（通过 ToolContext）：</b>
 * <ul>
 *   <li>{@code WEBFETCH_PROXY_HOST} / {@code WEBFETCH_PROXY_PORT} — HTTP 代理</li>
 *   <li>{@code WEBFETCH_CUSTOM_HEADERS} — Map&lt;String,String&gt; 自定义请求头</li>
 *   <li>{@code WEBFETCH_USER_AGENT} — String 自定义 User-Agent</li>
 *   <li>{@code WEBFETCH_MAX_RETRIES} — Integer 最大重试次数（默认 2）</li>
 *   <li>{@code WEBFETCH_RETRY_DELAY_MS} — Long 重试延迟毫秒（默认 1000）</li>
 *   <li>{@code WEBFETCH_ALLOW_INTERNAL} — Boolean 允许内网地址（默认 false）</li>
 * </ul>
 */
public class WebFetchTool implements Tool {

    /** 最大响应体大小：100KB */
    private static final int MAX_BODY_SIZE = 100 * 1024;

    /** {@code maxLength} 的默认值 */
    private static final int DEFAULT_MAX_LENGTH = 50_000;

    /**
     * {@code maxLength} 的硬上限。
     * <p>
     * 该参数由模型填写，不设上限等于把「一次分配多大字符串」的决定权交给它。
     */
    private static final int MAX_LENGTH_LIMIT = 200_000;

    /**
     * 从网络读取的字节上限（硬上限，2MB）。
     * <p>
     * 实际取值还会按 {@code min(maxLength * 4, 2MB)} 再收紧 —— UTF-8 最多
     * 4 字节一个字符，按字符数换算成字节数足够装下要保留的部分。
     * 关键在于「先按字节有界读取，再解码」：{@code BodyHandlers.ofString()}
     * 会把整个响应体读进内存，一个几百 MB 的响应就能把 Agent 进程拖垮。
     */
    private static final int MAX_RAW_BYTES = 2 * 1024 * 1024;

    /** HTTP 请求超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** 默认 User-Agent 标识 */
    private static final String DEFAULT_USER_AGENT = "HmsCore-Java/0.2 (WebFetchTool)";

    /** 默认最大重试次数 */
    private static final int DEFAULT_MAX_RETRIES = 2;

    /** 默认重试延迟（毫秒） */
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;

    /** 手动跟随重定向的最大跳数（每跳都重新做地址校验） */
    private static final int MAX_REDIRECTS = 5;

    // ToolContext 配置键
    public static final String CTX_PROXY_HOST = "WEBFETCH_PROXY_HOST";
    public static final String CTX_PROXY_PORT = "WEBFETCH_PROXY_PORT";
    public static final String CTX_CUSTOM_HEADERS = "WEBFETCH_CUSTOM_HEADERS";
    public static final String CTX_USER_AGENT = "WEBFETCH_USER_AGENT";
    public static final String CTX_MAX_RETRIES = "WEBFETCH_MAX_RETRIES";
    public static final String CTX_RETRY_DELAY_MS = "WEBFETCH_RETRY_DELAY_MS";
    /**
     * 允许抓取内网/本机地址的开关（Boolean 或 "true"）。
     * <p>
     * 默认关闭。仅当集成方明确需要抓取内部服务、且已确认 URL 不受不可信输入
     * 影响时才应开启 —— URL 通常由大模型决定，可被提示词注入操纵。
     */
    public static final String CTX_ALLOW_INTERNAL = "WEBFETCH_ALLOW_INTERNAL";

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
        // 三处都用 ToolValidator：模型偶尔把数字/布尔塞成字符串，
        // 直接强转会让整个工具抛 ClassCastException 并中止这一轮 Agent。
        String url = ToolValidator.getString(input, "url", null);
        int maxLength = Math.min(MAX_LENGTH_LIMIT,
                Math.max(1, ToolValidator.getInt(input, "maxLength", DEFAULT_MAX_LENGTH)));
        boolean raw = ToolValidator.getBoolean(input, "raw", false);

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

        // 是否允许内网/本机地址（默认不允许，防 SSRF）
        boolean allowInternal = parseBooleanConfig(context, CTX_ALLOW_INTERNAL,
                System.getenv("WEBFETCH_ALLOW_INTERNAL"));

        try {
            URI uri = URI.create(url);

            // SSRF 校验 —— URL 由大模型决定，必须在发起请求前拦住内网地址
            if (!allowInternal) {
                String rejection = validateTarget(uri);
                if (rejection != null) {
                    return rejection;
                }
            }

            // 构建 HttpClient（支持代理）
            // 重定向不交给 HttpClient 自动跟随：跳转目标同样需要 SSRF 校验，
            // 否则外部 URL 可用 302 把请求引向内网。见 fetchFollowingRedirects。
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER);

            if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
                clientBuilder.proxy(ProxySelector.of(
                        new InetSocketAddress(proxyHost, proxyPort)));
            }

            HttpClient client = clientBuilder.build();

            // 有界读取：按字节封顶，再按 charset 解码。
            int bodyLimit = (int) Math.min(MAX_RAW_BYTES, (long) maxLength * 4);
            HttpResponse<InputStream> response = fetchFollowingRedirects(
                    client, uri, userAgent, customHeaders, maxRetries, retryDelayMs, allowInternal);
            int statusCode = response.statusCode();
            Body responseBody = readBody(response, bodyLimit);
            String body = responseBody.text();

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
            // 字节上限与字符上限是两道不同的闸：前者防止把超大响应读进内存，
            // 后者是给模型看的篇幅限制。前者触发时必须显式说明，否则模型
            // 会把「读到一半的文档」当成全文。
            if (responseBody.truncated()) {
                result = result + "\n...[response body truncated at " + bodyLimit + " bytes]";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("URL: ").append(url).append("\n");
            sb.append("Status: ").append(statusCode).append("\n");
            sb.append("Content-Type: ").append(contentType).append("\n");
            sb.append("---\n");
            sb.append(result);

            return sb.toString();

        } catch (BlockedTargetException e) {
            // 已是面向模型的完整说明，直接透出
            return e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Error: Invalid URL: " + Tool.describeError(e);
        } catch (java.net.http.HttpTimeoutException e) {
            return "Error: Request timed out after " + TIMEOUT.toSeconds() + " seconds";
        } catch (Exception e) {
            return "Error fetching URL: " + Tool.describeError(e);
        }
    }

    /** 目标地址被 SSRF 校验拒绝 —— 携带面向模型的说明文本。 */
    private static class BlockedTargetException extends Exception {
        BlockedTargetException(String message) {
            super(message);
        }
    }

    /** 已解码的响应体；{@code truncated} 表示因字节上限没有读全。 */
    private record Body(String text, boolean truncated) {}

    /**
     * 按字节上限读取响应体，再按 {@code Content-Type} 的 charset 解码。
     * <p>
     * 用 {@link HttpResponse.BodyHandlers#ofInputStream()} 而不是
     * {@code ofString()}：后者会把整个响应体读进内存，上限只有在读完
     * 之后才起作用，面对超大响应已经晚了。
     *
     * @param limit 最多读取的字节数
     */
    private static Body readBody(HttpResponse<InputStream> response, int limit) throws IOException {
        String charsetName = response.headers().firstValue("Content-Type")
                .map(WebFetchTool::charsetOf)
                .orElse(null);
        try (InputStream in = response.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
            byte[] buf = new byte[8192];
            while (out.size() < limit) {
                int n = in.read(buf, 0, Math.min(buf.length, limit - out.size()));
                if (n < 0) break;
                out.write(buf, 0, n);
            }
            // 恰好读满上限时再探一个字节，区分「刚好这么大」和「还有下文」
            boolean truncated = out.size() >= limit && in.read() >= 0;
            return new Body(decode(out.toByteArray(), charsetName), truncated);
        }
    }

    private static String decode(byte[] bytes, String charsetName) {
        if (charsetName != null) {
            try {
                return new String(bytes, Charset.forName(charsetName));
            } catch (IllegalArgumentException ignored) {
                // 未知 charset：回退 UTF-8，而不是把整个请求判失败
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 从 {@code Content-Type} 头里取 charset；缺失或为空返回 {@code null}。 */
    private static String charsetOf(String contentType) {
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx < 0) return null;
        String value = contentType.substring(idx + "charset=".length()).trim();
        int end = value.indexOf(';');
        if (end >= 0) value = value.substring(0, end).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isBlank() ? null : value;
    }

    /**
     * 校验目标地址不指向本机或内网，防止 SSRF 触达云元数据服务与内部接口。
     * <p>
     * 解析 DNS 后检查**实际 IP**（而非仅比对主机名字符串），因此
     * {@code evil.example.com → 169.254.169.254} 这类解析型绕过同样会被拦下。
     * 主机名可能解析到多个地址，任一命中即拒绝。
     *
     * @return 拒绝原因；通过校验时为 {@code null}
     */
    private String validateTarget(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "Error: URL has no host";
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isInternal(addr)) {
                    return "Error: refusing to fetch internal address "
                            + addr.getHostAddress() + " (host: " + host + "). "
                            + "Internal targets are blocked to prevent SSRF; "
                            + "set WEBFETCH_ALLOW_INTERNAL=true to override.";
                }
            }
            return null;
        } catch (UnknownHostException e) {
            return "Error: cannot resolve host " + host;
        }
    }

    /**
     * 判断地址是否属于不可对外抓取的范围。
     * <p>
     * {@code InetAddress} 的现成判定覆盖不到两类同样危险、且在云环境里很常见的地址，
     * 这里手工补齐：
     * <ul>
     *   <li>{@code 100.64.0.0/10}（CGNAT）—— 运营商级 NAT 段，云上常被用作
     *       内部服务网格地址，JDK 不认为它是 site-local；</li>
     *   <li>{@code fc00::/7}（IPv6 唯一本地地址）—— 等价于 RFC 1918，
     *       {@code isSiteLocalAddress()} 只认已废弃的 fec0::/10。</li>
     * </ul>
     *
     * {@code isLinkLocalAddress()} 覆盖 169.254.0.0/16 与 fe80::/10 ——
     * AWS/GCP/Azure 的实例元数据端点即在前者，是 SSRF 最常见的凭证泄漏目标。
     */
    private static boolean isInternal(InetAddress addr) {
        if (addr.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || addr.isLinkLocalAddress()  // 169.254.0.0/16（云元数据）, fe80::/10
                || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || addr.isAnyLocalAddress()   // 0.0.0.0, ::
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127; // 100.64.0.0/10
        }
        if (raw.length == 16) {
            return (raw[0] & 0xFE) == 0xFC; // fc00::/7
        }
        return false;
    }

    /**
     * 发起请求并手动跟随重定向，对每一跳目标重新执行 SSRF 校验。
     * <p>
     * 自动跟随（{@link HttpClient.Redirect#NORMAL}）会绕过入口校验：
     * 一个合法的外部 URL 可以 302 到 {@code 169.254.169.254}。
     *
     * @return 最终响应
     * @throws BlockedTargetException 重定向目标未通过 SSRF 校验
     */
    private HttpResponse<InputStream> fetchFollowingRedirects(
            HttpClient client, URI uri, String userAgent, Map<String, String> customHeaders,
            int maxRetries, long retryDelayMs, boolean allowInternal) throws Exception {

        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            // 发送前复核：入口校验与实际建连之间有一次 DNS 重新解析，攻击者控制的
            // 域名可在这段窗口里把记录改成内网地址（DNS rebinding）。因此每一跳都
            // 在真正发请求之前再解析一遍。
            // 残余风险：解析结果到 socket 建连之间仍有毫秒级窗口，只有把连接钉死
            // 到已校验的 IP 才能根除；HttpClient 不暴露该能力，故作为已知限制记录。
            if (!allowInternal) {
                String rejection = validateTarget(current);
                if (rejection != null) {
                    throw new BlockedTargetException(hop == 0 ? rejection
                            : rejection + " (via redirect from " + uri + ")");
                }
            }
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(current)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")
                    .timeout(TIMEOUT)
                    .GET();
            for (var entry : customHeaders.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }

            HttpResponse<InputStream> response =
                    executeWithRetry(client, requestBuilder.build(), maxRetries, retryDelayMs);

            if (!isRedirect(response.statusCode())) {
                return response;
            }

            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null || location.isBlank()) {
                // 声明重定向却没给 Location，按最终响应处理
                return response;
            }

            // Location 可能是相对路径
            URI next = current.resolve(location);
            String scheme = next.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                closeQuietly(response);
                throw new BlockedTargetException(
                        "Error: refusing to follow redirect to non-HTTP scheme: " + next);
            }
            // 重定向目标的内网校验由下一轮循环的「发送前复核」完成 —— 那里离真正
            // 建连更近，能覆盖更多 rebinding 窗口。这里只需释放本次响应。
            closeQuietly(response);
            current = next;
        }
        throw new BlockedTargetException(
                "Error: too many redirects (limit " + MAX_REDIRECTS + ")");
    }

    /** 释放一个不再读取的响应体；失败不影响后续流程（连接终会被回收）。 */
    private static void closeQuietly(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // 丢弃响应的目的只是尽快归还连接，关不掉也无需上报
        }
    }

    /** 是否为需要跟随的重定向状态码。 */
    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    /** 带重试的 HTTP 请求执行 */
    private HttpResponse<InputStream> executeWithRetry(HttpClient client, HttpRequest request,
                                                       int maxRetries, long retryDelayMs) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
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

    /**
     * 读取布尔配置：优先取 ToolContext（支持 Boolean 或字符串），
     * 其次取环境变量。默认 {@code false} —— 这是安全开关，缺省必须是关闭。
     */
    private boolean parseBooleanConfig(ToolContext context, String key, String envFallback) {
        Object value = context.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s);
        return envFallback != null && Boolean.parseBoolean(envFallback);
    }
}
