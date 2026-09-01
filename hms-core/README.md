# HMS Core — AI Agent SDK

> **AI Agent SDK for Java 集成** — 为 Java 应用提供完整的多轮 AI 对话、工具调用、权限管理和会话隔离能力。

## 📌 项目定位

HMS Core 是一个**嵌入式 AI Agent SDK**，供 Spring Boot 应用以程序化方式集成 AI Agent 能力。提供 `HmsSessionManager` 作为唯一对外入口，支持多会话隔离、流式输出、工具编排、权限控制和三层上下文压缩。

## ✨ 核心能力

### AI Agent 引擎
- 🤖 **Agent Loop** — 完整的 Agent 循环（阻塞 + 流式双模式），支持多轮对话和工具调用
- 📊 **Token 追踪** — 实时统计输入/输出 Token、费用估算、上下文窗口使用率监控（4 级预警）
- 🗜️ **三层上下文压缩** — 微压缩 → Session Memory → 全量压缩，93% 阈值自动触发，熔断保护
- 💭 **Extended Thinking** — 支持 Anthropic extended thinking 思考过程展示

### 工具系统
- 🔧 **20 个内置工具** — Web 获取/搜索、任务管理（6 个）、子 Agent、MCP 桥接、Skill 调用、计划模式等
- 📋 **任务管理** — 后台任务创建/查询/更新/停止，支持自动执行和手动管理模式
- 🔌 **MCP 协议** — Model Context Protocol 客户端，StdIO + HTTP SSE 传输，工具发现与资源读取
- 🧩 **可扩展** — 自定义工具、Hook 钩子、权限规则、风险检测器，均通过 Spring Bean 注入

### 安全与权限
- 🔒 **5 级权限模式** — STRICT / SAFE / DEFAULT / TRUSTED / BYPASS
- ⚡ **8 步规则评估链** — 模式检查 → ToolContext 覆盖 → 风险等级 → 规则匹配 → 风险检测 → 用户确认
- 🛡️ **可扩展风险检测** — RiskDetector 接口支持注入场景特定的安全检查
- 📝 **三级规则管理** — 项目级 > 用户级 > 会话级，纯内存管理
- 🚫 **拒绝追踪** — 连续 3 次或累计 20 次拒绝触发自动降级

### 会话与集成
- 🔀 **多会话隔离** — 每个 session 独立的 AgentLoop、消息历史、工具注册和权限设置
- 📡 **丰富回调** — onToken / onToolUse / onThinking / onAskUser / onPermissionRequest / onError
- ⏱️ **会话生命周期** — 创建/暂停/恢复/销毁，空闲超时自动清理
- 📈 **指标收集** — 工具使用、API 调用次数、Token 用量统计
- 🌉 **开箱即用的桥接层** — `HmsEvent` 事件模型 + `EventBridgeCallbacks` + `HmsSseBridge`，Web 集成方一行接入 SSE，无需手写事件序列化与 Future 悬挂

## 📦 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 25 | 运行时（不使用 preview 特性，无需 `--enable-preview`） |
| Spring Boot | 4.1.1 | 应用框架与自动配置 |
| Spring AI | 2.0.1 | AI 模型调用（Anthropic + OpenAI） |
| Jackson | (Spring Boot 管理) | JSON 序列化与反序列化 |

## 🚀 快速开始

### 前置要求

- **JDK 25**（配置 `JAVA_HOME`）
- **Maven 3.9+**
- **API Key**（Anthropic 或 OpenAI 兼容服务）

### 1. 添加依赖

在你的 Spring Boot 项目的 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.inspirationi</groupId>
    <artifactId>hms-core</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```

> 💡 HMS Core 基于 Spring Boot 4.1.1 与 Spring AI 2.0.1（均为 GA）。你的项目需使用同一 Spring Boot 大版本 —— Spring AI 2.0.x 要求 Spring Boot 4.x / Framework 7.x，与 3.x 不兼容。

### 2. 配置 API Key

设置环境变量（二选一）：

```bash
# Anthropic 原生 API
export AI_API_KEY="sk-ant-xxx"
export HMS_CORE_PROVIDER="anthropic"

