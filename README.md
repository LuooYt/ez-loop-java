# HMS Core — AI Agent SDK for Java

> **让 Java 应用一键接入 AI 自主迭代范式能力** — 一个依赖、一次扫描、几行代码，即可获得完整的多轮 AI 对话、工具调用、权限管控与多会话隔离能力。

---

## 🚀 为什么选 HMS Core（低成本集成）

- **① 一个依赖搞定** — 引入 `hms-core` jar，Spring Boot 自动装配全部 Bean，无需手写任何配置类
- **② 开箱即用** — 内置 **20 个工具**（Web 搜索/抓取、任务管理、子 Agent、MCP、Skill…）+ 完整权限体系
- **③ 三行代码跑通** — 注入 `HmsSessionManager`，调用 `createSession()` → `send()`，对话即完成
- **④ 自带多语言** — 启动时自动检测 Windows/Linux 系统语言：中文系统直接使用内置中文提示词，非中文系统通过大模型自动翻译
- **⑤ 天然适合 Web** — 流式输出（SSE 友好）、异步回调、多会话隔离，可直接嵌入 REST API / 消息队列 / 微服务
- **⑥ 可无限扩展** — 自定义工具、Hook 钩子、插件系统、权限规则，按需注入

---

## 📦 快速集成（3 分钟跑通）

### 前置要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 25 | 需启用 `--enable-preview` |
| Maven | 3.9+ | 构建工具 |
| Spring Boot | 4.1.0-M2 | HMS Core 基于该里程碑版本构建 |
| API Key | - | Anthropic 或 OpenAI 兼容服务（如 DeepSeek） |

### ① 安装依赖

HMS Core 目前为 SNAPSHOT 版本，先从源码安装到本地 Maven 仓库：


### ② 在你的项目 `pom.xml` 中添加依赖

```xml
<dependency>
    <groupId>com.inspirationi</groupId>
    <artifactId>hms-core</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```


### ③ 配置 API Key

方式一：环境变量

```bash
# Anthropic 原生 API
export AI_API_KEY="sk-ant-xxx"
export HMS_CORE_PROVIDER="anthropic"

# 或 OpenAI 兼容 API（如 DeepSeek）
export AI_API_KEY="sk-xxx"
export HMS_CORE_PROVIDER="openai"
export AI_BASE_URL="https://api.deepseek.com"
export AI_MODEL="deepseek-chat"
```

方式二：`application.yml`（推荐，便于版本管理）

```yaml
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY:sk-xxx}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o}
          temperature: 0.7

hms-core:
  provider: ${HMS_CORE_PROVIDER:openai}
```

### ④ 扫描包并注入

在启动类中扫描 HMS Core 的自动配置包：

```java
@SpringBootApplication(scanBasePackages = {"com.inspirationi.loop", "com.yourcompany"})
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### ⑤ 完成 —— 三行代码实现 AI 对话

```java
@RestController
public class AiController {

    @Autowired
    private HmsSessionManager sessionManager;

    @PostMapping("/chat")
    public String chat(@RequestBody String message) {
        String sid = sessionManager.createSession();          // ① 创建会话
        HmsResponse response = sessionManager.send(sid, message); // ② 发送消息
        sessionManager.destroySession(sid);                   // ③ 销毁会话
        return response.content();
    }
}
```

🎉 完成！启动应用即可通过 `POST /chat` 获得 AI 回复 —— 支持多轮上下文、工具调用、中文提示词，全部内置。

> 💡 想先体验？仓库自带可视化演示应用（Web 界面 + SSE），在 `app/` 目录运行 `mvn spring-boot:run` 即可，地址 `http://localhost:8088`。

---

## 🎯 典型集成场景

### 场景一：REST 同步对话

```java
@PostMapping("/api/chat")
public Map<String, Object> chat(@RequestBody ChatReq req) {
    String sid = sessionManager.createSession();  // 每次请求独立会话（或按用户复用）
    HmsResponse r = sessionManager.send(sid, req.message());
    return Map.of(
        "content",  r.content(),
        "tokens",   r.totalTokens(),
        "toolCalls", r.toolCallsCount()
    );
}
```

### 场景二：SSE 流式对话（打字机效果）

