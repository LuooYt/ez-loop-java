package com.inspirationi.hmsweb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web MVC 配置 — CORS 跨域、静态资源映射等。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 允许的跨域来源，逗号分隔。
     * <p>
     * 默认只放行本机。此前写死 {@code allowedOriginPatterns("*")} 且
     * {@code allowCredentials(true)} —— 两者叠加后，Spring 会把<b>任意</b>请求方
     * 的 Origin 原样回显到响应头里，等于允许任何网页以本机身份调用这套无鉴权 API：
     * 用户只是打开了一个恶意页面，对方就能读取会话内容、切换权限模式。
     * 前端用 fetch 且不带 Cookie，因此同时关掉了凭证。
     */
    @Value("${hms.demo.cors-origins:http://localhost:[*],http://127.0.0.1:[*]}")
    private String allowedOrigins;

    /**
     * 配置跨域规则：仅允许白名单来源对 /api/** 发起常规请求。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
