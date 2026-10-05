package com.inspirationi.loop.api;

import com.inspirationi.loop.agent.AgentDefinition;
import com.inspirationi.loop.agent.AgentMemory;
import com.inspirationi.loop.agent.AgentMemoryEntry;
import com.inspirationi.loop.agent.AgentRegistry;
import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.agent.AgentWorkQueue;
import com.inspirationi.loop.agent.InMemoryAgentMemory;
import com.inspirationi.loop.agent.MemorySpec;
import com.inspirationi.loop.core.compact.SummaryText;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.AgentMemoryRecallTool;
import com.inspirationi.loop.tool.impl.AgentMemoryRememberTool;
import com.inspirationi.loop.tool.impl.EnqueueWorkTool;
import com.inspirationi.loop.tool.impl.ListAgentsTool;
import com.inspirationi.loop.tool.impl.ListWorkTool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 按 {@link AgentDefinition} 装配出一个 {@link AgentRuntime}。
 * <p>
 * 这是「定义」变成「活实例」的唯一入口。装配分六步，依次是：权限、工具、记忆、
 * 工作队列与内置工具、Agent 级上下文、会话管理器。每一步都以「这个 Agent 与别的
 * Agent 有什么不同」为准则 —— 相同的部分直接共享进程级依赖，不同的部分才新建。
 * <p>
 * <b>默认 Agent 的特殊待遇</b>：它的基座提示词供给方为 {@code null}，于是 system
 * prompt 走旧的一参数拼接路径；它的权限设置由定义携带全局 Bean，于是
 * {@code /api/permissions} 改模式对它立即生效。这一点是 demo-app 零改动的根本。
 */
