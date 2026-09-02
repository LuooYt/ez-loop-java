package com.inspirationi.loop.api;

import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * API 模式自动配置 —— 注册 SDK 对外接口 Bean。
 * <p>
 * 主要 Bean：
 * <ul>
 *   <li>{@link HmsSessionManager} — 会话隔离 + 生命周期</li>
 *   <li>{@link PromptManager} — 两级提示词管理</li>
 *   <li>{@link ToolManager} — 两级工具管理</li>
 * </ul>
 * 依赖 {@link com.inspirationi.loop.config.ToolConfiguration} 产出的
 * ToolRegistry，故声明在其之后装配。
 */
@AutoConfiguration(after = com.inspirationi.loop.config.ToolConfiguration.class)
public class ApiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ApiAutoConfiguration.class);

    /**
     * 注册默认的会话管理器 Bean（可被自定义实现覆盖）。
     * <p>
     * 使用 {@link Lazy} 注入 PromptManager，打破「PromptManager ↔ HmsSessionManager」
     * 的构造器循环依赖：两个 Bean 都持对方的懒加载代理，直到真正调用时才解析。
     *
     * @param activeChatModel       当前激活的聊天模型
     * @param toolRegistry          全局工具注册中心
     * @param promptManager         提示词管理器（懒加载代理）
     * @param permissionRuleEngine  权限规则引擎
     * @param idleTimeoutMinutes    会话空闲超时（分钟），默认 30
     * @param cleanupIntervalMinutes 空闲清理执行间隔（分钟），默认 5
     * @param userResponseTimeoutSeconds 等待用户回答（AskUser / 权限确认）的上限（秒），默认 300
     * @param maxSessions          同时存活的会话数上限，默认 1000
     * @param maxIterations        单轮最大迭代次数，默认 50 —— 撞上限会截断回答并
     *                             追加警告标记，长工具链任务可上调
     * @param toolContext          全局工具上下文 —— 作为各会话上下文的父级传入，
     *                             让会话内的工具能读到 TaskManager / McpManager 等共享对象
     * @return 会话隔离 + 生命周期管理的默认实现
     */
    @Bean
    @ConditionalOnMissingBean(HmsSessionManager.class)
    public HmsSessionManager hmsSessionManager(
            ChatModel activeChatModel, ToolRegistry toolRegistry,
            @Lazy PromptManager promptManager, PermissionRuleEngine permissionRuleEngine,
            ToolContext toolContext,
            @Value("${hms-core.session.idle-timeout-minutes:30}") long idleTimeoutMinutes,
            @Value("${hms-core.session.cleanup-interval-minutes:5}") long cleanupIntervalMinutes,
            @Value("${hms-core.user-response-timeout-seconds:300}") long userResponseTimeoutSeconds,
            @Value("${hms-core.session.max-sessions:1000}") int maxSessions,
            @Value("${hms-core.max-iterations:50}") int maxIterations) {
        log.info("Creating DefaultHmsSessionManager bean");
        return DefaultHmsSessionManager.builder(activeChatModel, toolRegistry, promptManager)
                .permissionEngine(permissionRuleEngine)
                .globalToolContext(toolContext)
                .idleTimeoutSeconds(idleTimeoutMinutes * 60)
                .cleanupIntervalSeconds(cleanupIntervalMinutes * 60)
                .userResponseTimeoutSeconds(userResponseTimeoutSeconds)
                .maxSessions(maxSessions)
                .maxIterations(maxIterations)
                .build();
    }

    /**
     * 注册默认的提示词管理器 Bean（可被自定义实现覆盖）。
     * <p>
     * 使用 {@link Lazy} 注入 HmsSessionManager，与 {@link #hmsSessionManager} 相互懒加载，
     * 从而在 hms-core 内部自行打破循环依赖，集成方无需覆写 Bean。
     *
     * @param sessionManager 会话管理器（懒加载代理，用于读取会话级提示词）
     * @param globalPrompt   全局提示词 Bean
     * @return 两级提示词管理器的默认实现
     */
    @Bean
    @ConditionalOnMissingBean(PromptManager.class)
    public PromptManager promptManager(@Lazy HmsSessionManager sessionManager, String globalPrompt) {
        log.info("Creating DefaultPromptManager bean");
        return new DefaultPromptManager(sessionManager, globalPrompt);
    }

    /**
     * 注册默认的工具管理器 Bean（可被自定义实现覆盖）。
     *
     * @param toolRegistry    全局工具注册中心
     * @param sessionManager  会话管理器（用于读取会话级工具）
     * @return 两级工具管理器的默认实现
     */
    @Bean
    @ConditionalOnMissingBean(ToolManager.class)
    public ToolManager toolManager(ToolRegistry toolRegistry, HmsSessionManager sessionManager) {
        log.info("Creating DefaultToolManager bean");
        return new DefaultToolManager(toolRegistry, sessionManager);
    }
}
