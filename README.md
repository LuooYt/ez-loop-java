<div align="center">

# HMS Core

**Production-grade AI Agent SDK for the JVM**

面向 JVM 的生产级 AI Agent SDK

[![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-6DB33F?style=flat-square)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](LICENSE)

**[English](#english) · [中文](#中文)**

</div>

---

<a name="english"></a>

## English

### Overview

HMS Core brings the autonomous-agent paradigm to Spring applications: an explicit
multi-iteration reasoning loop, tool invocation with graduated permission control,
session isolation, and layered context compaction — all behind a single dependency
and Spring Boot auto-configuration.

It is built for teams that need agent capability inside an existing JVM service,
not a separate Python sidecar.

```java
String sessionId = sessionManager.createSession();
HmsResponse response = sessionManager.send(sessionId, "Summarise today's incidents");
```

That is the whole integration surface. Multi-turn context, tool dispatch, permission
enforcement and token accounting are already wired.

### Design principles

| Principle | What it means in practice |
|---|---|
| **Zero-ceremony integration** | One JAR, auto-configured beans. No configuration classes to author. |
| **Explicit control flow** | The agent loop is ordinary Java you can read, breakpoint and reason about — not a hidden framework callback graph. |
| **Provider-neutral** | Anthropic and OpenAI-compatible endpoints via Spring AI. No vendor-specific types leak into your code. |
| **Safe by default** | Tools are risk-classified; the default permission mode refuses destructive operations without explicit consent. |
| **Observable** | Token usage, tool invocations, API calls, compaction events and error taxonomy are all surfaced. |
| **Extensible at every seam** | Custom tools, hooks, permission rules and risk detectors register as ordinary Spring beans. |

### Quick start

**1 — Declare the dependency**

```xml
<dependency>
    <groupId>com.inspirationi</groupId>
    <artifactId>hms-core</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```

**2 — Configure the model provider**

```yaml
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o}

hms-core:
  provider: ${HMS_CORE_PROVIDER:openai}   # or: anthropic

  # Must match the real context window of the model in use.
  # Too small → premature compaction (wasted summarisation cost, lost context).
  # Too large → compaction arrives too late and the upstream rejects the request.
  context-window: 200000

  # Reserved for model output and the compaction summary itself.
  # Must be substantially smaller than the window.
  reserved-tokens: 20000
```

**3 — Inject and converse**

```java
@RestController
public class AgentController {

    private final HmsSessionManager sessions;

    AgentController(HmsSessionManager sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/chat")
    public String chat(@RequestBody String message) {
        String sessionId = sessions.createSession();
        try {
            return sessions.send(sessionId, message).content();
        } finally {
            sessions.destroySession(sessionId);
        }
    }
}
```

> **Try it first** — the repository ships a reference application with a web UI and
> SSE streaming. Run `mvn spring-boot:run` in `demo-app/` and open
> `http://localhost:8088`. Type `/` in the composer for slash commands.
>
> **Verify the surface** — with the application running, execute
> `node demo-app/api-test.mjs` (Node 20+, zero dependencies). It exercises every
> capability reachable over HTTP and reports explicit skips for those that are not.

### Capabilities

#### Agent engine

- **Multi-iteration loop** — blocking and streaming modes share one control path;
  tool results are appended and fed back automatically until the model settles.
- **Layered context compaction** — three escalating strategies: micro-compaction
  (local truncation, no API cost) → session-memory summarisation → full summarisation
  as a fallback. Triggered at 93 % of the effective window, with circuit-breaker
  protection after repeated failures.
- **Manual compaction** — `compactNow(sessionId)` bypasses both threshold and
  circuit breaker, returning a structured `CompactionResult`.
- **Token accounting** — input, output and cache tokens tracked per session, with
  live context-window utilisation and cost estimation.
- **Extended thinking** — reasoning-model thinking blocks are surfaced separately
  from the answer rather than being discarded.
- **Runtime activity state** — six states (`CALLING_MODEL`, `THINKING`, `RESPONDING`,
  `USING_TOOL`, `WAITING_USER`, `IDLE`) reported as they change, so a UI can show what
  the agent is doing rather than an undifferentiated spinner. Orthogonal to the session
  lifecycle state.

#### Built-in tools

| Domain | Tools |
|---|---|
| Web | `WebFetch` · `WebSearch` |
| Orchestration | `Agent` (sub-agents) · `SendMessage` |
| Task management | `TaskCreate` · `TaskGet` · `TaskList` · `TaskUpdate` · `TaskStop` · `TaskOutput` |
| Productivity | `TodoWrite` · `Sleep` · `ToolSearch` |
| Interaction | `AskUserQuestion` · `EnterPlanMode` · `ExitPlanMode` |
| Configuration | `Config` · `Skill` |
| MCP | `ListMcpResources` · `ReadMcpResource` |

Custom tools implement the same contract and are discovered automatically.

#### Security and permissions

- **Five graduated modes** — `STRICT` · `SAFE` · `DEFAULT` · `TRUSTED` · `BYPASS`,
  each admitting a different maximum risk level.
- **Layered evaluation chain** — mode check → tool-context override → risk
  classification → rule matching → risk detection → user confirmation. Explicit
  `DENY` rules outrank even `BYPASS` mode.
- **Pluggable risk detection** — implement `RiskDetector` for domain-specific checks.
- **Denial tracking** — repeated refusals de-escalate automatically, preventing an
  agent from probing the same boundary indefinitely.

#### Integration

- **MCP protocol** — connect external MCP servers over StdIO or HTTP SSE; their
  tools register into the registry automatically.
- **Hooks** — `PRE_TOOL_USE` can block execution or rewrite arguments;
  `POST_TOOL_USE` can rewrite results.
- **Streaming transport** — `HmsSseBridge` provides SSE out of the box, including
  interactive questions and permission prompts. Other transports need only an
  event sink.
- **Metrics** — message counts, tool usage, API calls, token consumption and error
  taxonomy per session.

### Request lifecycle

```
HmsSessionManager.send(sessionId, message)
│
├─ session state check + input validation
│
└─ AgentLoop
   │
   ├─ append UserMessage
   │
   ├─ while iteration < max-iterations (default 50):
   │  │
   │  ├─ ChatModel.call(prompt)
   │  │
   │  ├─ token usage recorded
   │  │
   │  ├─ tool calls present?
   │  │  │
   │  │  ├─ YES → hooks + permission chain
   │  │  │        → execute tools, append results
   │  │  │        → compaction check
   │  │  │        → next iteration
   │  │  │
   │  │  └─ NO  → compaction check
   │  │           → exit loop
   │
   └─ onComplete(response)
```

The compaction check appears on **both** exits: a chat-only session that never calls
a tool must still be able to compact, or its context grows until the upstream
rejects the request.

### Configuration reference

| Property | Default | Purpose |
|---|---|---|
| `hms-core.provider` | `openai` | Model provider: `openai` or `anthropic` |
| `hms-core.context-window` | `200000` | Real context window of the model in use |
| `hms-core.reserved-tokens` | `20000` | Held back for output and summaries |
| `hms-core.max-iterations` | `50` | Per-turn loop ceiling; exceeding it truncates the answer with a warning marker |
| `hms-core.user-response-timeout-seconds` | `300` | Wait limit for `AskUser` and permission confirmation |
| `hms-core.session.max-sessions` | `1000` | Concurrent session ceiling |
| `hms-core.session.idle-timeout-minutes` | `30` | Idle reclamation threshold |
| `hms-core.session.cleanup-interval-minutes` | `5` | Sweep interval |
| `hms-core.i18n.enabled` | `true` | Translate built-in Chinese prompts to the system locale at startup. Translation is synchronous and can add tens of seconds to boot — set `false` to keep the Chinese originals |
| `hms-core.tls.insecure` | `false` | Skip TLS certificate and hostname verification. Debugging only — it removes man-in-the-middle protection |

### Requirements

- Java 25 or later
- Spring Boot 4.1.1 (Spring AI 2.0.1)
- An API key for an Anthropic or OpenAI-compatible endpoint

---

<a name="中文"></a>

## 中文

### 概述

HMS Core 把自主 Agent 范式带入 Spring 应用：显式的多轮推理循环、带分级权限管控的
工具调用、会话隔离、分层上下文压缩 —— 全部收敛在一个依赖与 Spring Boot 自动装配之后。

它面向的是需要在既有 JVM 服务内获得 Agent 能力的团队，而不是再维护一个独立的
Python 旁挂进程。

```java
String sessionId = sessionManager.createSession();
HmsResponse response = sessionManager.send(sessionId, "总结今天的告警");
```

这就是全部集成面。多轮上下文、工具分发、权限校验与 token 记账都已就位。

### 设计原则

| 原则 | 落到实处的含义 |
|---|---|
| **零仪式集成** | 一个 JAR，Bean 自动装配，无需编写任何配置类 |
| **控制流显式可读** | Agent 循环是普通 Java 代码，可阅读、可下断点、可推理 —— 而非隐藏在框架回调图里 |
| **不绑定厂商** | 经 Spring AI 对接 Anthropic 与 OpenAI 兼容端点，厂商专有类型不会渗入业务代码 |
| **默认即安全** | 工具带风险分级，默认权限模式在未获明确许可时拒绝破坏性操作 |
| **可观测** | token 用量、工具调用、API 调用、压缩事件与错误分类均对外暴露 |
| **处处可扩展** | 自定义工具、Hook、权限规则、风险检测器都以普通 Spring Bean 注册 |

### 快速开始

**① 声明依赖**

```xml
<dependency>
    <groupId>com.inspirationi</groupId>
    <artifactId>hms-core</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```

**② 配置模型供应商**

```yaml
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o}

hms-core:
  provider: ${HMS_CORE_PROVIDER:openai}   # 或 anthropic

  # 必须与所用模型的真实上下文窗口一致。
  # 配小了会过早压缩：白花摘要费用，还丢上下文。
  # 配大了压缩来不及：请求超限被上游直接拒绝。
  context-window: 200000

  # 留给模型输出与压缩摘要本身，必须显著小于窗口。
  reserved-tokens: 20000
```

**③ 注入并对话**

```java
@RestController
public class AgentController {

    private final HmsSessionManager sessions;

    AgentController(HmsSessionManager sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/chat")
    public String chat(@RequestBody String message) {
        String sessionId = sessions.createSession();
        try {
            return sessions.send(sessionId, message).content();
        } finally {
            sessions.destroySession(sessionId);
        }
    }
}
```

> **先体验** —— 仓库自带参考应用，含 Web 界面与 SSE 流式输出。在 `demo-app/`
> 目录执行 `mvn spring-boot:run`，访问 `http://localhost:8088`。输入框键入 `/`
> 可查看 slash 命令。
>
> **验证接口** —— 应用启动后执行 `node demo-app/api-test.mjs`（Node 20+，零依赖）。
> 它会逐项验证所有可经 HTTP 触达的能力，对无法验证的项显式标注跳过而非静默略过。

### 能力清单

#### Agent 引擎

- **多轮循环** —— 阻塞与流式共用同一条控制路径；工具结果自动入历史并回传，
  直至模型收敛。
- **分层上下文压缩** —— 三级递进策略：微压缩（本地截断，零 API 开销）→
  Session Memory 摘要 → 全量摘要兜底。在有效窗口 93 % 处触发，连续失败后熔断保护。
- **手动压缩** —— `compactNow(sessionId)` 绕过阈值与熔断，直接返回结构化的
  `CompactionResult`。
- **Token 记账** —— 按会话追踪输入、输出与缓存 token，实时给出上下文占用率与费用估算。
- **Extended Thinking** —— 推理模型的思考内容与最终回答分离呈现，不被丢弃。
- **运行时活动状态** —— 六态（思考中 / 深度思考中 / 回复中 / 调用工具 / 待确认 / 空闲）
  随发生实时上报，界面能显示 Agent 此刻在做什么，而不是一个笼统的转圈动画。
  与会话生命周期状态正交。

#### 内置工具

| 领域 | 工具 |
|---|---|
| Web | `WebFetch` · `WebSearch` |
| 编排 | `Agent`（子 Agent）· `SendMessage` |
| 任务管理 | `TaskCreate` · `TaskGet` · `TaskList` · `TaskUpdate` · `TaskStop` · `TaskOutput` |
| 效率 | `TodoWrite` · `Sleep` · `ToolSearch` |
| 交互 | `AskUserQuestion` · `EnterPlanMode` · `ExitPlanMode` |
| 配置 | `Config` · `Skill` |
| MCP | `ListMcpResources` · `ReadMcpResource` |

自定义工具遵循同一套契约，自动被发现并注册。

#### 安全与权限

- **五级权限模式** —— `STRICT` · `SAFE` · `DEFAULT` · `TRUSTED` · `BYPASS`，
  各自放行不同的最高风险等级。
- **分层评估链** —— 模式检查 → ToolContext 覆盖 → 风险分级 → 规则匹配 →
  风险检测 → 用户确认。显式 `DENY` 规则的优先级高于 `BYPASS` 模式。
- **可插拔风险检测** —— 实现 `RiskDetector` 接口注入领域特定的安全检查。
- **拒绝追踪** —— 连续拒绝自动降级，避免 Agent 反复试探同一条边界。

#### 集成能力

- **MCP 协议** —— 经 StdIO 或 HTTP SSE 连接外部 MCP 服务器，其工具自动注册进注册中心。
- **Hook 系统** —— `PRE_TOOL_USE` 可阻止执行或改写入参，`POST_TOOL_USE` 可改写结果。
- **流式传输** —— `HmsSseBridge` 开箱提供 SSE，含交互式提问与权限弹窗；
  换用其他传输方式只需实现一个事件 sink。
- **指标采集** —— 按会话统计消息数、工具使用、API 调用、token 消耗与错误分类。

### 请求生命周期

```
HmsSessionManager.send(sessionId, message)
│
├─ 会话状态检查 + 输入校验
│
└─ AgentLoop
   │
   ├─ 追加 UserMessage
   │
   ├─ while 迭代 < max-iterations（默认 50）:
   │  │
   │  ├─ ChatModel.call(prompt)
   │  │
   │  ├─ 记录 token 用量
   │  │
   │  ├─ 是否有工具调用？
   │  │  │
   │  │  ├─ 有 → Hook + 权限评估链
   │  │  │      → 执行工具，结果入历史
   │  │  │      → 压缩检查
   │  │  │      → 进入下一轮
   │  │  │
   │  │  └─ 无 → 压缩检查
   │  │         → 退出循环
   │
   └─ onComplete(response)
```

压缩检查出现在**两条**出路上：只聊天、从不调用工具的会话同样必须能压缩，
否则上下文会一路增长到被上游拒绝。

### 配置参考

| 配置项 | 默认值 | 用途 |
|---|---|---|
| `hms-core.provider` | `openai` | 模型供应商：`openai` 或 `anthropic` |
| `hms-core.context-window` | `200000` | 所用模型的真实上下文窗口 |
| `hms-core.reserved-tokens` | `20000` | 预留给输出与摘要 |
| `hms-core.max-iterations` | `50` | 单轮循环上限；触顶会截断回答并追加警告标记 |
| `hms-core.user-response-timeout-seconds` | `300` | 等待 `AskUser` 与权限确认的上限 |
| `hms-core.session.max-sessions` | `1000` | 并发会话上限 |
| `hms-core.session.idle-timeout-minutes` | `30` | 空闲回收阈值 |
| `hms-core.session.cleanup-interval-minutes` | `5` | 清理扫描间隔 |
| `hms-core.i18n.enabled` | `true` | 启动时把内置中文提示词翻译为系统语言。翻译是同步进行的，可能使启动耗时增加数十秒 —— 设为 `false` 则保留中文原文 |
| `hms-core.tls.insecure` | `false` | 跳过 TLS 证书与主机名校验。仅用于调试 —— 开启后失去中间人攻击防护 |

### 环境要求

- Java 25 及以上
- Spring Boot 4.1.1（Spring AI 2.0.1）
- Anthropic 或 OpenAI 兼容端点的 API 密钥

---

<div align="center">

## License

Licensed under the [Apache License 2.0](LICENSE).

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

</div>