```java
@GetMapping(value = "/api/chat/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@PathVariable String sessionId, @RequestParam String message) {
    SseEmitter emitter = new SseEmitter(60_000L);
    sessionManager.send(sessionId, message, new HmsCallbacks() {
        @Override public void onToken(String token) {
            emitter.send(SseEmitter.event().name("token").data(token));
        }
        @Override public void onComplete(HmsResponse r) {
            emitter.send(SseEmitter.event().name("done").data(r.content()));
            emitter.complete();
        }
    });
    return emitter;
}
```

### 场景三：完整回调（提问 / 权限 / 工具事件）

```java
HmsCallbacks callbacks = new HmsCallbacks() {
    @Override public void onToken(String token)          { /* 实时输出 */ }
    @Override public void onToolUse(String tool, String in, String out) { /* 工具调用日志 */ }
    @Override public void onThinking(String thinking)    { /* 思考过程展示 */ }

    // 异步向用户提问（WebSocket / SSE / 消息队列均可）
    @Override public CompletableFuture<String> onAskUserAsync(String question, List<String> options) {
        return uiService.askUser(question, options);   // 返回 Future，异步等待用户回答
    }
    // 权限确认
    @Override public CompletableFuture<String> onPermissionRequestAsync(String tool, String desc) {
        return uiService.confirmPermission(tool, desc);  // "allow" / "deny"
    }
    @Override public String onError(Throwable e) { return "abort"; }
};
HmsResponse r = sessionManager.send(sessionId, "帮我查一下最近的订单", callbacks);
```

> 💡 `HmsCallbacks` 所有方法都有默认空实现，只覆写需要的即可。Web 应用建议用 `onAskUserAsync` / `onPermissionRequestAsync` 异步模式，避免阻塞请求线程。

### 场景四：自定义工具

实现 `Tool` 接口，注册后 AI 即可调用：

```java
public class WeatherTool implements Tool {
    @Override public String name() { return "Weather"; }
    @Override public String description() { return "查询指定城市的天气"; }
    @Override public String inputSchema() {
        return """{ "type": "object",
              "properties": { "city": { "type": "string", "description": "城市名" } },
              "required": ["city"] }""";
    }
    @Override public boolean isReadOnly() { return true; }
    @Override public String execute(Map<String, Object> input, ToolContext ctx) {
        return "北京：晴，25°C";   // 可调用你自己的业务服务
    }
}

// 注册到会话（也可注册为全局工具）
@Autowired ToolManager toolManager;
toolManager.addSessionTool(sessionId, new WeatherTool());
```

### 场景五：多会话与生命周期

```java
// 每个用户/请求拥有独立会话，天然隔离上下文
String alice = sessionManager.createSession("你是订单客服助手。");
String bob   = sessionManager.createSession("你是数据分析专家。");

// 暂停/恢复/销毁
sessionManager.pauseSession(alice);
sessionManager.resumeSession(alice);
sessionManager.destroySession(bob);

// 空闲自动清理（定时任务，默认 5 分钟检查、30 分钟超时）
int cleaned = sessionManager.cleanupIdleSessions(1800);
```

---

## ✨ 开箱即用能力

### 🤖 AI Agent 引擎
- **Agent Loop** — 完整 Agent 循环（阻塞 + 流式双模式），多轮对话 + 工具调用 + 自动回传
- **Token 追踪** — 输入/输出 Token 实时统计、上下文窗口使用率监控
- **三层上下文压缩** — 微压缩（本地截断，0 API 调用）→ Session Memory（AI 摘要）→ 全量压缩，93% 阈值自动触发，熔断保护
- **Extended Thinking** — 支持 Anthropic extended thinking 思考过程展示

### 🔧 20 个内置工具
| 分类 | 工具 |
|------|------|
| Web | `WebFetch`、`WebSearch` |
| 编排 | `Agent`（子 Agent）、`SendMessage` |
| 任务 | `TaskCreate`、`TaskGet`、`TaskList`、`TaskUpdate`、`TaskStop`、`TaskOutput` |
| 效率 | `TodoWrite`、`Sleep`、`ToolSearch` |
| 交互 | `AskUserQuestion`、`EnterPlanMode`、`ExitPlanMode` |
| 配置 | `Config`、`Skill` |
| MCP | `ListMcpResources`、`ReadMcpResource` |

