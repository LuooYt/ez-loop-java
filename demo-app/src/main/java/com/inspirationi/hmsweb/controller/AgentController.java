package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.loop.agent.AgentDefinition;
import com.inspirationi.loop.agent.AgentMemory;
import com.inspirationi.loop.agent.AgentMemoryEntry;
import com.inspirationi.loop.agent.AgentRegistry;
import com.inspirationi.loop.agent.AgentRuntime;
import com.inspirationi.loop.agent.AgentWorkQueue;
import com.inspirationi.loop.agent.HeartbeatSpec;
import com.inspirationi.loop.agent.MemorySpec;
import com.inspirationi.loop.agent.ToolSelect;
import com.inspirationi.loop.api.AgentRuntimeFactory;
import com.inspirationi.loop.api.AgentSessionManager;
import com.inspirationi.loop.api.ChatMessage;
import com.inspirationi.loop.api.HmsSessionManager;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 多智能体演示 API —— 把 hms-core 的多智能体能力摊开到 HTTP 上，便于肉眼观察。
 * <p>
 * <b>这是演示层，不是 SDK 的一部分</b>：Agent 的注册在 SDK 里是编程 API
 * （{@code AgentRuntimeFactory} + {@code AgentRegistry}），生产集成方按自己的
 * 生命周期管理它。这里之所以有端点，是因为「注册」这一动作需要一个可被 curl
 * 触发的入口，否则多智能体能力只能靠写代码才能看见。
 * <p>
 * <b>仍然坚持的硬边界</b>：Agent 只能由人经本控制器注册，模型拿不到任何
 * 「创建 Agent」的工具 —— 见 {@link AgentRegistry} 的类说明。模型能做的只有
 * 发现已有 Agent 并委派。
 * <p>
 * 对话本身<b>不在这里</b>：建好会话后直接用既有的 {@code POST /api/chat/{sessionId}}，
 * 门面会按 sessionId 把消息路由到正确的 Agent。这正说明多智能体是叠加在既有接口
 * 之上的，而非另起一套。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    /** 演示用 Agent 的 id —— 与 `multi-agent-demo.mjs` 里的常量一一对应。 */
    private static final String RESEARCHER = "researcher";
    private static final String CODER = "coder";
    private static final String AUDITOR = "auditor";
    private static final String SCOUT = "scout";

    private final AgentSessionManager agentSessions;
    private final AgentRegistry registry;
    private final AgentRuntimeFactory factory;
    private final HmsSessionManager sessionManager;

    public AgentController(AgentSessionManager agentSessions,
                           AgentRegistry registry,
                           AgentRuntimeFactory factory,
                           HmsSessionManager sessionManager) {
        this.agentSessions = agentSessions;
        this.registry = registry;
        this.factory = factory;
        this.sessionManager = sessionManager;
    }

    // ==================== 装配演示 Agent ====================

    /**
     * 注册演示用的四个 Agent（幂等 —— 已存在的跳过，可反复调用）。
     * <p>
     * 四个 Agent 在<b>提示词、工具集、权限、记忆、主动性</b>上各不相同，正好覆盖
     * 多智能体的全部差异维度；默认 Agent 已在容器启动时由 {@code ApiAutoConfiguration}
     * 注册，这里不重复。
     */
    @PostMapping("/demo/setup")
    public ApiResponse<List<String>> setup() {
        List<String> result = new ArrayList<>();

        register(RESEARCHER, result, builder -> builder
                .name("资料研究员")
                .description("擅长查证事实、检索资料并归纳出结论。需要外部信息求证时找它。")
                .basePrompt("""
                        你是资料研究员。你的职责是查证与归纳，不是猜测。
                        - 需要事实时先检索，再下结论；检索不到就明说「未查证」，不要编造。
                        - 结论要给出依据，并注明不确定性。
                        """)
                .memory(MemorySpec.withCleanup())
                .tag("role", "research"));

        register(CODER, result, builder -> builder
                .name("Java 工程师")
                .description("负责编写、审查与解释 Java 代码。涉及实现与调试时找它。")
                .basePrompt("""
                        你是资深 Java 工程师。回答以可运行的代码为准。
                        - 给代码时标明所需 JDK 版本与依赖。
                        - 先给最小可运行版本，再说取舍；不要堆砌设计模式。
                        """)
                .memory(MemorySpec.simple())
                .tag("role", "engineering"));

        // 工具集按 Agent 收窄的标本：只读工具，连记忆写工具都不注册
        register(AUDITOR, result, builder -> builder
                .name("只读审查员")
                .description("只做审查与评估，不具备任何写入类工具。需要独立意见时找它。")
                .basePrompt("""
                        你是审查员。你只能读、不能写 —— 这是刻意的约束。
                        - 指出问题与风险，不替对方改。
                        - 结论分「必须改」与「建议改」两档。
                        """)
                .toolSelect(ToolSelect.readOnlyOnly())
                .memory(MemorySpec.readOnly())
                .tag("role", "review"));

        // 主动 Agent：注册后由心跳调度器按周期唤醒（首次唤醒在 intervalSeconds 之后）
        register(SCOUT, result, builder -> builder
                .name("巡检员")
                .description("主动智能体：定期自省并处理排入工作队列的事项。")
                .basePrompt("""
                        你是巡检员，由周期心跳唤醒，而非用户消息。
                        - 有排入的工作项就处理它；没有就复查状态并简要汇报。
                        - 不要为了显得忙碌而制造无意义的行动。
                        """)
                .memory(MemorySpec.withCleanup())
                .proactive(HeartbeatSpec.every(600))
                .tag("role", "ops"));

        return ApiResponse.ok(result);
    }

    private void register(String agentId, List<String> sink,
                          Consumer<AgentDefinition.Builder> customizer) {
        if (registry.contains(agentId)) {
            sink.add(agentId + "（已存在，跳过）");
            return;
        }
        AgentDefinition.Builder builder = AgentDefinition.builder(agentId);
        customizer.accept(builder);
        registry.register(factory.create(builder.build()));
        sink.add(agentId);
    }

    // ==================== 观察 ====================

    /** 列出全部 Agent 及其差异化配置 —— 这是「多个 Agent 确实不同」的直接证据。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentRuntime runtime : registry.list()) {
            agents.add(describe(runtime));
        }
        return ApiResponse.ok(agents);
    }

    @GetMapping("/{agentId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String agentId) {
        return ApiResponse.ok(describe(registry.require(agentId)));
    }

    /** Agent 级记忆 —— 跨该 Agent 全部会话共享，随 Agent 存活。 */
    @GetMapping("/{agentId}/memory")
    public ApiResponse<Map<String, Object>> memory(@PathVariable String agentId) {
        AgentRuntime runtime = registry.require(agentId);
        AgentMemory memory = runtime.memory();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", agentId);
        if (memory == null) {
            body.put("enabled", false);
            body.put("note", "该 Agent 未启用记忆（MemorySpec.disabled()）");
            return ApiResponse.ok(body);
        }

        body.put("enabled", true);
        body.put("size", memory.size());
        body.put("digestVersion", memory.digestVersion());
        // digest 就是会被拼进 system prompt 的那段文本 —— 亲自看一眼最直观
        body.put("digest", memory.digest());

        List<Map<String, Object>> entries = new ArrayList<>();
        for (AgentMemoryEntry entry : memory.entries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.id());
            item.put("content", entry.content());
            item.put("tags", entry.tags());
            item.put("source", entry.source());
            item.put("createdAt", entry.createdAt().toString());
            entries.add(item);
        }
        body.put("entries", entries);
        return ApiResponse.ok(body);
    }

    /** 指定会话的完整消息轨迹（含每一次工具调用的入参与结果）。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessage>> messages(@PathVariable String sessionId) {
        return ApiResponse.ok(sessionManager.getSessionMessages(sessionId));
    }

    // ==================== 驱动 ====================

    /** 为指定 Agent 创建会话；随后用既有的 {@code /api/chat/{sessionId}} 对话即可。 */
    @PostMapping("/{agentId}/sessions")
    public ApiResponse<Map<String, Object>> createSession(
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, String> body) {
        String sessionPrompt = body == null ? null : body.get("sessionPrompt");
        String sessionId = (sessionPrompt == null || sessionPrompt.isBlank())
                ? agentSessions.createAgentSession(agentId)
                : agentSessions.createAgentSession(agentId, sessionPrompt);
        return ApiResponse.ok(Map.of("agentId", agentId, "sessionId", sessionId));
    }

    /** 向主动 Agent 的工作队列投递一项工作 —— 下一次心跳时执行。 */
    @PostMapping("/{agentId}/work")
    public ApiResponse<Map<String, Object>> enqueueWork(
            @PathVariable String agentId,
            @RequestBody Map<String, String> body) {
        String prompt = body.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ApiResponse.fail("prompt 不能为空");
        }
        AgentWorkQueue.WorkItem item = agentSessions.enqueueWork(agentId, prompt);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workItemId", item.id());
        result.put("prompt", item.prompt());
        result.put("queueSize", registry.require(agentId).workQueue().size());
        return ApiResponse.ok(result);
    }

    /**
     * 手动触发一次心跳 —— 免去等待心跳周期。
     * <p>
     * 与调度器的自动触发走的是同一条 {@code AgentRuntime#triggerHeartbeatOnce()}，
     * 因此这里看到的就是真实心跳行为（含重入闸、自动暂停、空闲降频）。
     */
    @PostMapping("/{agentId}/heartbeat")
    public ApiResponse<Map<String, Object>> heartbeat(@PathVariable String agentId) {
        AgentRuntime runtime = registry.require(agentId);
        boolean ran = runtime.triggerHeartbeatOnce();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ran", ran);
        result.put("proactive", runtime.definition().isProactive());
        result.put("backendSessionId", runtime.backendSessionId());
        result.put("totalRuns", runtime.totalRuns());
        result.put("idleRuns", runtime.idleRuns());
        result.put("heartbeatPaused", runtime.isHeartbeatPaused());
        if (!ran) {
            result.put("note", runtime.definition().isProactive()
                    ? "未执行：后台会话正忙 / 处于降频跳过 / 已达连续运行上限而自动暂停"
                    : "该 Agent 是被动模式，没有心跳");
        }
        return ApiResponse.ok(result);
    }

    /** 把当前记忆摘要刷新进该 Agent 的全部会话 system prompt（会击穿 prompt cache，慎用）。 */
    @PostMapping("/{agentId}/memory/refresh")
    public ApiResponse<Map<String, Object>> refreshMemory(@PathVariable String agentId) {
        int refreshed = registry.require(agentId).refreshMemoryDigest();
        return ApiResponse.ok(Map.of("agentId", agentId, "refreshedSessions", refreshed));
    }

    // ==================== 内部 ====================

    private Map<String, Object> describe(AgentRuntime runtime) {
        AgentDefinition definition = runtime.definition();

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("agentId", definition.id());
        agent.put("name", definition.name());
        agent.put("description", definition.description());
        agent.put("activation", definition.activationMode().name());
        agent.put("delegatable", definition.delegatable());
        agent.put("permissionMode", runtime.permissionSettings().getCurrentMode().name());

        // 工具集：数量 + 具体清单。只读审查员的清单会明显短一截 —— 隔离是可见的
        agent.put("toolCount", runtime.toolRegistry().size());
        agent.put("tools", runtime.toolRegistry().getToolNames().stream().sorted().toList());

        agent.put("memoryEnabled", runtime.memory() != null);
        agent.put("memoryEntries", runtime.memory() == null ? 0 : runtime.memory().size());
        agent.put("basePrompt", definition.basePrompt());

        agent.put("activeSessions", runtime.sessionManager().getActiveSessionCount());
        agent.put("backendSessionId", runtime.backendSessionId());
        agent.put("tags", definition.tags());
        return agent;
    }
}