public class AgentRuntimeFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeFactory.class);

    /** 记忆清洗的提示词 —— 要求模型只做「合并同类项」，不要发挥。 */
    private static final String CONSOLIDATE_PROMPT = """
            下面是某个智能体积累的一批长期记忆条目。请把它们合并成更少、更精炼的条目。

            要求：
            - 保留其中的事实、结论、偏好与约定，去掉重复表述与过程细节
            - 不要新增任何原文没有的信息，不要发表评论
            - 每条一行，以 "- " 开头，直接输出合并结果，不要输出其他说明

            记忆条目：
            """;

    private final AgentRuntimeConfig config;
    private final AgentRegistry registry;

    public AgentRuntimeFactory(AgentRuntimeConfig config, AgentRegistry registry) {
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 按定义装配运行时。
     * <p>
     * 不注册到 {@link AgentRegistry} —— 注册由调用方发起（注册会启动心跳，
     * 而心跳在装配阶段启动毫无意义）。见 {@link AgentRegistry#register(AgentRuntime)}。
     *
     * @param definition Agent 定义
     * @return 装配完成、但尚未上线的运行时
     */
    public AgentRuntime create(AgentDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        String agentId = definition.id();

        PermissionSettings settings = resolvePermissionSettings(definition);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        engine.inheritPolicyFrom(config.globalPermissionEngine());

        ToolRegistry tools = selectTools(definition);
        AgentMemory memory = createMemory(definition);
        registerBuiltinTools(tools, definition, memory);

        AgentWorkQueue workQueue = new AgentWorkQueue();

        ToolContext agentContext = buildAgentContext(agentId, settings, tools, memory, workQueue);

        DefaultHmsSessionManager sessionManager = DefaultHmsSessionManager
                .builder(config.chatModel(), tools, config.promptManager())
                .permissionEngine(engine)
                .globalToolContext(agentContext)
                .cleanupScheduler(config.sharedScheduler())
                .agentId(agentId)
                .agentBasePrompt(basePromptSupplier(definition))
                .memoryDigest(digestSupplier(definition, memory))
                .tokenPricing(config.tokenPricing())
                .maxSessions(definition.maxSessions() > 0
                        ? definition.maxSessions() : config.defaultMaxSessions())
                .maxIterations(definition.maxIterations() > 0
                        ? definition.maxIterations() : config.defaultMaxIterations())
                .contextWindow(config.contextWindow())
                .reservedTokens(config.reservedTokens())
                .userResponseTimeoutSeconds(config.userResponseTimeoutSeconds())
                .cleanupIntervalSeconds(config.cleanupIntervalSeconds())
                .idleTimeoutSeconds(definition.idleTimeoutSeconds() > 0
                        ? definition.idleTimeoutSeconds() : config.defaultIdleTimeoutSeconds())
                .build();

        log.info("Agent runtime assembled: [{}] (tools={}, memory={}, proactive={})",
                agentId, tools.size(), memory != null, definition.isProactive());

        return new AgentRuntime(definition, tools, settings, engine,
                memory, workQueue, agentContext, sessionManager);
    }

    // ==================== 装配步骤 ====================

    /**
     * 解析权限设置的归属。
     * <p>
     * 定义里带了 {@link PermissionSettings}（默认 Agent 带的是全局 Bean）就用它 ——
     * 这是「默认 Agent 的权限模式仍由 {@code PermissionController} 统一控制」的实现；
     * 没带就新建一个私有实例，与全局互不影响。
     * <p>
     * 私有实例的规则<b>不继承全局</b>：规则是「用户对某个 Agent 的授权」，
     * 把针对 A 的 ALWAYS_ALLOW 复制给 B 等于悄悄扩大了授权范围。继承的只是
     * 危险检测与命令提取这类<b>策略</b>（见 {@code inheritPolicyFrom}）。
     */
    private PermissionSettings resolvePermissionSettings(AgentDefinition definition) {
        PermissionSettings settings = definition.permissionSettings() != null
                ? definition.permissionSettings()
                : new PermissionSettings();

        // 定义里声明的初始模式必须落地。静默忽略它是最坏的一种失败：调用方写了
        // .permissionMode(STRICT) 却得到一个默认模式的 Agent，而界面上看不出任何异常，
        // 直到某次高危操作被放行才暴露。
        //
        // 注意对默认 Agent 的含义：它携带的是全局 Bean，此时这一步会改写全局模式 ——
        // 那正是定义作者的显式意图（「这个 Agent 起始就处于严格模式」），不是串味。
        if (definition.permissionMode() != null) {
            settings.setCurrentMode(definition.permissionMode());
        }
        return settings;
    }

    /** 按 {@link com.inspirationi.loop.agent.ToolSelect} 从全局工具集中筛出本 Agent 的工具。 */
    private ToolRegistry selectTools(AgentDefinition definition) {
        ToolRegistry tools = new ToolRegistry();
        List<Tool> selected = definition.toolSelect().select(config.globalToolRegistry());
        for (Tool tool : selected) {
            tools.register(tool);
        }
        return tools;
    }

    /** 按 {@link MemorySpec} 创建记忆；未启用时返回 {@code null}。 */
    private AgentMemory createMemory(AgentDefinition definition) {
        MemorySpec spec = definition.memory();
        if (!spec.enabled()) {
            return null;
        }
        InMemoryAgentMemory memory = new InMemoryAgentMemory(spec);
        if (spec.cleanup()) {
            // 清洗器以闭包注入 —— agent 包因此不必依赖 Spring AI（见 MemorySummarizer）
            memory.setSummarizer(this::summarizeMemory);
        }
        return memory;
    }

    /**
     * 注册记忆/主动性/发现这三类内置工具。
     * <p>
     * <b>在 {@code ToolSelect} 筛选之后注册</b>：这些是本能力自带的工具，不属于全局
     * 工具集的可选部分。要关掉它们用各自的正规开关 —— 记忆写入用
     * {@code MemorySpec.writeTool(false)}，主动性工具随 {@code PASSIVE} 自动消失。
     */
    private void registerBuiltinTools(ToolRegistry tools, AgentDefinition definition,
                                      AgentMemory memory) {
        // 发现：所有 Agent 都能看到有哪些同行可委派
        tools.register(new ListAgentsTool());

        if (memory != null) {
            MemorySpec spec = definition.memory();
            if (spec.recallTool()) {
                tools.register(new AgentMemoryRecallTool());
            }
            if (spec.writeTool()) {
                tools.register(new AgentMemoryRememberTool());
            }
        }
        // 主动性工具只对主动 Agent 有意义 —— 被动 Agent 的队列无人消费，入队只会积压
        if (definition.isProactive()) {
            tools.register(new EnqueueWorkTool());
            tools.register(new ListWorkTool());
        }
    }

    /**
     * 构建 Agent 级工具上下文 —— 会话上下文的父级。
     * <p>
     * <b>必须逐键覆盖而不是新建空上下文</b>：会话的工具会回落读父级，若这里是个
     * 空壳，{@code TaskManager} / {@code McpManager} 等依赖全局上下文的对象就全
     * 读不到了。{@code childOf} 的语义正好相反 —— 本地写入遮蔽父级、本地缺失回落
     * 父级，于是 {@code PERMISSION_SETTINGS} 被这里换成 Agent 私有的那份，
     * 而 {@code EnterPlanModeTool} 之类的代码<b>一行不改</b>就读到了正确实例。
     */
    private ToolContext buildAgentContext(String agentId, PermissionSettings settings,
                                          ToolRegistry tools, AgentMemory memory,
                                          AgentWorkQueue workQueue) {
        ToolContext ctx = ToolContext.childOf(config.globalToolContext());
        ctx.set("PERMISSION_SETTINGS", settings);
        ctx.set(ListAgentsTool.AGENT_ID_KEY, agentId);
        ctx.set(ListAgentsTool.AGENT_REGISTRY_KEY, registry);
        ctx.set(AgentWorkQueue.CONTEXT_KEY, workQueue);
        ctx.set("TOOL_REGISTRY", tools);
        if (memory != null) {
            ctx.set(AgentMemory.CONTEXT_KEY, memory);
        }
        return ctx;
    }

    // ==================== 摘要供给 ====================

    /**
     * 基座提示词供给方。
     * <p>
     * 返回 {@code null} 表示「本 Agent 未声明自己的基座提示词」，于是 system prompt
     * 走旧的一参数路径（＝全局提示词 + 会话提示词）。默认 Agent 与任何没写
     * {@code basePrompt} 的 Agent 都落在这条路径上 —— 这是既有行为不被改写的原因。
     */
    private static Supplier<String> basePromptSupplier(AgentDefinition definition) {
        String basePrompt = definition.basePrompt();
        if (basePrompt == null || basePrompt.isBlank()) {
            return null;
        }
        return definition::basePrompt;
    }

    /** 记忆摘要供给方；未启用记忆或未开启摘要注入时为 {@code null}。 */
    private static Supplier<String> digestSupplier(AgentDefinition definition, AgentMemory memory) {
        if (memory == null || !definition.memory().digestInPrompt()) {
            return null;
        }
        return memory::digest;
    }

    /**
     * 用模型把一批旧记忆合并成精简条目 —— 记忆清洗的执行体。
     * <p>
     * 用 {@link SummaryText#extract} 而非直接读 {@code getResult().getOutput().getText()}：
     * 推理模型会把正文放进 thinking 分片，只读首个 Generation 会得到空串，
     * 清洗将被误判为失败。
     *
     * @return 合并后的正文；失败时由调用方（{@code InMemoryAgentMemory}）保留原条目
     */
    private String summarizeMemory(List<AgentMemoryEntry> batch) {
        StringBuilder sb = new StringBuilder(CONSOLIDATE_PROMPT);
        for (AgentMemoryEntry entry : batch) {
            sb.append("- ").append(entry.content()).append('\n');
        }
        Prompt prompt = new Prompt(List.of(new UserMessage(sb.toString())));
        return SummaryText.extract(config.chatModel().call(prompt), "AgentMemory");
    }
}
