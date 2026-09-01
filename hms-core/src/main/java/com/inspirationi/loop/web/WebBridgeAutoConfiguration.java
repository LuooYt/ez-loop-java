package com.inspirationi.loop.web;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inspirationi.loop.api.HmsSessionManager;
import com.inspirationi.loop.api.PendingResponses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Web 桥接自动配置 —— 注册 SSE 集成所需的 Bean。
 * <p>
 * 仅在 classpath 存在 {@link SseEmitter}（即引入了 spring-webmvc）时生效；
 * hms-core 对 spring-webmvc 的依赖为 {@code optional}，非 Web 集成方不受影响。
 * <p>
 * 注册的 Bean：
 * <ul>
 *   <li>{@link PendingResponses} — 悬挂请求登记处（无 Web 依赖，可单独使用）</li>
 *   <li>{@link HmsSseBridge} — SSE 集成门面</li>
 * </ul>
 * 均为 {@link ConditionalOnMissingBean}，集成方可自行覆盖。
 */
@Configuration
@ConditionalOnClass(SseEmitter.class)
public class WebBridgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebBridgeAutoConfiguration.class);

    /**
     * 悬挂请求登记处 —— 超时上限与库内部等待用户回答的上限保持一致。
     *
     * @param userResponseTimeoutSeconds 等待用户回答的上限（秒），默认 300
     */
    @Bean
    @ConditionalOnMissingBean(PendingResponses.class)
    public PendingResponses pendingResponses(
            @Value("${hms-core.user-response-timeout-seconds:300}") long userResponseTimeoutSeconds) {
        log.info("Creating PendingResponses bean (timeout={}s)", userResponseTimeoutSeconds);
        return new PendingResponses(userResponseTimeoutSeconds);
    }

    /**
     * SSE 集成门面。
     * <p>
     * 复用容器内的 {@link ObjectMapper}（Spring Boot Web 默认提供）；缺失时自建一个，
     * 保证在非 Boot 环境下也能工作。Agent 循环跑在虚拟线程上，不占用容器线程。
     *
     * @param sessionManager       会话管理器
     * @param pending              悬挂请求登记处
     * @param objectMapperProvider 容器内的 ObjectMapper（可能不存在）
     * @param emitterTimeoutMinutes SSE 连接空闲超时（分钟），默认 30
     */
    @Bean
    @ConditionalOnMissingBean(HmsSseBridge.class)
    public HmsSseBridge hmsSseBridge(
            HmsSessionManager sessionManager, PendingResponses pending,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            @Value("${hms-core.sse.emitter-timeout-minutes:30}") long emitterTimeoutMinutes) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("Creating HmsSseBridge bean (emitterTimeout={}min)", emitterTimeoutMinutes);
        return new HmsSseBridge(sessionManager, pending, executor, objectMapper,
                emitterTimeoutMinutes * 60 * 1000L);
    }
}