# OpenAI 兼容 API
export AI_API_KEY="sk-xxx"
export HMS_CORE_PROVIDER="openai"
```

可选配置：

```bash
# 自定义 API 地址
export AI_BASE_URL="https://api.deepseek.com"

# 自定义模型
export AI_MODEL="deepseek-chat"

# 上下文窗口大小（默认 200000）
export HMS_CORE_CONTEXT_WINDOW=200000
```

### 3. 启动应用

```java
@SpringBootApplication(scanBasePackages = {"com.inspirationi.loop", "com.yourcompany"})
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### 4. 开始使用

```java
@RestController
public class AiController {

    @Autowired
    private HmsSessionManager sessionManager;

    // 简单对话
    @PostMapping("/chat")
    public HmsResponse chat(@RequestBody String message) {
        String sid = sessionManager.createSession();
        HmsResponse response = sessionManager.send(sid, message);
        sessionManager.destroySession(sid);
        return response;
    }

    // 流式对话（SSE）—— 用内置的 HmsSseBridge，一行搞定
    @Autowired
    private HmsSseBridge sseBridge;

    @GetMapping(value = "/chat/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String sessionId, @RequestParam String message) {
        return sseBridge.stream(sessionId, message);
    }
}
```

> 💡 `HmsSseBridge` 已封装 SSE 发射器生命周期、事件 JSON 序列化、虚拟线程调度和用户回答的异步等待。详见下方「Web 集成（SSE）」。

## 📖 API 使用手册

### HmsSessionManager — 唯一对外入口

```java
@Autowired
private HmsSessionManager sessionManager;
```

#### 会话生命周期

```java
// 创建会话
String sessionId = sessionManager.createSession();
// 带自定义提示词
String sessionId = sessionManager.createSession("你是一个 Java 后端专家。");

// 暂停/恢复
sessionManager.pauseSession(sessionId);
sessionManager.resumeSession(sessionId);
boolean paused = sessionManager.isPaused(sessionId);

// 销毁（释放所有资源）
sessionManager.destroySession(sessionId);

// 查询
boolean exists = sessionManager.sessionExists(sessionId);
SessionInfo info = sessionManager.getSessionInfo(sessionId);
List<SessionInfo> all = sessionManager.listSessions();
int count = sessionManager.getActiveSessionCount();
```

#### 发送消息

```java
// 同步调用
HmsResponse response = sessionManager.send(sessionId, "分析项目结构");
System.out.println(response.content());

// 流式调用
sessionManager.sendStreaming(sessionId, "列出所有 Java 文件",
    token -> System.out.print(token));

// 带完整回调的调用
HmsCallbacks callbacks = new HmsCallbacks() {
    @Override public void onToken(String token) {
        // 每个输出 token 实时回调
    }
    @Override public void onToolUse(String toolName, String input, String result) {
        // 工具调用时触发
    }
    @Override public void onThinking(String thinking) {
        // AI 思考过程（Anthropic extended thinking）
    }
    @Override public String onAskUser(String question, List<String> options) {
        // AI 向用户提问时触发，返回用户回答
        return myUi.askUser(question, options);
    }
    @Override public String onPermissionRequest(String toolName, String description) {
        // 权限确认时触发 — 返回 "allow" 或 "deny"
        return myUi.confirm(toolName + ": " + description) ? "allow" : "deny";
    }
    @Override public void onComplete(HmsResponse response) {
        // 请求完成时触发
    }
    @Override public String onError(Throwable error) {
        // 异常时触发 — 返回 "retry" 重试或 "abort" 中止
        return "abort";
    }
};
sessionManager.send(sessionId, "帮我重构这段代码", callbacks);
```

> ⚠️ **同步 / 异步回调的优先级**：库先调同步版（`onAskUser` / `onPermissionRequest`），
> 同步版给出明确结论就**不再走异步版**。因此 Web 场景只覆写 `*Async` 时，
> 不要在同步版返回值 —— 默认实现已返回 `null`（弃权）以保证异步回调可达。
> 等待上限由 `hms-core.user-response-timeout-seconds` 控制（默认 300 秒），
> 超时按默认值处理：提问 → `skip`，权限 → `deny`。

