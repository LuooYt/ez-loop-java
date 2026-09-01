package com.inspirationi.loop.config;

import com.inspirationi.loop.api.DefaultPromptManager;
import com.inspirationi.loop.api.PromptManager;
import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.i18n.PromptTranslationService;
import com.inspirationi.loop.mcp.McpManager;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.plugin.OutputStylePlugin;
import com.inspirationi.loop.plugin.PluginContext;
import com.inspirationi.loop.plugin.PluginManager;
import com.inspirationi.loop.telemetry.FeatureFlagService;
import com.inspirationi.loop.telemetry.FeatureGate;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 核心应用配置 —— 基础设施 Bean 装配。
 * <p>
 * 两级提示词由 {@link com.inspirationi.loop.api.PromptManager} 管理。
 * 两级工具由 {@link com.inspirationi.loop.api.ToolManager} 管理。
 * 工具注册见 {@link ToolConfiguration}。
 */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /** AI 服务提供商（openai / anthropic），配置项 hms-core.provider，默认 openai */
    @Value("${hms-core.provider:openai}")
    private String provider;

    // ==================== 全局提示词 ====================

    /** 全局提示词（从配置 hms-core.global-prompt 注入，为空则使用内置默认值） */
    @Value("${hms-core.global-prompt:}")
    private String globalPromptFromConfig;

    /**
     * 全局提示词 Bean —— 优先返回配置中的全局提示词，否则使用内置默认提示词。
     */
    @Bean
    public String globalPrompt() {
        // 可从 yml 配置读取，也可通过环境变量注入
        if (globalPromptFromConfig != null && !globalPromptFromConfig.isBlank()) {
            log.info("Global prompt loaded from config ({} chars)", globalPromptFromConfig.length());
            return globalPromptFromConfig;
        }
        log.info("Using built-in default global prompt ({} chars)",
                DefaultPromptManager.DEFAULT_GLOBAL_PROMPT.length());
        return DefaultPromptManager.DEFAULT_GLOBAL_PROMPT;
    }

    // ==================== 基础 Bean ====================

    /** 工具上下文 Bean —— 承载会话级共享状态（如 TASK_MANAGER、TOOL_REGISTRY）。 */
    @Bean
    public ToolContext toolContext() {
        return ToolContext.defaultContext();
    }

    /** 任务管理器 Bean —— 负责任务的创建、调度与状态跟踪。 */
    @Bean
    public TaskManager taskManager() {
        return new TaskManager();
    }

    /** MCP 管理器 Bean —— 由 API 调用方主动连接/注册 MCP 服务。 */
    @Bean
    public McpManager mcpManager() {
        return new McpManager();
    }

    /** 插件管理器 Bean —— 创建插件管理器并注册内置输出样式插件。 */
    @Bean
    public PluginManager pluginManager(ToolContext toolContext) {
        PluginManager manager = new PluginManager(toolContext);
        var stylePlugin = new OutputStylePlugin();
        manager.registerPlugin(stylePlugin, "builtin");
        return manager;
    }

    /** 当前生效大模型 Bean —— 根据 provider 选择 OpenAI 兼容或 Anthropic 原生模型。 */
    @Bean
    public ChatModel activeChatModel(
            @Qualifier("openAiChatModel") ChatModel openAiModel,
            @Qualifier("anthropicChatModel") ChatModel anthropicModel) {
        if ("anthropic".equalsIgnoreCase(provider)) {
            log.info("Using Anthropic native API");
            return anthropicModel;
        } else {
            log.info("Using OpenAI compatible API");
            return openAiModel;
        }
    }

    /** 提供商信息 Bean —— 依据 provider 读取对应的默认 Base URL 与模型名。 */
    @Bean
    public ProviderInfo providerInfo() {
        String baseUrl;
        String model;
        if ("anthropic".equalsIgnoreCase(provider)) {
            baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.anthropic.com");
            model = System.getenv().getOrDefault("AI_MODEL", "claude-sonnet-4-20250514");
        } else {
            baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com");
            model = System.getenv().getOrDefault("AI_MODEL", "gpt-4o");
        }
        return new ProviderInfo(provider, baseUrl, model);
    }

    /** 权限设置 Bean —— 纯内存管理，规则由 SDK 调用方通过 API 注入。 */
    @Bean
    public PermissionSettings permissionSettings() {
        return new PermissionSettings();
    }

    /** 权限规则引擎 Bean —— 基于风险等级进行权限评估。 */
    @Bean
    public PermissionRuleEngine permissionRuleEngine(PermissionSettings permissionSettings) {
        return new PermissionRuleEngine(permissionSettings);
    }

    // 注意：AutoCompactManager / TokenTracker / MetricsCollector 不作为全局 Bean 暴露。
    // 三者都是会话级状态，由 DefaultHmsSessionManager.createSession() 为每个会话单独创建
    // 并绑定该会话的 AgentLoop。曾经存在的全局 Bean 与会话实例互不相干，其统计值恒为 0，
    // 且会让使用方误以为注入即可拿到用量。会话级数据请通过
    // HmsSessionManager.getSessionTokenStats(sessionId) / getSessionMetrics(sessionId) 获取。

    /** 特性开关服务 Bean —— 管理可动态启用的功能开关。 */
    @Bean
    public FeatureFlagService featureFlagService() {
        return new FeatureFlagService();
    }

    /** 特性开关门面 Bean —— 供各组件查询特性开关状态。 */
    @Bean
    public FeatureGate featureGate(FeatureFlagService featureFlagService) {
        return new FeatureGate(featureFlagService);
    }

    // ==================== 提示词国际化====================

    /**
     * 提示词翻译服务 —— 检测系统语言，若非中文则通过大模型将内置中文提示词
     */
    @Bean
    public PromptTranslationService promptTranslationService(
            ChatModel activeChatModel, PromptManager promptManager, ToolRegistry toolRegistry,
            @Value("${hms-core.i18n.enabled:true}") boolean i18nEnabled) {
        return new PromptTranslationService(activeChatModel, promptManager, toolRegistry, i18nEnabled);
    }

    /**
     * 前置加载 Runner —— 启动时检测系统语言并按需翻译提示词。
     * 中文系统直接使用内置中文提示词；非中文系统自动翻译为对应语言。
     */
    @Bean
    public ApplicationRunner promptI18nRunner(PromptTranslationService translationService) {
        return args -> translationService.translateAllIfNeeded();
    }

    /**
     * 提供商信息 —— 记录 AI 提供商名称、Base URL 与模型名。
     */
    public record ProviderInfo(String provider, String baseUrl, String model) {}
}
