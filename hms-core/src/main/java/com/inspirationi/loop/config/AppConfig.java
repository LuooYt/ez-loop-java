package com.inspirationi.loop.config;

import com.inspirationi.loop.api.DefaultPromptManager;
import com.inspirationi.loop.api.PromptManager;
import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.i18n.PromptTranslationService;
import com.inspirationi.loop.mcp.McpManager;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.telemetry.FeatureFlagService;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 核心应用配置 —— 基础设施 Bean 装配。
 * <p>
 * 两级提示词由 {@link com.inspirationi.loop.api.PromptManager} 管理。
 * 两级工具由 {@link com.inspirationi.loop.api.ToolManager} 管理。
 * 工具注册见 {@link ToolConfiguration}。
 * <p>
 * 自动装配链的第一环 —— 产出 ChatModel / ToolContext / PermissionRuleEngine
 * 等基础 Bean，后续 {@link ToolConfiguration} 等配置类依赖它们。
 * 注册见 {@code META-INF/spring/...AutoConfiguration.imports}。
 */
@AutoConfiguration
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

    // 注意：不再提供 PluginManager Bean。曾有一套 582 行的插件框架（URLClassLoader
    // 隔离 + MANIFEST 的 Plugin-Class + 生命周期回调），但 loadPlugin(Path) 从未被
    // 调用，整套机制只用来 new 一个编译期就确定的内建插件，而该插件的状态也无人消费。
    // Spring 本身就是插件容器：第三方要扩展工具，声明 Tool Bean 或提供带
    // @AutoConfiguration 的 jar 即可，还免费获得依赖注入、条件装配与生命周期管理，
    // 也不必自己承担 ClassLoader 泄漏的风险。

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

    /**
     * 特性开关服务 Bean —— 供使用方按环境变量 / 运行时注入自定义开关。
     * <p>
     * SDK 内部不消费它（各能力的开关走 {@code hms-core.*} 配置项），保留是因为
     * 它是可直接注入使用的通用设施。曾经还有一个 {@code FeatureGate} 门面，
     * 注册了 worktree / lsp / voice 等 11 个 gate —— 那些 flag 既不在本服务的
     * 默认值里（查不到即返回 false），对应的工具在本项目也不存在，属于从 CLI
     * 抄来的空壳，已删除。
     */
    @Bean
    public FeatureFlagService featureFlagService() {
        return new FeatureFlagService();
    }

    // ==================== 提示词国际化====================

    /**
     * 提示词翻译服务 —— 检测系统语言，若非中文则通过大模型把内置中文提示词翻译过去。
     * <p>
     * <b>默认关闭</b>（{@code hms-core.i18n.enabled}）。它挂在同步
     * {@link ApplicationRunner} 上，非中文系统下要串行发起约 8 次大模型调用
     * （全部内置提示词 + 每个工具的 description 与 inputSchema），启动因此阻塞
     * 数十秒 —— 容器健康检查通常等不到那时候，Pod 会在翻译完成前被 kill 并反复
     * 重启。另有三个副作用：结果只存内存，每次冷启动重新烧一遍 token；翻译不
     * 确定，同一提示词两次启动的结果可能不同，Agent 行为不可复现；失败后静默
     * 回落中文，非中文用户只会看到一行 warn。
     * <p>
     * 需要本地化提示词时显式开启；更稳的做法是把翻译前移到构建期 —— 生成的文案
     * 提交进仓库、人工过一遍，既确定又零启动开销。
     */
    @Bean
    public PromptTranslationService promptTranslationService(
            ChatModel activeChatModel, PromptManager promptManager, ToolRegistry toolRegistry,
            @Value("${hms-core.i18n.enabled:false}") boolean i18nEnabled) {
        return new PromptTranslationService(activeChatModel, promptManager, toolRegistry, i18nEnabled);
    }

    /**
     * 前置加载 Runner —— 启动时检测系统语言并按需翻译提示词。
     * <p>
     * 默认不做任何事（见 {@link #promptTranslationService}）：中文系统本就直接
     * 使用内置中文提示词，非中文系统需显式开启 {@code hms-core.i18n.enabled}。
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