### Web 集成（SSE）

Web 应用不必手写 `HmsCallbacks` 匿名类。hms-core 提供三层开箱即用的桥接：

| 类 | 包 | 依赖 | 职责 |
|---|---|---|---|
| `HmsEvent` | `api` | 无 | 传输中立的 sealed 事件模型，Jackson 直接序列化 |
| `PendingResponses` | `api` | 无 | 悬挂请求登记处（Future + 超时兜底） |
| `EventBridgeCallbacks` | `api` | 无 | `HmsCallbacks` → `Consumer<HmsEvent>` |
| `HmsSseBridge` | `web` | spring-webmvc | SSE 门面（发射器生命周期 + 序列化 + 线程调度） |

#### 最简用法

```java
@Autowired private HmsSseBridge sseBridge;

// ① 流式对话
@GetMapping(value = "/chat/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@PathVariable String sessionId, @RequestParam String message) {
    return sseBridge.stream(sessionId, message);
}

// ② 前端提交 AI 提问的回答 / 权限确认
sseBridge.submitAskResponse(sessionId, "用户的回答");
sseBridge.submitPermissionResponse(sessionId, "allow");   // "allow" / "deny"

// ③ 生命周期
sseBridge.release(sessionId);        // 销毁会话：释放 SSE 连接 + 等待中的请求
sseBridge.cancelPending(sessionId);  // 仅取消执行：保留 SSE 连接
```

#### 事件契约

`HmsEvent` 的 record 组件名即对外 JSON 字段名，`eventName()` 用作 SSE 的 `event:` 字段：

| `eventName()` | 字段 |
|---|---|
| `token` | `token` |
| `tool_use` | `toolName`、`input`、`result`（超 5000 字符截断） |
| `thinking` | `thinking`（超 2000 字符截断） |
| `ask_user` | `question`、`options`（null 归一化为 `[]`） |
| `permission` | `toolName`、`description` |
| `complete` | `content`、`totalTokens`、`toolCallsCount`、`interrupted` |
| `error` | `message` |

#### 接入其他传输（WebSocket / 消息队列）

复用前三个零 web 依赖的类，只写一个 sink：

```java
PendingResponses pending = new PendingResponses(300);
HmsCallbacks callbacks = new EventBridgeCallbacks(
        event -> myTransport.push(event.eventName(), objectMapper.writeValueAsString(event)),
        pending, sessionId);
sessionManager.send(sessionId, message, callbacks);

// 用户回答到达时，由另一个线程交付
pending.submitAskUser(sessionId, answer);
pending.submitPermission(sessionId, "allow");
```

> `spring-webmvc` 在 hms-core 中声明为 `optional`：不做 Web 集成的使用方不会被拖进 servlet 栈，
> 此时 `HmsSseBridge` 由 `@ConditionalOnClass(SseEmitter.class)` 静默跳过，
> 而 `HmsEvent` / `PendingResponses` / `EventBridgeCallbacks` 仍可正常使用。

#### 会话控制

```java
// 取消当前执行
sessionManager.cancel(sessionId);

// Token 统计
TokenStats stats = sessionManager.getSessionTokenStats(sessionId);
System.out.println("Input: " + stats.inputTokens() + ", Output: " + stats.outputTokens());
```

#### 运维管理

```java
// 清理空闲会话（超过指定秒数未活动的会话）
int cleaned = sessionManager.cleanupIdleSessions(1800); // 30 分钟

// 获取会话的指标收集器
MetricsCollector metrics = sessionManager.getSessionMetrics(sessionId);
```

### SessionInfo — 会话信息

```java
SessionInfo info = sessionManager.getSessionInfo(sessionId);

info.sessionId();       // 会话 ID
info.status();          // ACTIVE / PAUSED / DESTROYED
info.sessionPrompt();   // 会话级提示词
info.toolNames();       // 已注册的工具名称列表
info.createdAt();       // 创建时间
info.lastAccessTime();  // 最后访问时间
info.idleSeconds();     // 空闲秒数
info.inputTokens();     // 累计输入 Token
info.outputTokens();    // 累计输出 Token
info.messageCount();    // 消息轮数
```