> 工具描述均为优化后的中文，非中文系统下自动翻译。自定义工具同样遵循同一套协议。

### 🔒 安全与权限
- **5 级权限模式** — `STRICT` / `SAFE` / `DEFAULT` / `TRUSTED` / `BYPASS`
- **8 步规则评估链** — 模式检查 → ToolContext 覆盖 → 风险等级 → 规则匹配 → 风险检测 → 用户确认
- **可扩展风险检测** — `RiskDetector` 接口注入场景特定安全检查
- **拒绝追踪** — 连续拒绝自动降级，防止 AI 反复试探

### 🔌 集成能力
- **MCP 协议** — 一键连接外部 MCP 服务器（StdIO / HTTP SSE），工具自动注册
- **Hook 系统** — 工具调用前后插入自定义逻辑（`PRE_TOOL_USE` / `POST_TOOL_USE` / `PRE_PROMPT` / `POST_RESPONSE`）
- **插件系统** — 编程式注册或从 JAR 加载插件
- **指标收集** — 工具使用、API 调用、Token 用量统计


---

## 📖 API 使用手册

### `HmsSessionManager` — 唯一对外入口

```java
@Autowired
private HmsSessionManager sessionManager;
```

#### 会话生命周期

```java
// 创建会话（使用默认中文会话提示词）
String sessionId = sessionManager.createSession();
// 带自定义会话提示词
String sessionId = sessionManager.createSession("你是一个 Java 后端专家。");

// 暂停 / 恢复
sessionManager.pauseSession(sessionId);
sessionManager.resumeSession(sessionId);
boolean paused = sessionManager.isPaused(sessionId);

// 销毁（释放全部资源）
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

// 带完整回调（见"场景三"）
HmsResponse r = sessionManager.send(sessionId, message, callbacks);
```

#### 会话控制与运维

```java
// 取消当前执行
sessionManager.cancel(sessionId);

// Token 统计
TokenStats stats = sessionManager.getSessionTokenStats(sessionId);

// 清理空闲会话
int cleaned = sessionManager.cleanupIdleSessions(1800); // 30 分钟

// 会话指标
MetricsCollector metrics = sessionManager.getSessionMetrics(sessionId);
```

### `SessionInfo` — 会话信息

```java
SessionInfo info = sessionManager.getSessionInfo(sessionId);
info.sessionId();       // 会话 ID
info.status();          // ACTIVE / PAUSED / DESTROYED
info.sessionPrompt();   // 会话级提示词
info.toolNames();       // 已注册工具列表
info.inputTokens();     // 累计输入 Token
info.outputTokens();    // 累计输出 Token
info.messageCount();    // 消息轮数
```

### `PromptManager` — 两级提示词

```java
@Autowired
private PromptManager promptManager;

// 更新全局提示词（影响所有新会话）
promptManager.updateGlobalPrompt("你是一个安全审计专家。");

// 更新会话提示词
promptManager.updateSessionPrompt(sessionId, "本次任务专注性能优化。");

// 查看 / 重置
String global = promptManager.getGlobalPrompt();
String session = promptManager.getSessionPrompt(sessionId);
promptManager.resetGlobalPrompt();
```

> 最终发给 AI 的 System Prompt = **全局提示词 + 会话提示词**。重置时自动使用当前系统语言版本。

### `ToolManager` — 工具管理

```java
@Autowired
private ToolManager toolManager;

toolManager.addSessionTool(sessionId, new WeatherTool());      // 会话级添加
List<String> tools = toolManager.getSessionToolNames(sessionId);
toolManager.removeSessionTool(sessionId, "WebSearch");
```

### `PermissionSettings` — 权限管理

| 模式 | 行为 | 适用场景 |
|------|------|----------|
| `STRICT` | 仅允许只读操作 | 分析、审查 |
| `SAFE` | 自动放行只读 + 低风险 | 客服系统等轻度操作 |
| `DEFAULT` | 中风险及以下自动放行，高风险需确认 | 日常交互（默认） |
| `TRUSTED` | 仅关键操作需确认 | 高度信任的内部系统 |
| `BYPASS` | 跳过所有权限检查 | 自动化（慎用） |

