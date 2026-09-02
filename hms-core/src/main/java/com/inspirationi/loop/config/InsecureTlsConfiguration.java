package com.inspirationi.loop.config;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 放宽模型调用的 TLS 校验 —— <b>默认关闭，仅用于自建/中转端点</b>。
 * <p>
 * 由 {@code hms-core.tls.insecure=true} 显式开启。典型场景是把 base-url 指向
 * 一个 IP 或内部主机名，而对端出示的证书签给了别的域名，okhttp 因此抛
 * {@code SSLPeerUnverifiedException: Hostname <ip> not verified}。
 *
 * <h2>安全代价</h2>
 * 开启后，模型调用<b>不再具备中间人攻击防护</b>：任何能劫持该连接的一方都可以
 * 出示自签证书冒充服务端，从而读到请求里的 API key 与全部对话内容、并篡改回复。
 * 这不是「少一道校验」，而是 HTTPS 的身份保证被整个移除。因此：
 * <ul>
 *   <li>只在你信任网络路径的环境下使用（本机、内网、可控的中转）</li>
 *   <li>不要用于公网上的第三方端点 —— 那正是主机名校验要防的情形</li>
 *   <li>开启后每次启动都会打 WARN，这是故意的，不要去掉</li>
 * </ul>
 *
 * <h2>作用范围</h2>
 * <b>只影响 provider 的模型调用客户端</b>，不影响 {@code WebFetch} 等工具发起的
 * HTTPS 请求。后者抓取的是模型或用户给出的<b>任意外部 URL</b>，让它一并失去证书
 * 校验与本配置要解决的问题无关，只会白扩大风险面 —— 那条链路的 SSRF 与证书校验
 * 仍按原样生效。
 * <p>
 * 更好的做法始终是修好证书：让中转服务出示与访问地址匹配的证书，或把 base-url
 * 改成证书 SAN 里已有的域名。本开关是没有那个条件时的临时出路。
 */
@AutoConfiguration
@ConditionalOnClass(AnthropicHttpClientBuilderCustomizer.class)
@ConditionalOnProperty(name = "hms-core.tls.insecure", havingValue = "true")
public class InsecureTlsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InsecureTlsConfiguration.class);

    /**
     * 让 Anthropic（含所有走 Anthropic 协议的中转）客户端跳过证书与主机名校验。
     * <p>
     * 走官方的 {@link AnthropicHttpClientBuilderCustomizer} 扩展点，不反射、
     * 不替换整个 ChatModel Bean —— 超时、代理、观测等既有配置一律保留。
     */
    @Bean
    public AnthropicHttpClientBuilderCustomizer insecureTlsAnthropicCustomizer() {
        warn("Anthropic");
        X509TrustManager trustAll = trustAllManager();
        HostnameVerifier acceptAny = acceptAnyHostname();
        return builder -> builder
                .sslSocketFactory(sslContext(trustAll).getSocketFactory())
                .trustManager(trustAll)
                .hostnameVerifier(acceptAny);
    }

    /**
     * 每次启动都提醒 —— 这个开关很容易在某次排障后被忘在配置里带到生产。
     */
    private static void warn(String scope) {
        log.warn("hms-core.tls.insecure=true —— 已关闭 {} 模型调用的 TLS 证书与主机名校验。"
                + "该连接不再具备中间人攻击防护：链路上的任何一方都可冒充服务端，"
                + "读取 API key 与对话内容。仅限本机/内网/可控中转，切勿用于生产。", scope);
    }

    /** 接受任意主机名 —— 对应 {@code Hostname <ip> not verified} 那类失败。 */
    private static HostnameVerifier acceptAnyHostname() {
        return (hostname, session) -> true;
    }

    /**
     * 接受任意证书链。
     * <p>
     * 仅关掉主机名校验往往不够：中转端点也常用自签证书，那会抛
     * {@code PKIX path building failed}，属于另一条失败路径。既然本开关的语义
     * 已经是「信任这个端点」，两者一并放开才不会让使用方换个环境又撞一次。
     */
    private static X509TrustManager trustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 有意为空：本配置的语义就是不校验
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 有意为空：本配置的语义就是不校验
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /** 用 trust-all 管理器构建 TLS 上下文。 */
    private static SSLContext sslContext(X509TrustManager trustManager) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{trustManager}, new SecureRandom());
            return context;
        } catch (Exception e) {
            // 构建失败时不能静默回退到「校验正常」—— 使用方会以为开关生效了，
            // 却仍旧连不上，排查方向被带偏。
            throw new IllegalStateException(
                    "无法构建放宽校验的 SSLContext（hms-core.tls.insecure=true）", e);
        }
    }
}