### 提示词管理 (PromptManager)

```java
@Autowired
private PromptManager promptManager;

// 更新全局提示词（影响所有新创建的会话）
promptManager.updateGlobalPrompt("你是一个安全审计专家，请对代码进行全面审查。");

// 更新指定会话的个人提示词
promptManager.updateSessionPrompt(sessionId, "本次任务专注于性能优化。");

// 查看当前提示词
String global = promptManager.getGlobalPrompt();
String session = promptManager.getSessionPrompt(sessionId);
```

> 💡 HMS Core 支持两级提示词：**全局提示词**作用于所有会话，**会话提示词**仅作用于单个会话。最终发给 AI 的 System Prompt 是两者的拼接。

### 工具管理 (ToolManager)

```java
@Autowired
private ToolManager toolManager;

// 为指定会话注册额外工具
Tool myCustomTool = new MyCustomTool();
toolManager.addSessionTool(sessionId, myCustomTool);

// 获取会话的工具列表
List<String> tools = toolManager.getSessionToolNames(sessionId);

// 移除会话工具
toolManager.removeSessionTool(sessionId, "WebSearch");
```

### 权限管理

HMS Core 提供 5 种权限模式：

| 模式 | 行为 | 适用场景 |
|------|------|----------|
| `STRICT` | 仅允许只读操作 | 代码分析、架构审查 |
| `SAFE` | 自动放行 READ_ONLY + LOW 风险 | 客服系统等轻度操作场景 |
| `DEFAULT` | READ_ONLY + LOW + MEDIUM 自动放行，HIGH+ 需确认 | 日常交互（默认） |
| `TRUSTED` | 仅 CRITICAL 风险等级需确认 | 高度信任的内部系统 |
| `BYPASS` | 跳过所有权限检查 | 自动化脚本（需谨慎使用） |

```java
@Autowired
private PermissionSettings permissionSettings;

// 切换权限模式
permissionSettings.setCurrentMode(PermissionMode.TRUSTED);

// 添加永久规则
PermissionRule rule = PermissionRule.forCommand("Bash", "git", PermissionBehavior.ALLOW);
permissionSettings.addUserRule(rule);

// 添加会话级规则（不持久化）
permissionSettings.addSessionRule(rule);

// 移除规则
permissionSettings.removeUserRule("Bash(git:*)");

// 查看所有已保存的规则
List<String> rules = permissionSettings.listRules();

// 清除所有规则
permissionSettings.clearAll();
```

### MCP 服务器集成

```java
@Autowired
private McpManager mcpManager;

// 编程式连接 MCP 服务器
mcpManager.connect("my-server", "python",
    List.of("server.py"), Map.of());

// HTTP SSE 连接
mcpManager.connectHttp("remote-server", "http://localhost:3000", Map.of());

// 断开服务器
mcpManager.disconnect("my-server");

// 获取所有已发现的 MCP 工具
List<McpClient.McpTool> mcpTools = mcpManager.getAllTools();
```

> 💡 MCP 工具会自动注册到 `ToolRegistry`，AI 可以直接调用 `mcp__<server>__<tool>` 格式的工具。

### Hook 系统

在工具调用前后插入自定义逻辑。钩子是**会话级**的，从会话管理器取用：

```java
HookManager hooks = sessionManager.getSessionHooks(sessionId);

// PRE_TOOL_USE：阻止执行
hooks.register(HookType.PRE_TOOL_USE, "block-dangerous", ctx -> {
    if ("Bash".equals(ctx.getToolName())) {
        String command = (String) ctx.getArguments().get("command");
        if (command != null && command.contains("rm -rf")) {
            return HookResult.ABORT;   // 工具不执行，模型收到「已由 Hook 中止」
        }
    }
    return HookResult.CONTINUE;
}, 10);   // 优先级：数字越小越先执行

// PRE_TOOL_USE：改写入参
// getArguments() 返回的就是工具执行时用的那个 Map，原地改即生效
hooks.register(HookType.PRE_TOOL_USE, "redirect-to-sandbox", ctx -> {
    Object path = ctx.getArguments().get("file_path");
    if (path != null && path.toString().startsWith("/prod/")) {
        ctx.getArguments().put("file_path", "/sandbox" + path);
    }
    return HookResult.CONTINUE;
});

// POST_TOOL_USE：改写回传给模型的结果（脱敏、截断、补充说明）
hooks.register(HookType.POST_TOOL_USE, "redact-secrets", ctx -> {
    String result = ctx.getResult();
    if (result != null) {
        ctx.setResult(result.replaceAll("(?i)(api[_-]?key\\s*=\\s*)\\S+", "$1[REDACTED]"));
    }
    return HookResult.CONTINUE;
});
```

