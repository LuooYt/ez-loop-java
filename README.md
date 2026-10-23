<div align="center">

# HMS Core

**Production-grade AI Agent SDK for the JVM**

面向 JVM 的生产级 AI Agent SDK

[![Version](https://img.shields.io/badge/version-1.0.0-blue?style=flat-square)](https://github.com/)
[![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-6DB33F?style=flat-square)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](LICENSE)

**[English](#english) · [中文](#中文)**

</div>

---

<a name="english"></a>

## English

### Live demo

**http://demo.qdyda.cn** — the `demo-app` web console running on a 4B local model
(Ollama, OpenAI-compatible API). Open the page, create a session and chat; the agent
can call file tools inside its own session sandbox.

> ⚠️ **The demo has no authentication — it is for local demonstration only.** Anyone
> who can reach the port can drive the agent, read session content and change
> permission rules. Do not expose it to the internet without putting an authenticated
> reverse proxy in front of it. Defaults reflect this: the app binds to `127.0.0.1`,
> CORS only trusts localhost, and switching to `BYPASS` is refused unless
> `hms.demo.allow-bypass=true` is set explicitly.

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
    <version>1.0.0</version>
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

  # Optional — override the built-in rate card (USD per million tokens).
  # Keys are case-insensitive substrings of the model name; longer patterns win.
  pricing:
    models:
      opus:
        input: 15.0
        output: 75.0
        cache-read: 1.5
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
- **Token accounting** — input, output and cache read/write tracked per session,
  with live context-window utilisation.
- **Overridable pricing** — cost calculation is an extension point (`TokenPricing`),
  not hard-wired logic: override the built-in rate card via `hms-core.pricing.*`
  without waiting for an SDK release, or inject your own billing system. Unknown
  models yield an empty result rather than a guess — an explicit "don't know" beats
  a plausible wrong number.
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
| File system | `Read` · `Write` · `Edit` · `Glob` · `Grep` · `ListFiles` · `NotebookEdit` |
| Execution | `Bash` |
| IDE | `LSP` |
| Git | `EnterWorktree` · `ExitWorktree` |
| Notifications | `Notification` |

File-system, execution, IDE and Git tools are registered only when the session
sandbox is enabled; `Notification` is always registered.

Custom tools implement the same contract and are discovered automatically.

#### Session sandbox

Every session gets its own directory under `hms-core.sandbox.root`; all file
operations are confined to it.

```
<sandbox.root>/
└── <sessionId>/            created empty when the session starts
    ├── worktrees/<slug>/   created by EnterWorktree (the sandbox root must be a git repo)
    └── ...
```

| Property | Default | Purpose |
|---|---|---|
| `hms-core.sandbox.enabled` | `false` | Master switch. When off, no path-based tool is registered |
| `hms-core.sandbox.root` | — | Sandbox root directory; one child directory per session |
| `hms-core.sandbox.cleanup-on-destroy` | `false` | Delete the session directory on destroy/idle reclamation. Default keeps the artefacts |
| `hms-core.sandbox.max-file-bytes` | `10485760` | Per-file read/write ceiling |
| `hms-core.sandbox.bash.timeout-seconds` | `120` | Default command timeout (a request may only lower it) |
| `hms-core.sandbox.bash.max-output-chars` | `40000` | Output truncation limit |
| `hms-core.sandbox.lsp.servers.<name>` | `{}` | Language servers (`command`, `args`, `extensions`, `env`). `LSP` is registered only when at least one is configured |
| `hms-core.sandbox.notification.system-command` | `true` | Fall back to OS notifications when no `NotificationSink` bean is injected |

Risk levels: `Read`/`Glob`/`Grep`/`ListFiles`/`LSP`/`Notification` are `READ_ONLY`,
`Write`/`Edit`/`NotebookEdit`/`ExitWorktree` are `MEDIUM`, `Bash`/`EnterWorktree`
are `HIGH`.

Two things to keep in mind:

- **`Bash` is a mitigation, not a security boundary.** It runs in the session
  directory with destructive patterns blocked, but absolute paths and `cd` can
  still reach the rest of the file system. Run the process under an OS-level
  account or container if you need isolation.
- **The sandbox starts empty.** Nothing from the host project is copied in — put
  the files the agent needs into the session directory yourself.
- `LSP` requires the language server (`jdtls`, `typescript-language-server`, …) to
  be installed on the host and reachable on `PATH`.

#### Security and permissions

- **Five graduated modes** — `STRICT` · `SAFE` · `DEFAULT` · `TRUSTED` · `BYPASS`,
  each admitting a different maximum risk level.
- **Layered evaluation chain** — explicit `DENY` rules → `BYPASS` / `STRICT` mode
  → risk-level auto-allow → `ALLOW` rules → risk detection → user confirmation.
  `DENY` outranks everything, including `BYPASS`: bypassing skips confirmations,
  it does not cancel the rules you explicitly denied.
- **Narrow confirmations** — "always allow" records the exact command you approved,
  not its whole family. For sensitive commands (`rm`, `chmod`, `sudo`, `git`, …)
  the rule is kept for the current session only and never persisted.
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
| `hms-core.pricing.models.<pattern>.{input,output,cache-read}` | built-in rate card | USD per million tokens. `<pattern>` is a case-insensitive **substring** of the model name; longer patterns win. All three keys are required — an incomplete entry is discarded (never treated as free) |
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

### Documentation

| Document | Contents |
|---|---|
| [SDK reference](hms-core/README.md) | Full API guide, architecture, configuration reference, changelog |
| [Reference application](demo-app/README.md) | Web console, SSE contract, slash commands, test suites |

---

<a name="中文"></a>

## 中文

### 在线演示

**http://demo.qdyda.cn** —— `demo-app` Web 控制台，后端接本地 4B 模型
（Ollama，OpenAI 兼容 API）。打开页面新建会话即可对话，Agent 可在会话沙箱内
调用文件类工具。

> ⚠️ **demo 无鉴权，仅限本机演示，切勿直接公网部署。** 任何能连上该端口的人都可以
> 驱动 Agent、读取会话内容、增删权限规则。必须外网访问时，请在前面套一层带鉴权的
> 反向代理。默认配置据此收敛：只监听 `127.0.0.1`、CORS 只信任本机、切到 `BYPASS`
> 会被拒绝（除非显式设置 `hms.demo.allow-bypass=true`）。

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
    <version>1.0.0</version>
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

  # 可选 —— 覆盖内置价目表（每百万 token 美元价）。
  # 键是模型名的子串、大小写不敏感、长模式优先；三项须都填，缺项则整条作废。
  pricing:
    models:
      opus:
        input: 15.0
        output: 75.0
        cache-read: 1.5
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
- **Token 记账** —— 按会话追踪输入、输出与缓存读写四类 token，实时给出上下文占用率。
- **可覆写的计费** —— 费用计算是扩展点（`TokenPricing`），不是写死的逻辑：内置价目表可经
  `hms-core.pricing.*` 改价，无需等 SDK 发版；也可注入自己的计费系统（查库、按租户区分费率）。
  定价未知时返回空而非猜一个数 —— 一个显式的「不知道」远比可信的错数有用。
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
| 文件 | `Read` · `Write` · `Edit` · `Glob` · `Grep` · `ListFiles` · `NotebookEdit` |
| 执行 | `Bash` |
| IDE | `LSP` |
| Git | `EnterWorktree` · `ExitWorktree` |
| 通知 | `Notification` |

文件、执行、IDE 与 Git 类工具仅在会话沙箱启用时注册；`Notification` 始终注册。

自定义工具遵循同一套契约，自动被发现并注册。

#### 会话沙箱

每个会话在 `hms-core.sandbox.root` 下拥有独立目录，所有文件操作都被限制在其中。

```
<sandbox.root>/
└── <sessionId>/            会话创建时新建，初始为空
    ├── worktrees/<slug>/   EnterWorktree 创建的工作树（沙箱根须为 git 仓库）
    └── ...
```

| 配置项 | 默认值 | 用途 |
|---|---|---|
| `hms-core.sandbox.enabled` | `false` | 总开关。关闭时不注册任何路径类工具 |
| `hms-core.sandbox.root` | — | 沙箱根目录；每个会话在其下拥有独立子目录 |
| `hms-core.sandbox.cleanup-on-destroy` | `false` | 会话销毁/空闲回收时删除会话目录。默认保留产出 |
| `hms-core.sandbox.max-file-bytes` | `10485760` | 单文件读写上限 |
| `hms-core.sandbox.bash.timeout-seconds` | `120` | 命令默认超时（请求只能调低） |
| `hms-core.sandbox.bash.max-output-chars` | `40000` | 输出截断上限 |
| `hms-core.sandbox.lsp.servers.<name>` | `{}` | 语言服务器（`command`、`args`、`extensions`、`env`）。至少配置一个才注册 `LSP` |
| `hms-core.sandbox.notification.system-command` | `true` | 未注入 `NotificationSink` 时回退到系统通知 |

风险等级：`Read`/`Glob`/`Grep`/`ListFiles`/`LSP`/`Notification` 为 `READ_ONLY`，
`Write`/`Edit`/`NotebookEdit`/`ExitWorktree` 为 `MEDIUM`，`Bash`/`EnterWorktree`
为 `HIGH`。

两点必须知道：

- **`Bash` 是缓解措施，不是安全边界。** 它在会话目录中执行、拦截危险命令，但
  绝对路径与 `cd` 仍可触达文件系统其他位置。需要真隔离时，请用独立的系统账号
  或容器运行进程。
- **沙箱初始为空。** 宿主项目不会被复制进去 —— 需要 Agent 处理的文件请自行放入
  会话目录。
- `LSP` 需要宿主上安装对应语言服务器（`jdtls`、`typescript-language-server` 等）
  并在 `PATH` 中可达。

#### 安全与权限

- **五级权限模式** —— `STRICT` · `SAFE` · `DEFAULT` · `TRUSTED` · `BYPASS`，
  各自放行不同的最高风险等级。
- **分层评估链** —— 显式 `DENY` 规则 → `BYPASS` / `STRICT` 模式 → 风险等级自动放行 →
  `ALLOW` 规则 → 风险检测 → 用户确认。`DENY` 压过一切，包括 `BYPASS`：
  旁路跳过的是逐次确认，不是取消你设过的拒绝规则。
- **收窄的确认范围** —— 「始终允许」只记录你当时确认的那一条命令，不扩成整个命令族。
  敏感命令（`rm`、`chmod`、`sudo`、`git` 等）的规则仅在本会话内生效，不跨会话保留。
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

### 文档索引

| 文档 | 内容 |
|---|---|
| [SDK 参考手册](hms-core/README.zh-CN.md) | 完整 API 手册、架构设计、配置参考、版本历史 |
| [参考应用说明](demo-app/README.zh-CN.md) | Web 控制台、SSE 契约、slash 命令、测试套件 |

---

<div align="center">

## License

Licensed under the [Apache License 2.0](LICENSE).

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

</div>
