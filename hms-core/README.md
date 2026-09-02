# HMS Core — AI Agent SDK

> **AI Agent SDK for Java 集成** — 为 Java 应用提供完整的多轮 AI 对话、工具调用、权限管理和会话隔离能力。

## 📌 项目定位

HMS Core 是一个**嵌入式 AI Agent SDK**，供 Spring Boot 应用以程序化方式集成 AI Agent 能力。提供 `HmsSessionManager` 作为唯一对外入口，支持多会话隔离、流式输出、工具编排、权限控制和三层上下文压缩。

## ✨ 核心能力

### AI Agent 引擎
- 🤖 **Agent Loop** — 完整的 Agent 循环（阻塞 + 流式双模式），支持多轮对话和工具调用
- 📊 **Token 追踪** — 实时统计输入/输出/缓存四类 Token、上下文窗口使用率监控（4 级预警）
- 💰 **可覆写的计费** — `TokenPricing` 扩展点：内置价目表 + yml 覆盖，或注入自己的计费系统
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
- 📡 **丰富回调** — onToken / onToolUse / onThinking / onActivity / onAskUser / onPermissionRequest / onError
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
    @Override public void onToolUse(String toolName, String phase, String input, String result) {
        // 同一次调用触发多次：START → 若干 PROGRESS → END。
        // 统计用量只应在 "END" 计数 —— 逐条计会让用量翻几倍。
    }
    @Override public void onThinking(String thinking) {
        // AI 思考过程（Anthropic extended thinking）
    }
    @Override public void onActivity(SessionActivity activity, String detail) {
        // 运行时活动状态变化（仅在真正切换时触发）：
        // CALLING_MODEL / THINKING / RESPONDING / USING_TOOL / WAITING_USER / IDLE
        // detail 为补充信息，如 USING_TOOL 时的工具名
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
| `tool_use` | `toolName`、`phase`（`START` / `PROGRESS` / `END`）、`input`、`result`（超 5000 字符截断） |
| `thinking` | `thinking`（超 2000 字符截断） |
| `activity` | `activity`（状态枚举名）、`label`（中文文案）、`detail`（如工具名，可为 null） |
| `ask_user` | `question`、`options`（null 归一化为 `[]`） |
| `permission` | `toolName`、`description` |
| `compaction` | `layer`、`messagesBefore`、`messagesAfter`、`reason` |
| `complete` | `content`、`totalTokens`、`toolCallsCount`、`interrupted` |
| `error` | `message`、`code`（`HmsErrorCode` 的数值码） |

两条消费方必须知道的约定：

- **`tool_use` 同一次调用推送多次** —— 按 `phase` 分流：`START`（`result` 为 null）→ 若干 `PROGRESS`（进度行）→ `END`（带最终结果）。把每条当独立调用会让用量统计翻几倍。
- **`activity` 的 `IDLE` 往往送不到** —— SSE 连接在 `complete` 之后即关闭，收尾的 `IDLE` 已无接收端。消费方应把 `complete` / `error` 自身视作「回到空闲」的信号。

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

// Token 统计与费用
TokenStats stats = sessionManager.getSessionTokenStats(sessionId);
stats.inputTokens();          // 普通输入（不含缓存读取）
stats.outputTokens();
stats.cacheReadTokens();      // 缓存读取 —— 单价约为普通输入的 1/10
stats.cacheCreationTokens();  // 缓存写入
stats.cost();                 // BigDecimal，null 表示该模型定价未知
stats.pricingModel();         // 算费所用的模型名

// cost 为 null 与「费用为 0」是两件事，必须分开处理
stats.costIfKnown().ifPresentOrElse(
        c -> System.out.printf("费用 $%s（按 %s）%n", c, stats.pricingModel()),
        () -> System.out.println("该模型定价未知 —— 配 hms-core.pricing.* 或注入 TokenPricing"));
```

> ⚠️ **`cost` 为 `null` 表示「定价未知」，不要当作 0。** 二者若混用，「没配价目表」会被读成「没花钱」。
> 计费策略见下文 [Token 计费](#token-计费-tokenpricing)。

#### 运维管理

```java
// 清理空闲会话（超过指定秒数未活动的会话）
int cleaned = sessionManager.cleanupIdleSessions(1800); // 30 分钟

// 获取会话的指标收集器
MetricsCollector metrics = sessionManager.getSessionMetrics(sessionId);
```

#### 手动压缩

```java
CompactionResult result = sessionManager.compactNow(sessionId);

result.success();          // 是否实际压缩（历史过短或摘要失败时为 false）
result.layer();            // 手动触发恒为 CompactLayer.MANUAL
result.messagesBefore();   // 压缩前消息数
result.messagesAfter();    // 压缩后消息数
result.reason();           // 结果描述
```

与自动压缩的区别：**不看 token 阈值、不受熔断器约束**，直接走全量压缩层。熔断的目的是防止自动压缩在故障时反复烧钱，用户显式触发不适用这个理由；手动压缩失败也不累加 `consecutiveFailures`，不污染自动压缩的熔断预算。因此**熔断打开后仍可用手动压缩**，不必重启会话。

异常契约：

| 异常 | 场合 |
|------|------|
| `IllegalArgumentException` | 会话不存在 |
| `IllegalStateException` | 该会话正在执行请求 |

**为什么正在执行时必须拒绝**：并发压缩会产出 `tool_use` 无配对 `tool_result` 的历史，被上游以 400 拒绝，且损坏是**持久的** —— 历史已被替换，此后每一轮请求都会拿同一份坏历史再撞 400。所以这不是保守起见，而是正确性要求。已暂停（PAUSED）的会话允许压缩，「暂停 → 压缩 → 恢复」是预期用法。

> ⚠️ 手动压缩**同步返回结果、不发 SSE compaction 事件**。压缩事件回调是请求级的（每轮由 `AgentLoop` 重新注册），而「无请求在跑」恰是手动压缩唯一被允许的时机 —— 此时回调指向的 emitter 早已 complete，事件必然被丢弃。结果只能从返回值取。

### SessionInfo — 会话信息

```java
SessionInfo info = sessionManager.getSessionInfo(sessionId);

info.sessionId();       // 会话 ID
info.status();          // 生命周期：ACTIVE / PAUSED / DESTROYED
info.activity();        // 运行时活动：IDLE / CALLING_MODEL / THINKING / RESPONDING / USING_TOOL / WAITING_USER
info.sessionPrompt();   // 会话级提示词
info.toolNames();       // 已注册的工具名称列表
info.createdAt();       // 创建时间
info.lastAccessTime();  // 最后访问时间
info.idleSeconds();     // 空闲秒数
info.inputTokens();     // 累计输入 Token
info.outputTokens();    // 累计输出 Token
info.cost();            // 预估费用（BigDecimal），null = 该模型定价未知
info.pricingModel();    // 算费所用的模型名
info.messageCount();    // 消息轮数
```

> 💡 `status` 与 `activity` 是**正交的两个维度**：前者管「能否接收消息」，后者管「正在做什么」。一个 `ACTIVE` 会话既可能空闲，也可能正在调模型或执行工具；`PAUSED` 会话的 activity 必然是 `IDLE`，但反之不成立。
>
> `activity` 由 `AgentLoop.getActivity()` 实时读取，一次请求必然以 `IDLE` 收尾 —— 正常结束、异常、用户取消、撞迭代上限四条出路都由 `executeLoop` 的 `finally` 统一收敛。`AgentLoop` 是会话级持久对象，漏掉复位会让该会话此后每次查询都返回陈旧状态。

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

> ⚠️ 更新会话提示词有两个入口，语义不同：`HmsSessionManager.updateSessionPrompt(sessionId, prompt)` 会**同步刷新该会话 AgentLoop 的系统提示词**（替换 `messageHistory[0]`，保留对话历史）；而 `PromptManager.updateSessionPrompt(...)` 只改存储。要让改动对正在进行的会话立即生效，用前者。
>
> 两级提示词都是**纯内存状态**，应用重启后回落到配置/内置默认值。

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

### Token 计费 (TokenPricing)

费用计算是**可覆写的扩展点**，不是写死在 SDK 里的逻辑 —— 价格会变、新模型会出，硬编码意味着每次调价都要等一个新版本。

#### 方式一：配置覆盖内置价目表

内置覆盖 Claude（opus / sonnet / haiku）与 OpenAI（gpt-4o / gpt-4o-mini）。改价只需配 yml：

```yaml
hms-core:
  pricing:
    models:
      opus:                 # 键是模型名的「子串」，大小写不敏感
        input: 10.0         # 每百万 token 美元价
        output: 65.0
        cache-read: 1.2
      my-private-llm:       # 也可为内置表之外的模型新增费率
        input: 1.0
        output: 2.0
        cache-read: 0.1
```

三条规则：

- **子串匹配**：`opus` 能命中 `us.anthropic.claude-opus-5`。真实模型名常带网关前缀与日期后缀，精确匹配会让绝大多数模型名落空。
- **长模式优先**：`gpt-4o-mini` 不会被 `gpt-4o` 抢先命中（两者单价差约 16 倍）。优先级由模式长度决定，**与配置顺序无关**。
- **三项必须都填**：缺任一项则整条作废、回落内置默认值，并在启动日志打 warn。缺项按 0 补齐会让漏配变成「这项免费」，静默算出看似合理的错数。

启动日志会打印生效的模式，可据此确认配置被读到：

```
Creating BuiltinModelPricing bean (1 configured overrides, patterns: [my-private-llm, gpt-4o-mini, sonnet, gpt-4o, haiku, opus])
```

#### 方式二：接自己的计费系统

声明一个 `TokenPricing` Bean 即可**完全接管**（内置实现随即失效）：

```java
@Bean
TokenPricing tokenPricing(MyBillingService billing) {
    return (model, usage) -> billing.lookupRate(model)
            .map(rate -> rate.apply(usage));   // Optional.empty() = 定价未知
}
```

接口本身只有一个方法：

```java
Optional<BigDecimal> cost(String model, TokenUsage usage);
```

三处设计取舍值得说明：

| 取舍 | 原因 |
|------|------|
| 返回 `Optional` 而非直接给数 | 定价未知是常态（新模型、私有部署、兼容层网关）。若「金额」与「可不可信」走两条通道，调用方几乎必然只读前者 —— 此前 `isPricingKnown()` 就**从未被任何代码读取**，未知模型的费用被静默按 Sonnet 价目表算出并当作真实金额 |
| `BigDecimal` 而非 `double` | 金额不该用二进制浮点，累加多次调用会积累误差 |
| 无状态函数而非会话状态 | 查价目表是纯计算。此前它以「三个价格字段 + 模型名 + 定价是否已知」五个可变字段存在 `TokenTracker` 上，还配一个 `setModel` 去改 —— 把纯函数写成了状态机 |

`TokenUsage` 把四类 token 分开承载：缓存读取单价约为普通输入的 1/10，混入 `input` 会让长会话费用高估数倍；缓存写入反过来更贵，混入同样失真。

> 💡 **内置实现不对缓存写入计费**，沿用重构前的口径以免同一份用量在升级前后给出不同金额。这是一处已知低估（Anthropic 缓存写入约为基础输入价的 1.25 倍），需要精确计费请实现自己的 `TokenPricing`。

#### 迁移：`TokenTracker` 上的定价 API 已废弃

`setModel` / `estimateCost()` / `isPricingKnown()` / `getModelName()` 均标记 `@Deprecated`，仍可用但建议迁移：

```java
// 旧
double cost = tokenTracker.estimateCost();

// 新
Optional<BigDecimal> cost = pricing.cost(model, tokenTracker.usageSnapshot());
```

> ⚠️ **`estimateCost()` 有一处行为变化**：模型名未识别（或从未调用 `setModel`）时现在返回 `0.0`，而此前会按 Claude Sonnet 的价目表算出一个看似合理却与实际账单无关的金额。依赖旧行为的代码请迁移到 `TokenPricing` 并显式处理 `empty`。

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
│   ├── SessionActivity         // 运行时活动状态（6 态，与 SessionStatus 正交）
│   ├── HmsEvent                // 传输中立的 sealed 事件模型（9 种事件）
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
├── telemetry/                  // 遥测、计费与功能管理
│   ├── FeatureFlagService      // Feature Flag 服务（环境变量覆盖）
│   ├── MetricsCollector        // 本地指标收集
│   ├── TokenUsage              // 四类 token 用量（输入/输出/缓存读/缓存写）
│   ├── TokenPricing            // 计费策略接口 —— 集成方可注入 Bean 覆写
│   └── BuiltinModelPricing     // 内置价目表（支持 hms-core.pricing.* 覆盖）
├── config/                     // Spring 配置
│   ├── AppConfig               // 基础设施 Bean 装配
│   ├── PricingProperties       // hms-core.pricing.* 绑定
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
    │       │       ├── maybeAutoCompact() → 继续下一轮
    │       │       │   └── AutoCompactManager.autoCompactIfNeeded()
    │       │       │       ├── TokenTracker.shouldAutoCompact() (>93%)
    │       │       │       ├── ① MicroCompact（本地截断）
    │       │       │       ├── ② SessionMemoryCompact（AI 摘要，1次API调用）
    │       │       │       └── ③ FullCompact（全量兜底，PTL 重试+熔断器）
    │       │       │
    │       │       └── 无 tool_calls
    │       │           ├── maybeAutoCompact()   ← 纯文本轮次同样要压
    │       │           └── 循环结束
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

压缩有**两个入口**，共用同一批压缩层：

```
① 自动 —— AutoCompactManager.autoCompactIfNeeded()
    │   由 AgentLoop 每轮调用，两条出路都会经过（见上方核心流程）
    ├── 前置条件：未熔断 且 TokenTracker.shouldAutoCompact() (>93% 有效窗口)
    │
    ├── ① MicroCompact — 本地截断，无 API 调用
    │       保留最近 6 条 tool_result，时间感知（>10min 仅保留 2 条）
    │       未达阈值时也会每轮跑一次（不花钱）
    │       生效且未达 blocking 阈值(98%) → 就此返回，不进入付费层
    │
    ├── ② SessionMemoryCompact — AI 摘要，1 次 API 调用
    │       保留近期段，不拆分 tool 调用对
    │       失败 → 继续到下一层（递增 consecutiveFailures）
    │
    └── ③ FullCompact — 全量压缩，多次 API 调用（兜底）
            API Round 分组 → PTL gap 解析 → 逐步丢弃 → 熔断器
            连续 3 次失败 → 熔断，停止自动压缩尝试

② 手动 —— AutoCompactManager.compactNow()
    │   由 HmsSessionManager.compactNow(sessionId) 触发
    ├── 无前置条件：不读熔断标志、不看 token 阈值
    ├── 直接走 FullCompact（跳过 ①②），层级记为 MANUAL
    └── 失败不累加 consecutiveFailures —— 不占用自动压缩的熔断预算
```

> 💡 熔断只约束自动压缩。熔断打开后 `compactNow()` 依然可用，也可调 `resetCircuitBreaker()` 手动复位。

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
    max-sessions: 1000
  # 单轮最大迭代次数 —— 一次 send 内「模型调用 → 工具执行」最多循环多少轮。
  # 撞上限会截断回答并追加警告标记，长工具链任务可上调。<= 0 时回退到 50。
  max-iterations: 50
  # 等待用户回答（AI 提问 / 权限确认）的上限秒数
  # 超时后按默认值处理：提问 → skip，权限 → deny
  user-response-timeout-seconds: 300
  # 上下文窗口与压缩阈值 —— 详见下文「上下文窗口与压缩阈值」
  context-window: 200000
  reserved-tokens: 20000
  # Token 计费 —— 覆盖内置价目表（每百万 token 美元价）。
  # 键是模型名子串、大小写不敏感、长模式优先；三项须都填，缺项则整条作废。
  # 详见上文「Token 计费 (TokenPricing)」
  pricing:
    models:
      opus:
        input: 15.0
        output: 75.0
        cache-read: 1.5
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
| `HMS_CORE_CONTEXT_WINDOW` | ❌ | 上下文窗口大小（Token），仅在 `hms-core.context-window` 未配置时生效 | `200000` |
| `HMS_CORE_I18N_ENABLED` | ❌ | 提示词翻译开关 | `true` |

> ⚠️ 预留 Token 没有对应的环境变量，只能通过 `hms-core.reserved-tokens` 配置。

### 上下文窗口与压缩阈值

```
有效窗口 = context-window - reserved-tokens
压缩阈值 = 有效窗口 × 93%
```

判据是**最近一次请求的 prompt token 数**，不是累计用量 —— 累计几十万也不会触发压缩，这是正确设计（累计量与当前上下文大小无关）。

两个参数的配置优先级：`hms-core.*` 配置项 > `HMS_CORE_CONTEXT_WINDOW` 环境变量 > 内置默认值。环境变量只为「不经 Spring 直接 `new TokenTracker()`」的场景保留。

**配错的后果**：

| 情况 | 后果 |
|------|------|
| `context-window` 小于模型真实窗口 | 远未超限就判定超载（日志出现 >100% 占用率），反复发起压缩。而 Session Memory / 全量压缩两层都要把历史发给模型做摘要 —— 历史对上游其实完全合法，压缩即使成功也是白压 |
| `context-window` 大于模型真实窗口 | 压缩来不及触发，请求超限被上游直接拒绝 |
| 非正数，或 `reserved-tokens >= context-window` | 一律回退内置默认值。若不回退，有效窗口会归零、占用率恒为 0，压缩永不触发且症状极难定位 |

想在本地观察压缩行为，**调大 `reserved-tokens` 而不是调小 `context-window`**：

```yaml
hms-core:
  context-window: 200000    # 保持与模型真实窗口一致
  reserved-tokens: 170000   # 有效窗口 30000、阈值 27900，几轮长对话即可触及
```

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
| `0.2.0-SNAPSHOT` | 2026-09 | Token 计费抽象为可覆写扩展点 `TokenPricing`（内置价目表 + `hms-core.pricing.*` 覆盖）；`TokenStats` / `SessionInfo` 增加缓存 token 与 `cost` / `pricingModel`；`TokenTracker` 上的定价 API 全部废弃（详见下表） |
| `0.2.0-SNAPSHOT` | 2026-09 | 修复推理模型令压缩永久失效、流式降级后前端永停「思考中」等 8 处缺陷；新增熔断器重置 API（详见下表） |
| `0.2.0-SNAPSHOT` | 2026-09 | 新增会话运行时活动状态 `SessionActivity`（6 态）、`HmsCallbacks.onActivity` 与 `HmsEvent.Activity`；`onToolUse` 与 `HmsEvent.ToolUse` 增加 `phase` 参数；修复工具用量统计虚高 3 倍（详见下表） |
| `0.2.0-SNAPSHOT` | 2026-09 | 新增手动压缩 `compactNow(sessionId)`；上下文窗口与预留 Token 改为可配（`hms-core.context-window` / `reserved-tokens`）；修复三处压缩缺陷（详见下表） |
| `0.2.0-SNAPSHOT` | 2026-09 | 新增 Web 桥接层（`HmsEvent` / `EventBridgeCallbacks` / `PendingResponses` / `HmsSseBridge`），集成方 SSE 代码从约 270 行降至 1 行；修复三处回调缺陷（详见下表） |
| `0.2.0-SNAPSHOT` | 2026-08 | 重构为 HMS Core SDK，移除 CLI/TUI，新增会话隔离 API、两级提示词/工具管理、MCP HTTP SSE 传输 |
| `0.1.0` | 2025 | 初始版本 |

### 2026-09 Token 计费重构

计费此前是「半成品 + 死代码」：`estimateCost()` 在生产代码里**零调用**、`isPricingKnown()` **从未被读取**、价目表硬编码为 5 个 `if-else` + 15 个魔数，注释还停在 "Claude Sonnet 4"。

| 问题 | 影响 | 改动 |
|------|------|------|
| 价目表硬编码在 `TokenTracker` 里 | 价格会变、新模型会出，**每次调价都要发新版本**，集成方毫无补救手段 | 抽出 `TokenPricing` 接口；内置 `BuiltinModelPricing` 支持 `hms-core.pricing.*` 覆盖，集成方也可注入自己的 Bean 完全接管 |
| 「金额」与「可不可信」走两条通道 | `isPricingKnown()` 无人读取，未知模型的费用被**静默按 Sonnet 价目表算出**并当作真实金额 | 合成单一返回值 `Optional<BigDecimal>`，未知定价在类型上无法被忽略 |
| 费用用 `double` | 金额用二进制浮点，累加多次调用会积累误差 | 改用 `BigDecimal` |
| 定价状态化 | 三个价格字段 + 模型名 + `pricingKnown` 共 5 个可变字段，配一个 `setModel` 去改 —— 把纯函数写成了状态机 | `TokenPricing` 为无状态函数，单 Bean 全局共享、天然线程安全 |
| `gpt-4o-mini` 靠 if-else 顺序才不被 `gpt-4o` 抢匹配 | 加一个分支就可能悄悄破坏，两者单价差约 16 倍 | 改为「模式长者优先」，由构造时排序结构性保证，与配置顺序无关 |
| 费用从未接入查询链路 | `/cost` 命令只显示 token 数，一个金额都没有 —— 抽象完若仍无人调用，等于把死代码重构了一遍 | `TokenStats` / `SessionInfo` 增加 `cost` / `pricingModel`，`/tokens`、`/metrics` 与 `/cost`、`/context` 命令全部接入 |

> ⚠️ **行为变化**：`estimateCost()` 在模型名未识别时现在返回 `0.0`，此前会按 Sonnet 价目表算出一个看似合理却与实际账单无关的金额。`setModel` / `estimateCost` / `isPricingKnown` / `getModelName` 均已 `@Deprecated`，迁移方式见上文 [Token 计费](#token-计费-tokenpricing)。
>
> 测试见 `telemetry/BuiltinModelPricingTest`（12 项：单价隔离、匹配优先级、未知模型、配置覆盖、`BigDecimal` 精度）与 `config/PricingWiringTest`（5 项：用 `ApplicationContextRunner` 起真实容器验证 relaxed binding 与 `@ConditionalOnMissingBean` 的可覆写性）。
>
> 端到端契约由 `demo-app/verify-pricing.mjs` 验证（21 项）—— 单元测试证明不了序列化层的问题：`BigDecimal` 会不会变成字符串、`null` 会不会让 `Map.of` 抛 500、record 的派生方法会不会意外进 JSON。

### 2026-09 修复的 8 处缺陷

| 缺陷 | 影响 | 修复 |
|------|------|------|
| 摘要只读 `getText()` | **推理模型令压缩永久失效**：extended thinking 把产出放进 metadata，正文为空 → 判为「空摘要」→ PTL 重试 5 次全空 → 计入熔断 → 熔断永久，此后再不压缩，上下文涨到被上游 400 拒绝 | 新增 `SummaryText`：正文优先、正文空时回退读 `anthropicThinkingContents` |
| `SessionMemoryCompact` 三层链式取值 | `response.getResult().getOutput().getText()` 任一层为 null 即 NPE，被吞成 `FAILED` 并白耗熔断预算 | 与上同走 `SummaryText`，判空一并解决 |
| 熔断后无出路 | `resetCircuitBreaker()` 存在却未暴露，用户只能销毁会话、丢掉全部上下文重来 | 新增 `HmsSessionManager.resetCompactionCircuitBreaker(sessionId)` 与对应端点 |
| 流式降级后 `onToken` 零输出 | 前端气泡完全靠 token 累积、`complete` 只清光标不覆盖内容 —— **气泡永久停在「思考中」**，刷新才看得到回复 | `AgentLoop.replayFallbackText` 补发；仅在流式端零输出时补，避免中途断流后重复渲染前半段 |
| `DefaultHmsService` 是多会话已修 bug 的未修版本 | ① AskUser 回调注册后从不清理，后续请求的提问打给上一个接收端；② 用量取会话累计而非本轮增量（3 轮各 100 token 报成 600）；③ 中断的轮次报成 ok | 三处与 `DefaultHmsSessionManager` 对齐，抽出 `buildResponse` 统一处理 |
| `MicroCompact` 谎报消息条数 | 它就地替换 tool_result、**条数分毫不变**，却把「工具响应条数」塞进 `messagesBefore/messagesAfter` 推给前端 | 新增 `CompactionResult.microSuccess`，条数字段如实报历史长度，裁剪量进 `reason` |
| 未达阈值时的微压缩不通知观测方 | 历史确实被改写（超长 tool_result 换成占位文本），但 SSE 上没有任何事件可解释 —— 与达阈值路径对同一动作给出两种可观测性 | 该路径也 `notifyEvent` 并返回结果 |
| `TokenTracker` 两个 setter 绕过构造器校验 | 窗口设 0 → 占用率恒为 0 → **压缩永不触发**，正是构造器注释里说的「极难定位」的症状 | 抽出 `normalizeWindow` / `normalizeReserved` 共用；调窗口时顺带重新规范化预留值 |

> 前两个缺陷是同一条失效链的两端，都属「换个模型就静默失效」型 —— 原有压缩测试全部漏过，因为它们的 mock 模型总是正常返回正文。

### 2026-09 修复的压缩缺陷

| 缺陷 | 影响 | 修复 |
|------|------|------|
| 压缩检查只放在「有工具调用」分支 | **纯文本对话永不压缩** —— 不调工具的轮次在更早的 `break` 就退出了循环，上下文一路涨到超窗被上游拒绝 | 抽成 `AgentLoop.maybeAutoCompact()`，覆盖循环的两条出路 |
| `succeed()` 先替换历史再读 `before.size()` | `messagesBefore` **恒等于** `messagesAfter` —— `before` 就是调用方的历史列表本身、替换又是就地 `clear() + addAll()`，日志与 SSE 事件出现「FULL compact: 4 → 4 messages」这种压了却报没压的结果 | 替换前先取两个 size |
| 上下文窗口与预留 Token 硬编码 | 无法按实际模型调整。配小了过早压缩（白花摘要费用还丢上下文），配大了压缩来不及（请求超限被拒） | 改为 `hms-core.context-window` / `reserved-tokens`；非正数或 `reserved >= window` 回退默认值 |

> 回归测试见 `src/test/java/com/inspirationi/loop/core/compact/` 下的 `TextOnlyCompactionTest`、`CompactionCountReportingTest`、`ManualCompactTest`，以及 `api/ContextWindowConfigTest`。
>
> 前两个缺陷都是「静默失效」型 —— 原有三个压缩测试全部漏过，因为它们分别直接调 `autoCompactIfNeeded`、只断言装配、或用「每轮调一次工具」的 mock 模型恰好一直待在能触发的分支上。新测试改为端到端走 `HmsSessionManager` 的公开 API。

### 2026-09 修复的回调缺陷

| 缺陷 | 影响 | 修复 |
|------|------|------|
| `onPermissionRequest` 默认返回 `"deny"` | 只覆写 `onPermissionRequestAsync` 的集成方权限**永远被拒**，异步回调是死代码 | 默认改为返回 `null`（弃权），使异步回调可达；不覆写者行为不变 |
| 库内硬编码 `.get(30, SECONDS)` | 集成方配置 300 秒也无效，用户第 40 秒回答即被丢弃 | 改为 `hms-core.user-response-timeout-seconds`（默认 300）统一控制 |
| `onError` 从未被调用 | 错误回调的 `retry`/`abort` 语义未实现 | `DefaultHmsSessionManager.send` 捕获异常 → 通知回调 → 原样抛出 |

> 回归测试见 `src/test/java/com/inspirationi/loop/api/CallbackFallbackTest.java`（11 个用例）。

### 2026-09 修复的工具事件缺陷

| 缺陷 | 影响 | 修复 |
|------|------|------|
| `ToolEvent.Phase` 在 `DefaultHmsSessionManager` 被丢弃 | 同一次工具调用会发 START / PROGRESS / END 三类事件，却被同等对待逐条计入 `metrics.recordToolUse` —— **工具用量虚高 3 倍**；前端也按每条事件渲染，同一次调用出现多个重复气泡 | 按 `phase` 分流：用量只在 `END` 计一次；`onToolUse` 与 `HmsEvent.ToolUse` 增加 `phase` 参数，把阶段透给集成方 |
| 一次请求结束后活动状态可能停在中间态 | `AgentLoop` 是会话级持久对象 —— 异常、取消、撞迭代上限任一路径漏掉复位，该会话此后每次查询都返回陈旧状态，界面永久显示「调用工具」 | `executeLoop` 整体包 `try/finally`，四条出路统一收敛到 `IDLE` |

> 回归测试见 `src/test/java/com/inspirationi/loop/core/SessionActivityTest.java`（11 个用例，四条复位路径逐条钉住）。hms-core 现有 283 个单测。
>
> 签名变更真正危险的地方不在编译期：`SessionExtensionPointsTest` 覆写了旧的 3 参 `onToolUse`，加 `phase` 后它**不再覆写接口方法**，退化成一个无人调用的普通方法 —— 编译通过、断言恒空。与上面压缩缺陷的「静默失效」同属一类，改接口签名时必须搜一遍所有覆写点。

## 📄 License

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