**只有这两个时机。** 单个钩子抛出的异常会被记录并忽略，不影响主流程与其余钩子。

### 权限拒绝审计

连续或累计拒绝达阈值后，需确认的工具会被自动拒绝（熔断）。注册回调可观测这一事件：

```java
sessionManager.getSessionDenials(sessionId).addDenialCallback((consecutive, total) -> {
    auditLog.warn("会话 {} 触及拒绝阈值：连续 {} 次 / 累计 {} 次", sessionId, consecutive, total);
    alarmService.send("权限拒绝异常，请检查会话");
});
```

回调只能**观测**，不改变放行/拒绝的决定 —— 决定权在 `PermissionRuleEngine` 与权限回调。

### 第三方扩展

不另设插件框架 —— 走 Spring 自身的机制即可：声明 `Tool` Bean，或把工具打成带
`@AutoConfiguration` 的 jar 放进 classpath。这样能直接用依赖注入、条件装配与
Bean 生命周期，无需自己管理 ClassLoader。

```java
@Configuration
public class MyToolsConfig {
    @Bean
    public MyCustomTool myCustomTool() {
        return new MyCustomTool();
    }
}
```

运行时增删工具用 `ToolManager`（见上文「工具管理」）。

### Feature Flag 功能开关

```java
@Autowired
private FeatureFlagService featureFlagService;

// 运行时开关功能（键名由使用方自定；SDK 内部不消费这些开关）
featureFlagService.setFlag("MY_FEATURE", false);
boolean enabled = featureFlagService.isEnabled("MY_FEATURE");

// 环境变量覆盖（最高优先级）
// export CLAUDE_CODE_FF_WORKTREE_MODE=false
```

## 🏗️ 架构设计

### 模块结构

