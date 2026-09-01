package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.tool.ToolContext;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebFetchTool} 的 SSRF 防护测试。
 * <p>
 * WebFetch 的 URL 由大模型决定，可被提示词注入操纵；且该工具
 * {@code isReadOnly() == true}，在传统回调权限模式下不触发用户确认。
 * 因此内网地址必须在工具内部被拦住 —— 这些测试守护该边界。
 */
class WebFetchSsrfTest {

    private final WebFetchTool tool = new WebFetchTool();

    private String fetch(String url) {
        return tool.execute(Map.of("url", url), ToolContext.defaultContext());
    }

    private String fetchAllowingInternal(String url) {
        ToolContext context = ToolContext.defaultContext();
        context.set(WebFetchTool.CTX_ALLOW_INTERNAL, true);
        return tool.execute(Map.of("url", url), context);
    }

    /** 拒绝响应的判定 —— 必须明确指出是内网地址被拦，而非泛化的网络错误。 */
    private static void assertBlockedAsInternal(String result, String what) {
        assertTrue(result.startsWith("Error:"),
                what + " 应被拒绝，实际返回：" + truncate(result));
        assertTrue(result.contains("internal address"),
                what + " 的拒绝原因应指明内网地址，实际返回：" + truncate(result));
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }

    @Test
    void blocksCloudMetadataEndpoint() {
        // AWS/GCP/Azure 实例元数据 —— SSRF 最常见的凭证泄漏目标
        assertBlockedAsInternal(fetch("http://169.254.169.254/latest/meta-data/"),
                "云元数据端点 169.254.169.254");
    }

    @Test
    void blocksLoopbackAddresses() {
        assertBlockedAsInternal(fetch("http://127.0.0.1:8080/admin"), "IPv4 回环地址");
        assertBlockedAsInternal(fetch("http://localhost:8080/admin"), "localhost");
    }

    @Test
    void blocksPrivateNetworkRanges() {
        assertBlockedAsInternal(fetch("http://10.0.0.1/"), "10/8 私有网段");
        assertBlockedAsInternal(fetch("http://172.16.0.1/"), "172.16/12 私有网段");
        assertBlockedAsInternal(fetch("http://192.168.1.1/"), "192.168/16 私有网段");
    }

    @Test
    void blocksAnyLocalAddress() {
        assertBlockedAsInternal(fetch("http://0.0.0.0/"), "0.0.0.0");
    }

    @Test
    void blocksIpv6Loopback() {
        assertBlockedAsInternal(fetch("http://[::1]/"), "IPv6 回环地址");
    }

    @Test
    void rejectsNonHttpSchemes() {
        String fileResult = fetch("file:///etc/passwd");
        assertTrue(fileResult.startsWith("Error:"), "file:// 应被拒绝");

        String gopherResult = fetch("gopher://127.0.0.1:11211/");
        assertTrue(gopherResult.startsWith("Error:"), "gopher:// 应被拒绝");
    }

    @Test
    void allowInternalOverrideBypassesTheCheck() {
        // 显式放开后不应再因「内网地址」被拒 —— 会因连接失败而报别的错，
        // 关键是拒绝原因不再是 SSRF 校验。
        String result = fetchAllowingInternal("http://127.0.0.1:1/");
        assertFalse(result.contains("internal address"),
                "WEBFETCH_ALLOW_INTERNAL=true 时不应再做内网拦截，实际返回：" + truncate(result));
    }

    @Test
    void allowInternalAcceptsStringValue() {
        // ToolContext 的值可能来自配置文件，是字符串而非 Boolean
        ToolContext context = ToolContext.defaultContext();
        context.set(WebFetchTool.CTX_ALLOW_INTERNAL, "true");
        String result = tool.execute(Map.of("url", "http://127.0.0.1:1/"), context);
        assertFalse(result.contains("internal address"),
                "字符串 \"true\" 也应生效，实际返回：" + truncate(result));
    }

    @Test
    void defaultsToBlockingWhenOverrideAbsentOrFalse() {
        ToolContext context = ToolContext.defaultContext();
        context.set(WebFetchTool.CTX_ALLOW_INTERNAL, false);
        assertBlockedAsInternal(
                tool.execute(Map.of("url", "http://127.0.0.1:8080/"), context),
                "显式 false 时的内网地址");

        // 安全开关缺省必须是关闭
        assertBlockedAsInternal(fetch("http://127.0.0.1:8080/"), "未设置开关时的内网地址");
    }

    @Test
    void stillValidatesUrlSchemePrefix() {
        String result = fetch("ftp://example.com/file");
        assertTrue(result.contains("must start with http"),
                "非 http/https 前缀应在入口就被拒绝");
    }

    @Test
    void rejectsUnresolvableHost() {
        String result = fetch("http://this-host-should-not-exist-hms-core-test.invalid/");
        assertTrue(result.startsWith("Error:"), "无法解析的主机应返回错误");
    }
}
