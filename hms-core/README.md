<div align="center">

# HMS Core

**Embeddable AI Agent SDK for the JVM**

[![Version](https://img.shields.io/badge/version-1.0.0-blue?style=flat-square)](https://github.com/)
[![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-6DB33F?style=flat-square)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](../LICENSE)

**English** · [中文](README.zh-CN.md)

</div>

---

## Overview

HMS Core embeds the autonomous-agent paradigm into Spring Boot applications.
`HmsSessionManager` is the single public entry point: isolated multi-session state,
streaming output, tool orchestration, graduated permission control and layered
context compaction — all behind one dependency and Spring Boot auto-configuration.

It targets teams that need agent capability *inside* an existing JVM service rather
than as a separate Python sidecar.

## Capabilities

### Agent engine

- **Agent loop** — blocking and streaming modes share one control path; multi-turn
  conversation with automatic tool-result feedback.
- **Token accounting** — four token classes tracked (input, output, cache read,
  cache write) with live context-window utilisation and four-level warning states.
- **Overridable pricing** — `TokenPricing` is an extension point, not hard-wired
  logic: a built-in rate card, YAML overrides, or your own billing system.
- **Layered compaction** — micro → session-memory → full, auto-triggered at 93 % of
  the effective window, with circuit-breaker protection.
- **Extended thinking** — reasoning-model thinking blocks surfaced separately from
  the answer.
- **Runtime activity state** — six states reported as they change, so a UI can show
  what the agent is *doing* rather than an undifferentiated spinner.

### Tooling

- **20 built-in tools** — web fetch/search, six task-management tools, sub-agents,
  MCP bridge, skill invocation, plan mode.
- **Task management** — background task create/query/update/stop, automatic and
  manual execution modes.
- **MCP protocol** — Model Context Protocol client over StdIO and HTTP SSE, with
  tool discovery and resource reads.
- **Extensible** — custom tools, hooks, permission rules and risk detectors all
  register as ordinary Spring beans.

### Security and permissions

- **Five graduated modes** — `STRICT` / `SAFE` / `DEFAULT` / `TRUSTED` / `BYPASS`.
- **Eight-step evaluation chain** — mode check → tool-context override → risk
  classification → rule matching → risk detection → user confirmation.
- **Pluggable risk detection** — implement `RiskDetector` for domain-specific checks.
- **Three-tier rule scope** — project > user > session, held in memory.
- **Denial tracking** — three consecutive or twenty cumulative refusals trigger
  automatic de-escalation.

### Sessions and integration

- **Session isolation** — every session owns its `AgentLoop`, message history, tool
  registry and permission settings.
- **Rich callbacks** — `onToken` / `onToolUse` / `onThinking` / `onActivity` /
  `onAskUser` / `onPermissionRequest` / `onError`.
- **Lifecycle management** — create / pause / resume / destroy, with idle reclamation.
- **Metrics** — tool usage, API call counts, token consumption.
- **Batteries-included bridge** — `HmsEvent` + `EventBridgeCallbacks` +
  `HmsSseBridge`: SSE integration in one line, no hand-written event serialisation
  or future suspension.

## Technology stack

| Component | Version | Role |
|---|---|---|
| JDK | 25 | Runtime (no preview features; `--enable-preview` not required) |
| Spring Boot | 4.1.1 | Application framework and auto-configuration |
| Spring AI | 2.0.1 | Model invocation (Anthropic + OpenAI) |
| Jackson | managed by Spring Boot | JSON serialisation |

## Getting started

### Prerequisites

- **JDK 25** (`JAVA_HOME` configured)
- **Maven 3.9+**
- **An API key** for Anthropic or an OpenAI-compatible service

### 1 — Declare the dependency

```xml
<dependency>
    <groupId>com.inspirationi</groupId>
    <artifactId>hms-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

> HMS Core targets Spring Boot 4.1.1 and Spring AI 2.0.1 (both GA). Your project must
> use the same Spring Boot major version — Spring AI 2.0.x requires Spring Boot 4.x /
> Framework 7.x and is not compatible with 3.x.

### 2 — Configure credentials

```bash
# Anthropic native API
export AI_API_KEY="sk-ant-xxx"
export HMS_CORE_PROVIDER="anthropic"

# OpenAI-compatible API
export AI_API_KEY="sk-xxx"
export HMS_CORE_PROVIDER="openai"
```

Optional:

```bash
export AI_BASE_URL="https://api.deepseek.com"
export AI_MODEL="deepseek-chat"
export HMS_CORE_CONTEXT_WINDOW=200000
```

### 3 — Bootstrap

```java
@SpringBootApplication(scanBasePackages = {"com.inspirationi.loop", "com.yourcompany"})
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### 4 — Converse

```java
@RestController
public class AiController {

    private final HmsSessionManager sessionManager;
    private final HmsSseBridge sseBridge;

    AiController(HmsSessionManager sessionManager, HmsSseBridge sseBridge) {
        this.sessionManager = sessionManager;
        this.sseBridge = sseBridge;
    }

    // Synchronous
    @PostMapping("/chat")
    public HmsResponse chat(@RequestBody String message) {
        String sid = sessionManager.createSession();
        try {
            return sessionManager.send(sid, message);
        } finally {
            sessionManager.destroySession(sid);
        }
    }

    // Streaming (SSE) — one line via the built-in bridge
    @GetMapping(value = "/chat/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String sessionId, @RequestParam String message) {
        return sseBridge.stream(sessionId, message);
    }
}
```

> `HmsSseBridge` already encapsulates emitter lifecycle, event JSON serialisation,
> virtual-thread scheduling and asynchronous waiting for user responses. See
> [Web integration](#web-integration-sse).

## API guide

### HmsSessionManager — the single entry point

#### Session lifecycle

```java
String sessionId = sessionManager.createSession();
String sessionId = sessionManager.createSession("You are a Java backend expert.");

sessionManager.pauseSession(sessionId);
sessionManager.resumeSession(sessionId);
boolean paused = sessionManager.isPaused(sessionId);

sessionManager.destroySession(sessionId);   // releases all resources

boolean exists = sessionManager.sessionExists(sessionId);
SessionInfo info = sessionManager.getSessionInfo(sessionId);
List<SessionInfo> all = sessionManager.listSessions();
int count = sessionManager.getActiveSessionCount();
```

#### Sending messages

```java
// Synchronous
HmsResponse response = sessionManager.send(sessionId, "Analyse the project structure");

// Streaming
sessionManager.sendStreaming(sessionId, "List all Java files", System.out::print);

// With full callbacks
HmsCallbacks callbacks = new HmsCallbacks() {
    @Override public void onToken(String token) {
        // Every output token, in real time
    }
    @Override public void onToolUse(String toolName, String phase, String input, String result) {
        // Fires several times per invocation: START → PROGRESS* → END.
        // Count usage only on "END" — counting each event inflates it several-fold.
    }
    @Override public void onThinking(String thinking) {
        // Reasoning content (Anthropic extended thinking)
    }
    @Override public void onActivity(SessionActivity activity, String detail) {
        // Runtime state transitions (fires only on actual change):
        // CALLING_MODEL / THINKING / RESPONDING / USING_TOOL / WAITING_USER / IDLE
        // `detail` carries supplementary info, e.g. the tool name for USING_TOOL
    }
    @Override public String onAskUser(String question, List<String> options) {
        return myUi.askUser(question, options);
    }
    @Override public String onPermissionRequest(String toolName, String description) {
        return myUi.confirm(toolName + ": " + description) ? "allow" : "deny";
    }
    @Override public void onComplete(HmsResponse response) { }
    @Override public String onError(Throwable error) {
        return "abort";   // or "retry"
    }
};
sessionManager.send(sessionId, "Refactor this code", callbacks);
```

> **Synchronous vs. asynchronous callback precedence.** The library calls the
> synchronous variant first (`onAskUser` / `onPermissionRequest`); if it returns a
> definite answer the asynchronous variant is **never reached**. Web integrations
> that override only the `*Async` methods must therefore not return a value from the
> synchronous ones — the default implementation already returns `null` (abstain) so
> the asynchronous path stays reachable.
>
> The wait limit is governed by `hms-core.user-response-timeout-seconds` (default
> 300). On timeout: questions resolve to `skip`, permissions to `deny`.

### Web integration (SSE)

Web applications need not hand-write an anonymous `HmsCallbacks`. Three layers ship
ready to use:

| Class | Package | Depends on | Responsibility |
|---|---|---|---|
| `HmsEvent` | `api` | — | Transport-neutral sealed event model, Jackson-serialisable |
| `PendingResponses` | `api` | — | Suspended-request registry (future + timeout fallback) |
| `EventBridgeCallbacks` | `api` | — | `HmsCallbacks` → `Consumer<HmsEvent>` |
| `HmsSseBridge` | `web` | spring-webmvc | SSE façade (emitter lifecycle + serialisation + scheduling) |

#### Minimal usage

```java
// ① Streaming conversation
@GetMapping(value = "/chat/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@PathVariable String sessionId, @RequestParam String message) {
    return sseBridge.stream(sessionId, message);
}

// ② Client submits an answer / permission decision
sseBridge.submitAskResponse(sessionId, "the user's answer");
sseBridge.submitPermissionResponse(sessionId, "allow");   // "allow" / "deny"

// ③ Lifecycle
sseBridge.release(sessionId);        // destroy: release the SSE connection and pending requests
sseBridge.cancelPending(sessionId);  // cancel execution only, keep the connection
```

#### Event contract

`HmsEvent` record component names *are* the public JSON field names; `eventName()`
becomes the SSE `event:` field.

| `eventName()` | Fields |
|---|---|
| `token` | `token` |
| `tool_use` | `toolName`, `phase` (`START` / `PROGRESS` / `END`), `input`, `result` (truncated beyond 5 000 chars) |
| `thinking` | `thinking` (truncated beyond 2 000 chars) |
| `activity` | `activity` (state enum name), `label` (display text), `detail` (e.g. tool name, nullable) |
| `ask_user` | `question`, `options` (`null` normalised to `[]`) |
| `permission` | `toolName`, `description` |
| `compaction` | `layer`, `messagesBefore`, `messagesAfter`, `reason` |
| `complete` | `content`, `totalTokens`, `toolCallsCount`, `interrupted` |
| `error` | `message`, `code` (numeric `HmsErrorCode`) |

Two conventions consumers must know:

- **`tool_use` is pushed multiple times per invocation** — branch on `phase`:
  `START` (`result` is null) → zero or more `PROGRESS` → `END` (final result).
  Treating each as a separate invocation inflates usage counts several-fold.
- **`activity`'s terminal `IDLE` usually never arrives** — the SSE connection closes
  right after `complete`, leaving no receiver. Treat `complete` / `error` themselves
  as the "back to idle" signal.

#### Other transports (WebSocket, message queues)

Reuse the three web-free classes and write only a sink:

```java
PendingResponses pending = new PendingResponses(300);
HmsCallbacks callbacks = new EventBridgeCallbacks(
        event -> myTransport.push(event.eventName(), objectMapper.writeValueAsString(event)),
        pending, sessionId);
sessionManager.send(sessionId, message, callbacks);

// Delivered from another thread when the user responds
pending.submitAskUser(sessionId, answer);
pending.submitPermission(sessionId, "allow");
```

> `spring-webmvc` is declared `optional` in hms-core: non-web consumers are not
> dragged into the servlet stack. `HmsSseBridge` is then silently skipped by
> `@ConditionalOnClass(SseEmitter.class)`, while `HmsEvent` / `PendingResponses` /
> `EventBridgeCallbacks` remain fully usable.

#### Session control

```java
sessionManager.cancel(sessionId);

TokenStats stats = sessionManager.getSessionTokenStats(sessionId);
stats.inputTokens();          // plain input (excludes cache reads)
stats.outputTokens();
stats.cacheReadTokens();      // cache reads — roughly 1/10 the unit price of input
stats.cacheCreationTokens();
stats.cost();                 // BigDecimal; null means pricing unknown for this model
stats.pricingModel();         // the model name used for the calculation

// `null` cost and "zero cost" are different things and must be handled separately
stats.costIfKnown().ifPresentOrElse(
        c -> System.out.printf("Cost $%s (per %s)%n", c, stats.pricingModel()),
        () -> System.out.println("Pricing unknown — configure hms-core.pricing.* or inject TokenPricing"));
```

> **A `null` cost means "pricing unknown", not zero.** Conflating them turns
> "no rate card configured" into "nothing was spent". See [Token pricing](#token-pricing).

#### Operations

```java
int cleaned = sessionManager.cleanupIdleSessions(1800);          // reclaim sessions idle > 30 min
MetricsCollector metrics = sessionManager.getSessionMetrics(sessionId);
```

#### Manual compaction

```java
CompactionResult result = sessionManager.compactNow(sessionId);

result.success();          // false when history is too short or summarisation failed
result.layer();            // always CompactLayer.MANUAL for manual triggers
result.messagesBefore();
result.messagesAfter();
result.reason();
```

Unlike automatic compaction it **ignores the token threshold and the circuit
breaker**, going straight to the full-compaction layer. The breaker exists to stop
automatic compaction from burning money during an outage — an explicit user request
does not warrant that protection. Manual failures also do not increment
`consecutiveFailures`, so they never consume the automatic-compaction budget.
**Manual compaction therefore remains available after the breaker has opened**, with
no need to restart the session.

Exception contract:

| Exception | Condition |
|---|---|
| `IllegalArgumentException` | Session does not exist |
| `IllegalStateException` | The session is currently executing a request |

**Why an in-flight request must be rejected:** concurrent compaction can produce a
history containing a `tool_use` with no matching `tool_result`, which the upstream
rejects with 400 — and the damage is **persistent**, because the history has already
been replaced, so every subsequent turn hits the same 400 with the same broken
history. This is a correctness requirement, not caution. A `PAUSED` session may be
compacted; "pause → compact → resume" is an intended workflow.

> Manual compaction **returns its result synchronously and emits no SSE `compaction`
> event**. The compaction callback is request-scoped (re-registered each turn by
> `AgentLoop`), and "no request in flight" is precisely the only moment manual
> compaction is permitted — the emitter the callback points at has long since
> completed, so the event would be discarded. Read the return value instead.

### SessionInfo

```java
SessionInfo info = sessionManager.getSessionInfo(sessionId);

info.sessionId();
info.status();          // lifecycle: ACTIVE / PAUSED / DESTROYED
info.activity();        // runtime: IDLE / CALLING_MODEL / THINKING / RESPONDING / USING_TOOL / WAITING_USER
info.sessionPrompt();
info.toolNames();
info.createdAt();
info.lastAccessTime();
info.idleSeconds();
info.inputTokens();
info.outputTokens();
info.cost();            // BigDecimal; null = pricing unknown for this model
info.pricingModel();
info.messageCount();
```

> `status` and `activity` are **orthogonal dimensions**: the former governs whether
> messages can be accepted, the latter what is happening right now. An `ACTIVE`
> session may be idle, calling the model or executing a tool; a `PAUSED` session is
> necessarily `IDLE`, but not the converse.
>
> `activity` is read live from `AgentLoop.getActivity()`, and every request
> necessarily ends at `IDLE` — normal completion, exception, user cancellation and
> hitting the iteration ceiling are all converged by a `finally` in `executeLoop`.
> `AgentLoop` is a session-scoped, long-lived object; a missed reset would make every
> later query on that session return a stale state.

### PromptManager

```java
promptManager.updateGlobalPrompt("You are a security auditor. Review code thoroughly.");
promptManager.updateSessionPrompt(sessionId, "This task focuses on performance.");

String global = promptManager.getGlobalPrompt();
String session = promptManager.getSessionPrompt(sessionId);
```

HMS Core maintains two prompt tiers: the **global prompt** applies to all sessions,
the **session prompt** to one. The system prompt sent to the model is their
concatenation.

> There are two entry points for updating a session prompt, with different semantics.
> `HmsSessionManager.updateSessionPrompt(...)` **also refreshes that session's
> `AgentLoop` system prompt** (replacing `messageHistory[0]` while preserving the
> conversation); `PromptManager.updateSessionPrompt(...)` only updates storage. Use
> the former to affect an in-flight session immediately.
>
> Both tiers are **in-memory state** and fall back to configured or built-in defaults
> after a restart.

### ToolManager

```java
toolManager.addSessionTool(sessionId, new MyCustomTool());
List<String> tools = toolManager.getSessionToolNames(sessionId);
toolManager.removeSessionTool(sessionId, "WebSearch");
```

### Permissions

| Mode | Behaviour | Suited to |
|---|---|---|
| `STRICT` | Read-only operations only | Code analysis, architecture review |
| `SAFE` | Auto-allows READ_ONLY + LOW | Customer service and other light-touch scenarios |
| `DEFAULT` | Auto-allows READ_ONLY + LOW + MEDIUM; HIGH+ requires confirmation | Everyday interaction (default) |
| `TRUSTED` | Only CRITICAL requires confirmation | Highly trusted internal systems |
| `BYPASS` | Skips all permission checks | Automation scripts (use with care) |

```java
permissionSettings.setCurrentMode(PermissionMode.TRUSTED);

PermissionRule rule = PermissionRule.forCommand("Bash", "git", PermissionBehavior.ALLOW);
permissionSettings.addUserRule(rule);      // persistent
permissionSettings.addSessionRule(rule);   // session-scoped

permissionSettings.removeUserRule("Bash(git:*)");
List<String> rules = permissionSettings.listRules();
permissionSettings.clearAll();
```

### Token pricing

Cost calculation is an **overridable extension point**, not logic baked into the SDK
— prices change and new models appear; hard-coding means every repricing waits for a
release.

#### Option 1 — override the built-in rate card via configuration

The built-in card covers Claude (opus / sonnet / haiku) and OpenAI (gpt-4o /
gpt-4o-mini). Repricing is a YAML change:

```yaml
hms-core:
  pricing:
    models:
      opus:                 # key is a case-insensitive SUBSTRING of the model name
        input: 10.0         # USD per million tokens
        output: 65.0
        cache-read: 1.2
      my-private-llm:       # models outside the built-in card can be added too
        input: 1.0
        output: 2.0
        cache-read: 0.1
```

Three rules:

- **Substring matching** — `opus` matches `us.anthropic.claude-opus-5`. Real model
  names carry gateway prefixes and date suffixes; exact matching would miss almost
  all of them.
- **Longest pattern wins** — `gpt-4o-mini` is not captured by `gpt-4o` (their unit
  prices differ ~16×). Precedence is decided by pattern length, **independent of
  configuration order**.
- **All three fields are required** — omit any one and the whole entry is discarded,
  falling back to the built-in default with a warning at startup. Defaulting a
  missing field to 0 would silently turn an incomplete config into "this component is
  free" and produce a plausible wrong number.

The startup log prints the effective patterns, so you can confirm your config was read:

```
Creating BuiltinModelPricing bean (1 configured overrides, patterns: [my-private-llm, gpt-4o-mini, sonnet, gpt-4o, haiku, opus])
```

#### Option 2 — plug in your own billing system

Declaring a `TokenPricing` bean **takes over completely** (the built-in
implementation backs off):

```java
@Bean
TokenPricing tokenPricing(MyBillingService billing) {
    return (model, usage) -> billing.lookupRate(model)
            .map(rate -> rate.apply(usage));   // Optional.empty() = pricing unknown
}
```

The interface has a single method:

```java
Optional<BigDecimal> cost(String model, TokenUsage usage);
```

Three design choices worth explaining:

| Choice | Rationale |
|---|---|
| Return `Optional` rather than a bare number | Unknown pricing is the normal case (new models, private deployments, compatibility gateways). If "the amount" and "whether it is trustworthy" travel on separate channels, callers will almost certainly read only the former — the previous `isPricingKnown()` was in fact **never read by any code**, so unknown models had a cost silently computed against the Sonnet rate card and presented as real |
| `BigDecimal` rather than `double` | Monetary amounts should not use binary floating point; accumulating across calls compounds error |
| A stateless function rather than session state | Looking up a rate card is pure computation. It previously lived on `TokenTracker` as five mutable fields (three prices + model name + a known flag) with a `setModel` to mutate them — a pure function written as a state machine |

`TokenUsage` carries the four token classes separately: cache reads cost roughly a
tenth of plain input, so folding them into `input` overstates long sessions
several-fold; cache writes are conversely *more* expensive, and folding them in
distorts the figure the other way.

> **The built-in implementation does not bill cache writes**, preserving the
> pre-refactor accounting so the same usage does not yield different amounts across
> the upgrade. This is a known understatement (Anthropic cache writes run about 1.25×
> base input); implement your own `TokenPricing` if you need exact billing.

#### Migration — the pricing API on `TokenTracker` is deprecated

`setModel` / `estimateCost()` / `isPricingKnown()` / `getModelName()` are all
`@Deprecated`. They still work, but migrate:

```java
// Before
double cost = tokenTracker.estimateCost();

// After
Optional<BigDecimal> cost = pricing.cost(model, tokenTracker.usageSnapshot());
```

> **`estimateCost()` has one behavioural change**: an unrecognised model name (or one
> where `setModel` was never called) now returns `0.0`, whereas it previously computed
> a plausible-looking figure from the Claude Sonnet rate card that bore no relation to
> the actual bill. Code relying on the old behaviour should migrate to `TokenPricing`
> and handle `empty` explicitly.

### MCP server integration

```java
mcpManager.connect("my-server", "python", List.of("server.py"), Map.of());
mcpManager.connectHttp("remote-server", "http://localhost:3000", Map.of());
mcpManager.disconnect("my-server");

List<McpClient.McpTool> mcpTools = mcpManager.getAllTools();
```

> MCP tools register into `ToolRegistry` automatically; the model can invoke them
> directly as `mcp__<server>__<tool>`.

### Hooks

Insert custom logic around tool invocation. Hooks are **session-scoped**:

```java
HookManager hooks = sessionManager.getSessionHooks(sessionId);

// PRE_TOOL_USE — block execution
hooks.register(HookType.PRE_TOOL_USE, "block-dangerous", ctx -> {
    if ("Bash".equals(ctx.getToolName())) {
        String command = (String) ctx.getArguments().get("command");
        if (command != null && command.contains("rm -rf")) {
            return HookResult.ABORT;   // tool is not run; the model is told a hook aborted it
        }
    }
    return HookResult.CONTINUE;
}, 10);   // priority: lower runs first

// PRE_TOOL_USE — rewrite arguments
// getArguments() returns the very map the tool will execute with; mutate in place
hooks.register(HookType.PRE_TOOL_USE, "redirect-to-sandbox", ctx -> {
    Object path = ctx.getArguments().get("file_path");
    if (path != null && path.toString().startsWith("/prod/")) {
        ctx.getArguments().put("file_path", "/sandbox" + path);
    }
    return HookResult.CONTINUE;
});

// POST_TOOL_USE — rewrite the result fed back to the model
hooks.register(HookType.POST_TOOL_USE, "redact-secrets", ctx -> {
    String result = ctx.getResult();
    if (result != null) {
        ctx.setResult(result.replaceAll("(?i)(api[_-]?key\\s*=\\s*)\\S+", "$1[REDACTED]"));
    }
    return HookResult.CONTINUE;
});
```

**These are the only two moments.** An exception from one hook is logged and ignored,
affecting neither the main flow nor the remaining hooks.

### Denial auditing

Once consecutive or cumulative refusals reach the threshold, tools requiring
confirmation are auto-denied. Register a callback to observe it:

```java
sessionManager.getSessionDenials(sessionId).addDenialCallback((consecutive, total) -> {
    auditLog.warn("Session {} hit the denial threshold: {} consecutive / {} total",
            sessionId, consecutive, total);
    alarmService.send("Anomalous permission denials — inspect this session");
});
```

The callback is **observational only**; the allow/deny decision rests with
`PermissionRuleEngine` and the permission callbacks.

### Third-party extensions

There is no separate plugin framework — Spring's own mechanisms suffice. Declare a
`Tool` bean, or package tools into a JAR carrying `@AutoConfiguration` and drop it on
the classpath. That gives you dependency injection, conditional wiring and bean
lifecycle without managing class loaders yourself.

```java
@Configuration
public class MyToolsConfig {
    @Bean
    public MyCustomTool myCustomTool() {
        return new MyCustomTool();
    }
}
```

Use `ToolManager` for runtime tool changes.

### Feature flags

```java
// Keys are caller-defined; the SDK does not consume them internally
featureFlagService.setFlag("MY_FEATURE", false);
boolean enabled = featureFlagService.isEnabled("MY_FEATURE");

// Environment variables take highest precedence:
// export CLAUDE_CODE_FF_WORKTREE_MODE=false
```

## Architecture

### Module layout

```
com.inspirationi.loop
├── HmsApplication              // Spring Boot auto-configuration entry (SDK mode)
├── api/                        // Public API layer
│   ├── HmsSessionManager       // Session-isolating manager (the sole public interface)
│   ├── DefaultHmsSessionManager
│   ├── HmsService              // Single-session façade (simplified)
│   ├── DefaultHmsService
│   ├── HmsCallbacks            // Callback set
│   ├── HmsResponse
│   ├── SessionInfo
│   ├── SessionActivity         // Runtime state (6 states, orthogonal to SessionStatus)
│   ├── HmsEvent                // Transport-neutral sealed event model (9 events)
│   ├── EventBridgeCallbacks    // HmsCallbacks → Consumer<HmsEvent>
│   ├── PendingResponses        // Suspended-request registry (future + timeout)
│   ├── PromptManager / DefaultPromptManager     // Two-tier prompts
│   ├── ToolManager / DefaultToolManager         // Two-tier tools
│   └── ApiAutoConfiguration
├── web/                        // Web bridge (depends on spring-webmvc, optional)
│   ├── HmsSseBridge            // SSE façade
│   └── WebBridgeAutoConfiguration  // guarded by @ConditionalOnClass(SseEmitter)
├── core/                       // Agent core
│   ├── AgentLoop               // The loop (blocking + streaming; hooks, permissions, compaction)
│   ├── AgentToolExecutor
│   ├── TaskManager             // Background tasks (virtual-thread pool + state machine)
│   ├── HookManager
│   ├── TokenTracker            // Token accounting + context-window monitoring
│   ├── CoordinatorMode         // Sub-agent orchestration
│   └── compact/                // Three-layer compaction subsystem
│       ├── AutoCompactManager  // Orchestrator (cascade + circuit breaker)
│       ├── MicroCompact        // Local truncation, no API call
│       ├── SessionMemoryCompact// AI summary, retains a recent window
│       ├── FullCompact         // Fallback, with PTL retry
│       └── CompactionResult
├── tool/                       // Tool system
│   ├── Tool                    // name / description / schema / execute
│   ├── ToolRegistry
│   ├── ToolValidator
│   ├── ToolContext             // Execution context (no filesystem dependency)
│   ├── ToolCallbackAdapter     // Spring AI ToolCallback adapter
│   ├── AbstractReadOnlyTool
│   └── impl/                   // 20 tool implementations
├── mcp/                        // MCP protocol client
│   ├── McpTransport / StdioTransport / HttpSseTransport
│   ├── McpClient               // JSON-RPC 2.0
│   ├── McpManager              // Multi-server management
│   └── McpException
├── permission/                 // Permission subsystem
│   ├── PermissionTypes         // behaviours / modes / rules / decisions / choices
│   ├── PermissionRuleEngine    // Eight-step evaluation chain
│   ├── PermissionSettings      // Three-tier management (in memory)
│   ├── DangerousPatterns
│   ├── RiskDetector
│   └── DenialTracker           // 3 consecutive / 20 cumulative thresholds
├── telemetry/                  // Telemetry, billing, feature management
│   ├── FeatureFlagService
│   ├── MetricsCollector
│   ├── TokenUsage              // Four token classes
│   ├── TokenPricing            // Pricing strategy — overridable by bean
│   └── BuiltinModelPricing     // Built-in rate card (hms-core.pricing.* overrides)
├── config/
│   ├── AppConfig
│   ├── PricingProperties       // binds hms-core.pricing.*
│   └── ToolConfiguration
└── util/
    └── ModelResolver           // Model alias resolution
```

### Request flow

```
HmsSessionManager.createSession()
    │
    ├── instantiate AgentLoop (own ChatModel, ToolRegistry, PermissionSettings)
    ├── copy the global tool registry into session scope
    ├── build the system prompt (global + session)
    └── register in the session map

HmsSessionManager.send(sessionId, message)
    │
    ├── session state check (ACTIVE / PAUSED / DESTROYED)
    ├── input validation
    ├── register callbacks
    ├── AgentLoop.run(userMessage)
    │      │
    │      ├── append UserMessage
    │      ├── while iteration < max-iterations:
    │      │      │
    │      │      ├── ChatModel.call(prompt)
    │      │      ├── TokenTracker.recordUsage()
    │      │      │
    │      │      ├── tool_calls present?
    │      │      │   ├── PreToolUse hook → permission evaluation
    │      │      │   ├── execute tool → ToolCallbackAdapter.call()
    │      │      │   └── PostToolUse hook
    │      │      │
    │      │      ├── append AssistantMessage + ToolResponseMessage
    │      │      │
    │      │      ├── maybeAutoCompact() → next iteration
    │      │      │
    │      │      └── no tool_calls
    │      │          ├── maybeAutoCompact()   ← text-only turns must compact too
    │      │          └── exit loop
    │      └── return HmsResponse
    │
    └── onComplete(response)
```

### Permission evaluation chain

```
tool call → PermissionRuleEngine.evaluate()
    │
    ├── ① BYPASS mode                          → ALLOW
    ├── ② STRICT mode                          → read-only ALLOW, writes DENY
    ├── ③ ToolContext mode override            (session-scoped dynamic override)
    ├── ④ risk-level auto-allow                (via the autoAllowUpTo mapping)
    ├── ⑤ alwaysDeny rule match                → DENY
    ├── ⑥ alwaysAllow rule match               → ALLOW
    ├── ⑦ RiskDetector                         → flagged ⇒ ASK
    └── ⑧ default                              → ASK
             ↓
    ALLOW_ONCE / ALWAYS_ALLOW / DENY_ONCE / ALWAYS_DENY
```

### Compaction architecture

Compaction has **two entry points** sharing the same layers:

```
① Automatic — AutoCompactManager.autoCompactIfNeeded()
   │   invoked by AgentLoop every turn, on both exits of the loop
   ├── preconditions: breaker closed AND TokenTracker.shouldAutoCompact() (>93 % of the effective window)
   │
   ├── ① MicroCompact — local truncation, no API call
   │       retains the last 6 tool_results; time-aware (>10 min → keeps 2)
   │       also runs every turn below the threshold (it is free)
   │       effective and below the blocking threshold (98 %) → return here, never reaching the paid layers
   │
   ├── ② SessionMemoryCompact — AI summary, one API call
   │       retains a recent window, never splitting a tool call/result pair
   │       failure → fall through (increments consecutiveFailures)
   │
   └── ③ FullCompact — full summarisation, multiple API calls (fallback)
           API-round grouping → PTL gap parsing → progressive dropping → circuit breaker
           three consecutive failures → breaker opens, automatic attempts stop

② Manual — AutoCompactManager.compactNow()
   │   triggered by HmsSessionManager.compactNow(sessionId)
   ├── no preconditions: ignores the breaker flag and the token threshold
   ├── goes straight to FullCompact (skipping ① and ②), recorded as MANUAL
   └── failures do not increment consecutiveFailures — the automatic budget is untouched
```

> The breaker constrains automatic compaction only. `compactNow()` still works once
> it has opened, and `resetCompactionCircuitBreaker(sessionId)` clears it manually.

### Session isolation

```
sessionManager
    │
    ├── sessionId: "abc123"
    │   ├── AgentLoop          → its own ChatModel instance
    │   ├── ToolRegistry       → copied from global + session additions
    │   ├── PermissionSettings → shared global settings + session rules
    │   ├── PromptManager      → global prompt + session prompt
    │   └── MetricsCollector   → independent metrics
    │
    ├── sessionId: "def456"    → fully isolated, same structure
    │
    └── cleanupScheduler       → periodic idle reclamation (5 min sweep, 30 min timeout)
```

## Configuration reference

### application.yml

```yaml
hms-core:
  provider: ${HMS_CORE_PROVIDER:openai}    # openai / anthropic

  session:
    idle-timeout-minutes: 30
    cleanup-interval-minutes: 5
    max-sessions: 1000

  # Maximum iterations per turn — how many "model call → tool execution" cycles a
  # single send() may run. Hitting the ceiling truncates the answer and appends a
  # warning marker; long tool chains may need more. Values <= 0 fall back to 50.
  max-iterations: 50

  # Wait limit for user responses (questions / permission confirmation), in seconds.
  # On timeout: questions → skip, permissions → deny.
  user-response-timeout-seconds: 300

  # Context window and compaction threshold — see the section below
  context-window: 200000
  reserved-tokens: 20000

  # Token pricing — overrides the built-in rate card (USD per million tokens).
  # Keys are case-insensitive substrings of the model name; longest pattern wins;
  # all three fields are required or the entry is discarded.
  pricing:
    models:
      opus:
        input: 15.0
        output: 75.0
        cache-read: 1.5

  sse:
    # SSE idle timeout (minutes) — must exceed the expected duration of one agent turn
    emitter-timeout-minutes: 30

  metrics:
    enabled: true
    flush-interval-seconds: 60

# Spring AI — Anthropic
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

# Spring AI — OpenAI (works with any OpenAI-compatible API)
    openai:
      api-key: ${AI_API_KEY:}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o}
```

### Environment variables

| Variable | Required | Description | Default |
|---|---|---|---|
| `AI_API_KEY` | ✅ | API key | — |
| `HMS_CORE_PROVIDER` | — | Provider (`openai` / `anthropic`) | `openai` |
| `AI_BASE_URL` | — | API base URL | provider-dependent |
| `AI_MODEL` | — | Model name | provider-dependent |
| `AI_MAX_TOKENS` | — | Maximum output tokens | `8096` |
| `HMS_CORE_CONTEXT_WINDOW` | — | Context window (tokens); effective only when `hms-core.context-window` is unset | `200000` |
| `HMS_CORE_I18N_ENABLED` | — | Prompt-translation switch | `true` |

> Reserved tokens have no environment-variable equivalent; use
> `hms-core.reserved-tokens`.

### Context window and compaction threshold

```
effective window = context-window − reserved-tokens
threshold        = effective window × 93 %
```

The criterion is **the prompt token count of the most recent request**, not
cumulative usage — hundreds of thousands cumulative will never trigger compaction,
and that is correct: cumulative volume says nothing about current context size.

Precedence: `hms-core.*` properties > `HMS_CORE_CONTEXT_WINDOW` > built-in defaults.
The environment variable exists only for direct `new TokenTracker()` use outside
Spring.

**Consequences of misconfiguration:**

| Situation | Consequence |
|---|---|
| `context-window` below the model's real window | Overload is declared far short of the real limit (logs show >100 % utilisation) and compaction fires repeatedly. Both the session-memory and full layers must send the history to the model for summarisation — yet that history is perfectly legal upstream, so even a successful compaction is wasted work |
| `context-window` above the model's real window | Compaction arrives too late; the request exceeds the limit and is rejected upstream |
| Non-positive, or `reserved-tokens >= context-window` | Both fall back to built-in defaults. Without that fallback the effective window would be zero, utilisation would be permanently 0, and compaction would never fire — with symptoms that are extremely hard to trace |

To observe compaction locally, **increase `reserved-tokens` rather than decreasing
`context-window`**:

```yaml
hms-core:
  context-window: 200000    # keep aligned with the model's real window
  reserved-tokens: 170000   # effective window 30 000, threshold 27 900 — reachable in a few long turns
```

## Model aliases

`ModelResolver` resolves short names:

| Alias | Resolves to |
|---|---|
| `haiku` / `haiku-3` / `claude-3-haiku` | `claude-3-haiku-20240307` |
| `sonnet-3.5` / `claude-3.5-sonnet` | `claude-3-5-sonnet-20241022` |
| `sonnet` / `sonnet-4` / `claude-sonnet-4` | `claude-sonnet-4-20250514` |
| `opus` / `opus-4` / `claude-opus-4` | `claude-opus-4-20250514` |
| `gpt-4` / `gpt-4o` / `gpt-4o-mini` | passed through |
| `o1` / `o1-mini` / `o3` / `o3-mini` | passed through |

## Changelog

| Version | Date | Changes |
|---|---|---|
| **`1.0.0`** | **2026-09** | **First stable release** — the public API is now frozen and follows semantic versioning. Everything below is part of it |
| ↳ | 2026-09 | Token pricing extracted into the overridable `TokenPricing` extension point (built-in rate card + `hms-core.pricing.*`); `TokenStats` / `SessionInfo` gained cache tokens plus `cost` / `pricingModel`; the pricing API on `TokenTracker` is deprecated |
| ↳ | 2026-09 | Fixed 8 defects including reasoning models permanently disabling compaction and the UI stalling at "thinking" after a streaming fallback; added a circuit-breaker reset API |
| ↳ | 2026-09 | Added `SessionActivity` runtime state (6 states), `HmsCallbacks.onActivity` and `HmsEvent.Activity`; `onToolUse` and `HmsEvent.ToolUse` gained a `phase` parameter; fixed 3× inflation of tool-usage metrics |
| ↳ | 2026-09 | Added manual compaction `compactNow(sessionId)`; context window and reserved tokens made configurable; fixed 3 compaction defects |
| ↳ | 2026-09 | Added the web bridge (`HmsEvent` / `EventBridgeCallbacks` / `PendingResponses` / `HmsSseBridge`), reducing integrator SSE code from ~270 lines to 1; fixed 3 callback defects |
| ↳ | 2026-08 | Refactored into the HMS Core SDK: CLI/TUI removed; session-isolation API, two-tier prompt/tool management and MCP HTTP SSE transport added |
| `0.1.0` | 2025 | Initial release |

> **What 1.0.0 commits to.** The public API — `HmsSessionManager`, `HmsCallbacks`,
> `HmsEvent`, `TokenPricing` and their data types — follows semantic versioning from
> here: breaking changes only on a major bump.
>
> Methods marked `@Deprecated(since = "1.0.0")` remain available throughout 1.x and
> will not be removed before 2.0. They are pre-1.0 leftovers (the pricing API on
> `TokenTracker`, `HmsResponse.interrupted(String)`); shipping 1.0 with deprecations
> already in place is deliberate — better to carry a known-flawed design with a
> documented migration path than to freeze it into the stable surface.

### 2026-09 — Token pricing refactor

Billing was previously half-built dead code: `estimateCost()` had **zero callers** in
production code, `isPricingKnown()` was **never read**, and the rate card was five
`if-else` branches with fifteen magic numbers whose comments still said
"Claude Sonnet 4".

| Problem | Impact | Change |
|---|---|---|
| Rate card hard-coded in `TokenTracker` | Prices change and new models appear, so **every repricing needed a new release** with no recourse for integrators | Extracted the `TokenPricing` interface; `BuiltinModelPricing` supports `hms-core.pricing.*` overrides, and integrators can take over entirely with their own bean |
| "Amount" and "trustworthiness" on separate channels | `isPricingKnown()` had no readers, so unknown models had a cost **silently computed against the Sonnet card** and treated as real | Collapsed into a single `Optional<BigDecimal>`, making unknown pricing impossible to ignore at the type level |
| Cost held as `double` | Binary floating point for money accumulates error across calls | Switched to `BigDecimal` |
| Pricing held as state | Five mutable fields plus a `setModel` mutator — a pure function written as a state machine | `TokenPricing` is a stateless function: one shared bean, inherently thread-safe |
| `gpt-4o-mini` depended on `if-else` order to avoid being captured by `gpt-4o` | Adding a branch could silently break it, and their unit prices differ ~16× | Longest-pattern-wins, guaranteed structurally by sorting at construction, independent of configuration order |
| Cost never reached the query surface | The `/cost` command showed token counts and no amount — an abstraction nobody calls is just refactored dead code | `TokenStats` / `SessionInfo` gained `cost` / `pricingModel`; `/tokens`, `/metrics`, `/cost` and `/context` all wired up |

> **Behavioural change:** `estimateCost()` now returns `0.0` for an unrecognised model
> name, where it previously produced a plausible figure from the Sonnet card unrelated
> to the actual bill. `setModel` / `estimateCost` / `isPricingKnown` / `getModelName`
> are all `@Deprecated`.
>
> Tests: `telemetry/BuiltinModelPricingTest` (12 cases — price isolation, match
> precedence, unknown models, config override, `BigDecimal` precision) and
> `config/PricingWiringTest` (5 cases — a real container via `ApplicationContextRunner`
> verifying relaxed binding and `@ConditionalOnMissingBean` overridability).
>
> The end-to-end contract is verified by `demo-app/verify-pricing.mjs` (21 checks) —
> unit tests cannot prove things about the serialisation layer: whether `BigDecimal`
> becomes a string, whether `null` makes `Map.of` throw a 500, whether a record's
> derived methods leak into the JSON.

### 2026-09 — Eight defects fixed

| Defect | Impact | Fix |
|---|---|---|
| Summarisation read only `getText()` | **Reasoning models disabled compaction permanently**: extended thinking puts the output in metadata leaving the body empty → judged a "blank summary" → five PTL retries all blank → counted toward the breaker → breaker opens permanently, no further compaction, context grows until upstream returns 400 | Added `SummaryText`: body first, falling back to the thinking content when the body is blank |
| `SessionMemoryCompact` chained three dereferences | `response.getResult().getOutput().getText()` NPEs if any level is null, swallowed as `FAILED` and wasting breaker budget | Routed through `SummaryText`, resolving the null checks with it |
| No escape from an open breaker | `resetCircuitBreaker()` existed but was not exposed; users could only destroy the session and lose all context | Added `HmsSessionManager.resetCompactionCircuitBreaker(sessionId)` and an endpoint |
| Zero `onToken` output after a streaming fallback | The UI bubble relies entirely on accumulated tokens and `complete` only clears the cursor — **the bubble stalled at "thinking" forever**, with the reply visible only after a refresh | `AgentLoop.replayFallbackText` replays it, but only when the streaming side produced nothing, avoiding double-rendering after a mid-stream break |
| `DefaultHmsService` was an unfixed copy of bugs already fixed for multi-session | ① AskUser callbacks registered but never cleared, so later questions went to the previous receiver; ② usage read as session cumulative rather than per-turn delta (three turns of 100 reported as 600); ③ interrupted turns reported as ok | All three aligned with `DefaultHmsSessionManager`, with `buildResponse` extracted |
| `MicroCompact` misreported message counts | It replaces tool_results in place — **the count does not change at all** — yet pushed "tool response count" into `messagesBefore/messagesAfter` for the front end | Added `CompactionResult.microSuccess`; the count fields now report true history length and the trimmed volume goes into `reason` |
| Sub-threshold micro-compaction notified nobody | The history genuinely changed (oversized tool_results replaced by placeholders) yet no SSE event explained it — the same action had two different observabilities depending on the path | That path now also calls `notifyEvent` and returns a result |
| Two `TokenTracker` setters bypassed constructor validation | Setting the window to 0 → utilisation permanently 0 → **compaction never fires**, exactly the "hard to trace" symptom the constructor comment warns about | Extracted shared `normalizeWindow` / `normalizeReserved`; changing the window also re-normalises the reserve |

> The first two defects are two ends of the same failure chain, both of the "silently
> breaks when you change models" kind — every existing compaction test missed them
> because their mock models always returned a normal body.

### 2026-09 — Compaction defects fixed

| Defect | Impact | Fix |
|---|---|---|
| The compaction check sat only inside the "has tool calls" branch | **Text-only conversations never compacted** — turns without tool calls exit at an earlier `break`, so context grew past the window and was rejected upstream | Extracted `AgentLoop.maybeAutoCompact()`, covering both exits of the loop |
| `succeed()` replaced the history before reading `before.size()` | `messagesBefore` was **always equal to** `messagesAfter` — `before` *is* the caller's history list and replacement is an in-place `clear() + addAll()`, so logs and SSE events showed "FULL compact: 4 → 4 messages": compacted, but reported as not | Capture both sizes before replacing |
| Context window and reserved tokens hard-coded | Could not be tuned per model. Too small wastes summarisation cost and loses context; too large lets requests exceed the limit | Exposed as `hms-core.context-window` / `reserved-tokens`, with fallback for non-positive values or `reserved >= window` |

> Regression tests: `TextOnlyCompactionTest`, `CompactionCountReportingTest`,
> `ManualCompactTest` under `core/compact/`, plus `api/ContextWindowConfigTest`.
>
> The first two were "silent failure" defects that all three existing compaction tests
> missed — respectively because they called `autoCompactIfNeeded` directly, asserted
> only on wiring, or used a "calls a tool every turn" mock that happened to stay on the
> one branch that worked. The new tests drive the public `HmsSessionManager` API
> end to end.

### 2026-09 — Callback defects fixed

| Defect | Impact | Fix |
|---|---|---|
| `onPermissionRequest` defaulted to `"deny"` | Integrators overriding only `onPermissionRequestAsync` had permissions **always denied**; the async callback was dead code | Default now returns `null` (abstain), making the async path reachable; behaviour unchanged for those who do not override |
| A hard-coded `.get(30, SECONDS)` inside the library | Configuring 300 seconds had no effect; a user answering at second 40 was discarded | Governed by `hms-core.user-response-timeout-seconds` (default 300) |
| `onError` was never invoked | The `retry`/`abort` semantics of the error callback were unimplemented | `DefaultHmsSessionManager.send` catches, notifies, then rethrows unchanged |

> Regression tests: `api/CallbackFallbackTest` (11 cases).

### 2026-09 — Tool event defects fixed

| Defect | Impact | Fix |
|---|---|---|
| `ToolEvent.Phase` discarded in `DefaultHmsSessionManager` | One invocation emits START / PROGRESS / END, all counted equally into `metrics.recordToolUse` — **tool usage inflated 3×**; the front end also rendered one bubble per event | Branch on `phase`: count once at `END`; `onToolUse` and `HmsEvent.ToolUse` expose `phase` to integrators |
| Activity state could stall mid-state after a request | `AgentLoop` is a session-scoped object — if any path (exception, cancellation, iteration ceiling) missed the reset, every later query on that session returned a stale state and the UI showed "using tool" forever | `executeLoop` wrapped in `try/finally`, converging all four exits on `IDLE` |

> Regression tests: `core/SessionActivityTest` (11 cases, pinning each of the four
> reset paths).
>
> The real danger in a signature change is not at compile time:
> `SessionExtensionPointsTest` overrode the old three-argument `onToolUse`, and after
> adding `phase` it **no longer overrode the interface method** — it degenerated into
> an uncalled ordinary method. Compilation passed and the assertions were vacuous.
> Same "silent failure" class as the compaction defects above: when changing an
> interface signature, search every override site.

---

<div align="center">

## License

Licensed under the [Apache License 2.0](../LICENSE).

</div>