```
com.inspirationi.loop
├── HmsApplication              // Spring Boot 自动配置入口（SDK 模式）
├── api/                        // 对外 API 层
│   ├── HmsSessionManager       // 会话隔离管理器（唯一对外入口接口）
│   ├── DefaultHmsSessionManager// 会话管理器实现
│   ├── HmsService              // 单会话门面接口（简化版）
│   ├── DefaultHmsService       // 单会话实现
│   ├── HmsCallbacks            // 回调集合
│   ├── HmsResponse             // 响应模型
│   ├── SessionInfo             // 会话信息 DTO
│   ├── HmsEvent                // 传输中立的 sealed 事件模型（7 种事件）
│   ├── EventBridgeCallbacks    // HmsCallbacks → Consumer<HmsEvent> 桥接
│   ├── PendingResponses        // 悬挂请求登记处（Future + 超时兜底）
│   ├── PromptManager / DefaultPromptManager  // 两级提示词管理
│   ├── ToolManager / DefaultToolManager      // 两级工具管理
│   └── ApiAutoConfiguration    // API Bean 自动装配
├── web/                        // Web 桥接层（依赖 spring-webmvc，optional）
│   ├── HmsSseBridge            // SSE 门面（发射器生命周期+序列化+线程调度）
│   └── WebBridgeAutoConfiguration // @ConditionalOnClass(SseEmitter) 守卫
├── core/                       // Agent 核心
│   ├── AgentLoop               // Agent 循环（阻塞+流式，Hook+权限+压缩集成）
│   ├── AgentToolExecutor       // 工具执行器
│   ├── TaskManager             // 后台任务管理（虚拟线程池+状态机）
│   ├── HookManager             // Hook 系统（4种钩子+优先级排序）
│   ├── TokenTracker            // Token 追踪+上下文窗口监控
│   ├── CoordinatorMode         // 协调器模式（子 Agent 编排）
│   └── compact/                // 三层压缩子系统
│       ├── AutoCompactManager  // 压缩编排器（级联+熔断器）
│       ├── MicroCompact        // 微压缩（本地截断，无 API 调用）
│       ├── SessionMemoryCompact// Session Memory（AI 摘要+保留近期段）
│       ├── FullCompact         // 全量压缩（兜底，PTL 重试）
│       └── CompactionResult    // 压缩结果记录
├── tool/                       // 工具系统
│   ├── Tool                    // 工具接口（name/description/schema/execute）
│   ├── ToolRegistry            // 工具注册中心
│   ├── ToolValidator           // 参数验证器
│   ├── ToolContext             // 工具执行上下文（无文件系统依赖）
│   ├── ToolCallbackAdapter     // Spring AI ToolCallback 适配器
│   ├── AbstractReadOnlyTool    // 只读工具基类
│   └── impl/                   // 20 个工具实现
│       ├── WebFetchTool/WebSearchTool         // Web 工具
│       ├── AgentTool/SendMessageTool          // Agent 间通信
│       ├── TaskCreate/Get/List/Output/Stop/Update  // 任务管理（6个）
│       ├── TodoWriteTool                      // 待办事项
│       ├── SkillTool                          // 技能调用
│       ├── SleepTool                          // 休眠
│       ├── ConfigTool                         // 配置读写
│       ├── ToolSearchTool                     // 工具搜索
│       ├── AskUserQuestionTool                // 用户提问
│       ├── EnterPlanModeTool/ExitPlanModeTool // 计划模式
│       └── ListMcpResourcesTool/ReadMcpResourceTool // MCP 资源
├── mcp/                        // MCP 协议客户端
│   ├── McpTransport            // 传输层接口
│   ├── StdioTransport          // StdIO 传输（子进程）
│   ├── HttpSseTransport        // HTTP SSE 传输
│   ├── McpClient               // MCP 客户端（JSON-RPC 2.0）
│   ├── McpManager              // 多服务器管理
│   └── McpException            // MCP 异常
├── permission/                 // 权限子系统
│   ├── PermissionTypes         // 类型定义（行为/模式/规则/决策/选择）
│   ├── PermissionRuleEngine    // 8 步规则评估链
│   ├── PermissionSettings      // 三级权限管理（纯内存）
│   ├── DangerousPatterns       // 危险命令模式检测
│   ├── RiskDetector            // 可扩展风险检测器接口
│   └── DenialTracker           // 拒绝追踪（连续3/累计20阈值）
├── telemetry/                  // 遥测与功能管理
│   ├── FeatureFlagService      // Feature Flag 服务（环境变量覆盖）
│   └── MetricsCollector        // 本地指标收集
├── config/                     // Spring 配置
│   ├── AppConfig               // 基础设施 Bean 装配
│   └── ToolConfiguration       // 工具注册
└── util/
    └── ModelResolver           // 模型别名解析
```

### 核心流程