```java
@Autowired
private PermissionSettings permissionSettings;

permissionSettings.setCurrentMode(PermissionMode.TRUSTED);
permissionSettings.addUserRule(PermissionRule.forCommand("Bash", "git", PermissionBehavior.ALLOW));
permissionSettings.addSessionRule(rule);   // 会话级（不持久化）
permissionSettings.removeUserRule("Bash(git:*)");
permissionSettings.clearAll();
```

### `McpManager` — 外部 MCP 服务器

```java
@Autowired
private McpManager mcpManager;

mcpManager.connect("my-server", "python", List.of("server.py"), Map.of());   // 子进程
mcpManager.connectHttp("remote", "http://localhost:3000", Map.of());          // HTTP SSE
mcpManager.disconnect("my-server");
```

> MCP 工具自动注册为 `mcp__<server>__<tool>` 格式，AI 可直接调用。

### Hook 系统 — 工具调用拦截

```java
HookManager hooks = agentLoop.getHookManager();
hooks.register(HookType.PRE_TOOL_USE, "guard", ctx -> {
    if ("Bash".equals(ctx.getToolName())
            && ctx.getArguments().containsValue("rm -rf")) {
        return HookResult.ABORT;   // 拦截危险命令
    }
    return HookResult.CONTINUE;
}, 10);  // 优先级（越小越先执行）
```

---

## 🏗️ 架构设计

### 模块结构

```
com.inspirationi.loop
├── HmsApplication              // Spring Boot 自动配置入口（SDK 模式）
├── api/                        // 对外 API 层
│   ├── HmsSessionManager       // 会话隔离管理器（唯一对外入口）
│   ├── HmsService              // 单会话门面接口
│   ├── HmsCallbacks            // 回调集合（Token/工具/提问/权限）
│   ├── HmsResponse             // 响应模型
│   ├── SessionInfo             // 会话信息 DTO
│   ├── PromptManager           // 两级提示词管理
│   ├── ToolManager             // 两级工具管理
│   └── ApiAutoConfiguration    // API Bean 自动装配
├── core/                       // Agent 核心
│   ├── AgentLoop               // Agent 循环（阻塞+流式）
│   ├── AgentToolExecutor       // 工具执行器
│   ├── TaskManager             // 后台任务管理（虚拟线程池）
│   ├── HookManager             // Hook 系统（4 种钩子）
│   ├── TokenTracker            // Token 追踪
│   ├── CoordinatorMode         // 协调器模式（子 Agent 编排）
│   └── compact/                // 三层上下文压缩
├── i18n/                       // 多语言提示词
│   ├── SystemLanguageDetector  // 系统语言检测（Windows/Linux）
│   ├── PromptI18n              // 翻译结果缓存
│   └── PromptTranslationService// 大模型批量翻译服务
├── tool/                       // 工具系统
│   ├── Tool                    // 工具接口（name/description/schema/execute）
│   ├── ToolRegistry            // 工具注册中心
│   ├── ToolContext             // 工具执行上下文
│   ├── ToolCallbackAdapter     // Spring AI ToolCallback 适配器
│   └── impl/                   // 20 个内置工具
├── mcp/                        // MCP 协议客户端（StdIO + HTTP SSE）
├── permission/                 // 权限子系统（5 模式 + 8 步评估链）
├── plugin/                     // 插件系统
├── telemetry/                  // 遥测与 Feature Flag
├── config/                     // Spring 配置
└── util/                       // ModelResolver 等工具
```

### 一次请求的核心流程

```
HmsSessionManager.send(sessionId, message)
    │
    ├── 会话状态检查 + 输入校验
    ├── AgentLoop.run(message)
    │       ├── 追加 UserMessage
    │       ├── while (迭代 < 50):
    │       │       ├── ChatModel.call(prompt) → AI 回复
    │       │       ├── 有 tool_calls？
    │       │       │       ├── Hook + 权限评估（8 步链）
    │       │       │       ├── 执行工具 → 结果回传
    │       │       │       └── 继续下一轮
    │       │       └── 无 tool_calls → 结束
    │       └── 上下文超阈值？→ 三层压缩
    └── 回调 onComplete(response)
```

---

---

## 📄 License

本项目 参考 https://gitee.com/free/claude-code  采用[Apache License 2.0](LICENSE) 开源协议。