```
HmsSessionManager.createSession()
    │
    ├── 创建 AgentLoop 实例（含独立 ChatModel、ToolRegistry、PermissionSettings）
    ├── 复制全局工具注册表到会话级
    ├── 构建 System Prompt（全局提示词 + 会话提示词）
    └── 注册到会话 Map

HmsSessionManager.send(sessionId, message)
    │
    ├── Session 状态检查（ACTIVE/PAUSED/DESTROYED）
    ├── 输入验证（null/empty 检查）
    ├── 设置回调（onToken/onToolUse/onAskUser/...）
    ├── AgentLoop.run(userMessage)
    │       │
    │       ├── 追加 UserMessage 到消息历史
    │       ├── while (iteration < MAX_ITERATIONS) {
    │       │       │
    │       │       ├── ChatModel.call(prompt) → AI 回复
    │       │       ├── TokenTracker.recordUsage()
    │       │       │
    │       │       ├── 检测 tool_calls：
    │       │       │   ├── PreToolUse Hook → 权限规则引擎评估
    │       │       │   │   ├── BYPASS → ALLOW
    │       │       │   │   ├── STRICT → 只读 ALLOW / 写 DENY
    │       │       │   │   ├── ToolContext 模式覆盖
    │       │       │   │   ├── 风险等级自动放行
    │       │       │   │   ├── alwaysDeny 匹配 → DENY
    │       │       │   │   ├── alwaysAllow 匹配 → ALLOW
    │       │       │   │   ├── RiskDetector 检测
    │       │       │   │   └── 默认 → 触发 onPermissionRequest 回调
    │       │       │   ├── 执行工具 → ToolCallbackAdapter.call()
    │       │       │   └── PostToolUse Hook
    │       │       │
    │       │       ├── 追加 AssistantMessage + ToolResponseMessage
    │       │       │
    │       │       ├── AutoCompactManager.autoCompactIfNeeded()
    │       │       │   ├── TokenTracker.shouldAutoCompact() (>93%)
    │       │       │   ├── ① MicroCompact（本地截断）
    │       │       │   ├── ② SessionMemoryCompact（AI 摘要，1次API调用）
    │       │       │   └── ③ FullCompact（全量兜底，PTL 重试+熔断器）
    │       │       │
    │       │       └── 无 tool_calls → 循环结束
    │       │   }
    │       └── 返回 HmsResponse
    │
    └── 回调 onComplete(response)
```

### 权限评估链（8 步）

```
工具调用 → PermissionRuleEngine.evaluate()
    │
    ├── ① BYPASS 模式 → 直接 ALLOW
    ├── ② STRICT 模式 → 只读 ALLOW，写操作 DENY
    ├── ③ ToolContext 模式覆盖（会话级动态覆盖）
    ├── ④ 风险等级自动放行（基于 autoAllowUpTo 映射）
    ├── ⑤ alwaysDeny 规则匹配 → DENY
    ├── ⑥ alwaysAllow 规则匹配 → ALLOW
    ├── ⑦ RiskDetector 检测 → 有风险标记 ASK
    └── ⑧ 默认 → 需要用户确认（ASK）
             ↓
        ALLOW_ONCE / ALWAYS_ALLOW / DENY_ONCE / ALWAYS_DENY
```

### 三层压缩架构

```
AutoCompactManager.autoCompactIfNeeded()
    │
    ├── 前置条件：TokenTracker.shouldAutoCompact() (>93% 上下文窗口使用率)
    │
    ├── ① MicroCompact — 本地截断，无 API 调用
    │       保留最近 6 条 tool_result，时间感知（>10min 仅保留 2 条）
    │       失败 → 继续到下一层（递增 consecutiveFailures）
    │
    ├── ② SessionMemoryCompact — AI 摘要，1 次 API 调用
    │       保留近期段，不拆分 tool 调用对
    │       失败 → 继续到下一层（递增 consecutiveFailures）
    │
    └── ③ FullCompact — 全量压缩，多次 API 调用（兜底）
            API Round 分组 → PTL gap 解析 → 逐步丢弃 → 熔断器
            连续 3 次失败 → 停止压缩尝试
```

### 会话隔离架构

```
sessionManager
    │
    ├── sessionId: "abc123"
    │   ├── AgentLoop → 独立 ChatModel 实例
    │   ├── ToolRegistry → 从全局复制 + 会话级扩展
    │   ├── PermissionSettings → 共享全局设置 + 会话级规则
    │   ├── PromptManager → 全局提示词 + 会话提示词
    │   └── MetricsCollector → 独立指标统计
    │
    ├── sessionId: "def456"
    │   ├── AgentLoop → 独立 ChatModel 实例
    │   ├── ToolRegistry → 从全局复制 + 会话级扩展
    │   └── ...（完全隔离）
    │
    └── cleanupScheduler → 定时清理空闲会话（默认 5 分钟检查，30 分钟超时）
```

## ⚙️ 配置参考

### application.yml

```yaml
# HMS Core 配置
hms-core:
  provider: ${HMS_CORE_PROVIDER:openai}    # API 提供者: openai / anthropic
  session:
    idle-timeout-minutes: 30
    cleanup-interval-minutes: 5
  # 等待用户回答（AI 提问 / 权限确认）的上限秒数
  # 超时后按默认值处理：提问 → skip，权限 → deny
  user-response-timeout-seconds: 300
  sse:
    # SSE 连接空闲超时（分钟），需长于单轮 Agent 执行的预期耗时
    emitter-timeout-minutes: 30
  metrics:
    enabled: true
    flush-interval-seconds: 60

# Spring AI 配置 - Anthropic
spring:
  ai:
    anthropic:
      api-key: ${AI_API_KEY:}
      base-url: ${AI_BASE_URL:https://api.anthropic.com}
      chat:
        options:
          model: ${AI_MODEL:claude-sonnet-4-20250514}
          max-tokens: ${AI_MAX_TOKENS:8096}
          temperature: 0.7

# Spring AI 配置 - OpenAI（兼容所有 OpenAI 格式 API）
    openai:
      api-key: ${AI_API_KEY:}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o}
```

### 环境变量

| 变量 | 必须 | 说明 | 默认值 |
|------|------|------|--------|
| `AI_API_KEY` | ✅ | API 密钥 | - |
| `HMS_CORE_PROVIDER` | ❌ | 提供者 (`openai`/`anthropic`) | `openai` |
| `AI_BASE_URL` | ❌ | API 基础 URL | 按提供者不同 |
| `AI_MODEL` | ❌ | 模型名称 | 按提供者不同 |
| `AI_MAX_TOKENS` | ❌ | 最大 Token 数 | `8096` |
| `HMS_CORE_CONTEXT_WINDOW` | ❌ | 上下文窗口大小（Token） | `200000` |
| `HMS_CORE_I18N_ENABLED` | ❌ | 提示词翻译开关 | `true` |

## 📐 模型别名

HMS Core 内置模型别名解析（`ModelResolver`），支持短名称映射：

| 别名 | 解析为 |
|------|--------|
| `haiku` / `haiku-3` / `claude-3-haiku` | `claude-3-haiku-20240307` |
| `sonnet-3.5` / `claude-3.5-sonnet` | `claude-3-5-sonnet-20241022` |
| `sonnet` / `sonnet-4` / `claude-sonnet-4` | `claude-sonnet-4-20250514` |
| `opus` / `opus-4` / `claude-opus-4` | `claude-opus-4-20250514` |
| `gpt-4` / `gpt-4o` / `gpt-4o-mini` | 直接透传 |
| `o1` / `o1-mini` / `o3` / `o3-mini` | 直接透传 |

## 📄 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| `0.2.0-SNAPSHOT` | 2026-09 | 新增 Web 桥接层（`HmsEvent` / `EventBridgeCallbacks` / `PendingResponses` / `HmsSseBridge`），集成方 SSE 代码从约 270 行降至 1 行；修复三处回调缺陷（详见下表） |
| `0.2.0-SNAPSHOT` | 2026-08 | 重构为 HMS Core SDK，移除 CLI/TUI，新增会话隔离 API、两级提示词/工具管理、MCP HTTP SSE 传输 |
| `0.1.0` | 2025 | 初始版本 |

### 2026-09 修复的回调缺陷

| 缺陷 | 影响 | 修复 |
|------|------|------|
| `onPermissionRequest` 默认返回 `"deny"` | 只覆写 `onPermissionRequestAsync` 的集成方权限**永远被拒**，异步回调是死代码 | 默认改为返回 `null`（弃权），使异步回调可达；不覆写者行为不变 |
| 库内硬编码 `.get(30, SECONDS)` | 集成方配置 300 秒也无效，用户第 40 秒回答即被丢弃 | 改为 `hms-core.user-response-timeout-seconds`（默认 300）统一控制 |
| `onError` 从未被调用 | 错误回调的 `retry`/`abort` 语义未实现 | `DefaultHmsSessionManager.send` 捕获异常 → 通知回调 → 原样抛出 |

> 回归测试见 `src/test/java/com/inspirationi/loop/api/CallbackFallbackTest.java`（11 个用例）。

## 📄 License

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
