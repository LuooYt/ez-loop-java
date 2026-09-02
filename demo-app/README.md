<div align="center">

# HMS Web Demo

**Reference console for the HMS Core SDK**

**English** · [中文](README.zh-CN.md)

</div>

---

An AI agent web console built on the HMS Core SDK — and simultaneously a **minimal
integration example**. SSE streaming, tool logs and permission dialogs are all wired
up, yet the controller layer contains virtually no glue code.

## Getting started

### Prerequisites

- JDK 25 (`JAVA_HOME` configured)
- Maven 3.9+
- An API key for Anthropic or an OpenAI-compatible service
- `hms-core-0.2.0-SNAPSHOT.jar` installed into the local Maven repository
  (run `mvn install` in `hms-core/`)

### Configuration

Two profiles ship with the application:

| Profile | Purpose | Version-controlled |
|---|---|---|
| `prod` | Everything injected via environment variables | ✅ (the default) |
| `dev` | Local debugging, contains credentials | ❌ (see `.gitignore`) |

`prod` is the default deliberately: `application-dev.yml` is not in version control,
so it does not exist in a fresh clone — defaulting to `dev` would fail at startup for
want of credentials. `prod` reads everything from the environment and reports exactly
what is missing, which is a more diagnosable default.

```bash
# Anthropic native API
export AI_API_KEY="sk-ant-xxx"
export HMS_CORE_PROVIDER="anthropic"

# Or an OpenAI-compatible API
export AI_API_KEY="sk-xxx"
export HMS_CORE_PROVIDER="openai"

# Optional
export AI_BASE_URL="https://api.anthropic.com"
export AI_MODEL="claude-sonnet-4-20250514"
```

For local debugging, create `application-dev.yml` and activate it explicitly:

```bash
export SPRING_PROFILES_ACTIVE=dev
```

### Run

```bash
cd demo-app
mvn spring-boot:run
```

Open http://localhost:8088 (override the port with `SERVER_PORT`).

## Integration: the controller needs one line

SSE bridging — emitter lifecycle, event serialisation, virtual-thread scheduling and
asynchronous waiting for user responses — is entirely handled by hms-core's
`HmsSseBridge`. This demo contains **no** SSE glue code:

```java
@GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter chatStream(@PathVariable String sessionId, @RequestParam String message) {
    return sseBridge.stream(sessionId, message);   // that's all
}
```

Beyond that, only two forwarding endpoints and cleanup on destruction:

```java
// Client submits an answer to a question / a permission decision
sseBridge.submitAskResponse(sessionId, response);
sseBridge.submitPermissionResponse(sessionId, response);

// Destroy the session: release the SSE connection and any pending requests
sseBridge.release(sessionId);
// Cancel execution only: release pending requests, keep the connection
sseBridge.cancelPending(sessionId);
```

> Submitting a response is **best-effort**: when no request is awaiting one (because
> it already timed out, say) the call still succeeds, so the client need not handle
> that race.

## Project layout

```
demo-app/
├── pom.xml
├── src/main/java/com/inspirationi/hmsweb/
│   ├── HmsWebDemoApplication.java      # entry point (scanBasePackages includes com.inspirationi.loop)
│   ├── config/WebMvcConfig.java        # CORS + static resources
│   ├── controller/
│   │   ├── SessionController.java      # session management (incl. manual compaction, prompt I/O)
│   │   ├── ChatController.java         # conversation (synchronous + SSE)
│   │   ├── ToolController.java         # tool management
│   │   ├── PermissionController.java   # permission management
│   │   ├── MetricsController.java      # metrics queries
│   │   └── ApiExceptionHandler.java    # translates SDK exceptions into the unified envelope
│   └── model/                          # DTOs (ApiResponse / CompactResponse / ...)
├── src/main/resources/
│   ├── application.yml                 # shared configuration (no credentials)
│   ├── application-prod.yml            # production — all via environment variables
│   └── static/                         # front-end SPA
│       ├── index.html
│       ├── css/
│       └── js/
│           ├── api.js                  # REST wrapper
│           ├── sse-client.js           # EventSource wrapper
│           ├── commands.js             # slash-command registry
│           └── components/             # chat-panel / command-palette / ...
├── api-test.mjs                        # HTTP smoke test (see below)
├── verify-pricing.mjs                  # pricing contract verification
└── src/test/java/                      # 25 integration tests
```

> An earlier version hand-wrote 272 lines of SSE bridging in
> `service/SessionBridgeService.java`. That has all moved down into hms-core; the file
> and the `service/` package are gone.

## API surface

| Area | Path | Notes |
|---|---|---|
| Sessions | `POST/GET/DELETE /api/sessions` | Create / query / destroy. Responses carry `status` (lifecycle) and `activity` (runtime) |
| Session control | `POST /api/sessions/{id}/pause`, `/resume`, `/cancel` | Pause / resume / cancel the current execution |
| Session ops | `POST /api/sessions/cleanup?idleSeconds=` | Bulk-reclaim idle sessions |
| Session queries | `GET /api/sessions/{id}/tokens`, `/messages` | Token stats (four classes + `cost` / `pricingModel`) / message history |
| Manual compaction | `POST /api/sessions/{id}/compact` | Returns `{compacted, layer, messagesBefore, messagesAfter, reason}` |
| Breaker reset | `POST /api/sessions/{id}/compact/reset-circuit-breaker` | Restores automatic compaction, returns `{wasBroken}`. The breaker is permanent — without this endpoint a user could only destroy the session and start over |
| Prompts | `GET/PUT /api/sessions/{id}/prompt` | Read `{sessionPrompt, globalPrompt}` / update `{sessionPrompt}` |
| Chat | `POST /api/chat/{id}` | Synchronous conversation |
| Streaming | `GET /api/chat/{id}/stream?message=` | SSE (direct `EventSource`) |
| Answers | `POST /api/chat/{id}/ask-response` | Submit an answer to the agent's question |
| Permissions | `POST /api/chat/{id}/permission-response` | Submit a decision (`allow` / `deny`) |
| Tools | `GET /api/tools`, `/api/tools/{id}` | Global / session tool listing and management |
| Permission config | `GET/PUT/POST /api/permissions` | Modes and rules |
| Metrics | `GET /api/metrics/{id}` | Token stats (incl. `cost` / `pricingModel`) / dashboard |

> **Status-code convention.** Validation performed by the controller returns
> **HTTP 200 with `success: false`**; only exceptions thrown by hms-core are
> translated by `ApiExceptionHandler`. Those are classified by error code: caller
> errors (1xxx / 2xxx / 3xxx) become **400** and are logged at debug, while server
> and upstream failures (5xxx+) become **500** with a full stack trace. 404 is not
> used — the client only inspects `success`.
>
> A `null` `cost` means **pricing is unknown for that model**; the front end must
> render it distinctly from `0` (see `Format.cost`). Showing unknown as `$0.00` turns
> "no rate card configured" into "nothing was spent".

## Slash commands

Typing `/` in the composer opens a completion overlay: prefix filtering, ↑↓ selection,
Tab completion, Enter to run, Esc to dismiss. Thirteen commands, registered in
`static/js/commands.js` — adding one means editing that file only.

| Category | Commands |
|---|---|
| Client-side only | `/help` `/clear` `/new` `/cancel` `/context` `/cost` `/export` |
| Existing endpoints | `/pause` `/resume` `/cleanup` `/tools` |
| Newer endpoints | `/compact` `/prompt` (no argument reads, with argument updates) |

Two important semantics:

- **Commands are not written into `messageHistory`** — they are console operations,
  not conversation. Storing them would waste the token window, drag them into
  compaction, and let the model read `/clear` as something the user said. Command
  traces therefore disappear on refresh or session switch.
- **Only `/cancel` may run mid-stream** (the `duringStream` field in the registry).
  The composer stays editable during streaming for exactly that reason — ordinary
  messages are stopped by a guard in `sendMessage()`, and other commands are refused
  by `runCommand()` per the registry, so nothing leaks out.

## Runtime activity state

The UI shows what a session is doing right now, sourced from hms-core's
`SessionActivity` (six states, orthogonal to `SessionStatus`, which governs whether
messages can be accepted).

| State | Display | Trigger |
|---|---|---|
| `IDLE` | Idle | No request executing |
| `CALLING_MODEL` | Thinking | Request sent, first content not yet arrived |
| `THINKING` | Deep thinking | Extended-thinking fragment received |
| `RESPONDING` | Responding | First body token arrived |
| `USING_TOOL` | Using tool · *name* | Tool execution started |
| `WAITING_USER` | Awaiting you | Waiting for an answer or a permission decision |

Three display surfaces:

- **`chat-header` badge** (`#session-activity`) — always visible, covers the whole
  turn, with a breathing dot while busy
- **Empty-bubble placeholder** — follows the current state before the answer begins,
  instead of a permanent "thinking"
- **Sidebar dot** — activity colours while busy, falling back to lifecycle state when idle

A typical observed sequence: `Thinking → Using tool · TodoWrite → Thinking → Responding → Idle`

> `CALLING_MODEL` warrants its own state because "deep thinking" is only observable
> live when streaming *and* extended thinking are both on. On the blocking path the
> thinking content returns with the response (the model has long since answered), and
> without extended thinking the entire wait would produce no signal at all. With this
> state, none of the four combinations leaves a blank period in the UI.

## SSE event contract

`sse-client.js` listens for nine events whose field names are defined by hms-core's
`HmsEvent`:

| Event | Fields |
|---|---|
| `token` | `token` |
| `tool_use` | `toolName`, `phase` (`START` / `PROGRESS` / `END`), `input`, `result` (truncated beyond 5 000 chars) |
| `thinking` | `thinking` (truncated beyond 2 000 chars) |
| `activity` | `activity` (state enum name), `label` (display text), `detail` (e.g. tool name, nullable) |
| `ask_user` | `question`, `options` |
| `permission` | `toolName`, `description` |
| `compaction` | `layer`, `messagesBefore`, `messagesAfter`, `reason` |
| `complete` | `content`, `totalTokens`, `toolCallsCount`, `interrupted` |
| `error` | `message`, `code` |

> **This is a front-end/back-end contract.** Renaming an `HmsEvent` record component
> renames a JSON field and breaks the client (consumers are in
> `static/js/components/chat-panel.js`).

> `compaction` is emitted for **automatic** compaction only. Manual compaction
> (`POST /api/sessions/{id}/compact`) returns its result synchronously over HTTP: the
> compaction callback is request-scoped, and manual compaction is permitted only when
> no request is in flight — at which point the emitter the callback points at has
> already closed.

> **`tool_use` is pushed multiple times per invocation.** Branch on `phase`: `START`
> (just begun, `result` is null) → zero or more `PROGRESS` → `END` (complete, with the
> result). Treating each as a separate invocation renders duplicate bubbles for one
> call and multiplies usage counts. See the `tool_use` handler in `chat-panel.js`:
> START creates the bubble, PROGRESS appends, END fills in the result and counts once.

> **The idle state is not pushed over SSE.** The connection closes right after
> `complete`, leaving no receiver for the server's trailing `IDLE` — `complete` /
> `error` are themselves the "back to idle" signal, which the client applies in
> `onStreamEnd()`. Activity for *other* sessions in the sidebar comes from the
> `activity` field of `GET /api/sessions` (polled every 30 s).

## Relevant configuration

```yaml
hms-core:
  # Wait limit for user responses (questions / permission confirmation), in seconds.
  # On timeout: questions → skip, permissions → deny.
  user-response-timeout-seconds: 300

  # Context window and reserved tokens — this file carries detailed notes on the
  # consequences of misconfiguration.
  context-window: 200000
  reserved-tokens: 20000

  # Token pricing — overrides the built-in rate card (USD per million tokens).
  # Keys are case-insensitive substrings of the model name; longest pattern wins;
  # all three fields are required or the entry is discarded with a warning.
  pricing:
    models:
      opus:
        input: 15.0
        output: 75.0
        cache-read: 1.5

  sse:
    # SSE idle timeout (minutes)
    emitter-timeout-minutes: 30
```

## Running the tests

```bash
mvn test
```

Twenty-five integration tests covering 27 endpoints across five controllers, driving
real HTTP via the JDK's own `HttpClient` (Spring Boot 4 removed
`@AutoConfigureMockMvc` and `TestRestTemplate`).

Note that `chatSyncValidSession` makes a real AI API call; without a valid API key its
failure is expected. `SessionCommandApiTests` specifically covers manual compaction,
the prompt endpoints and session activity state without invoking the model.

## HTTP smoke test

`api-test.mjs` is a black-box end-to-end test and a **pure client**: it only issues
HTTP requests and never starts, stops or builds anything, so it will not disturb an
instance you are debugging. Zero dependencies — just Node 20+ with built-in `fetch`.

```bash
# Start the application yourself first, then:
node demo-app/api-test.mjs

node demo-app/api-test.mjs --list              # list all test groups
node demo-app/api-test.mjs --only compact      # run one group
```

Twelve groups: `tool` `session` `contract` `permission` `chat` `toolcall` `compact`
`stream` `lifecycle` `concurrency` `interactive` `metrics`.

**Exit codes:** 0 for all-pass, otherwise the failure count. One special case — when
the core groups (`chat` / `toolcall` / `stream` / `compact` / `interactive`) are
skipped wholesale because the model is unreachable, the exit code is **1**, because
"zero failures" does not mean "verified" there: the agent loop, tool invocation,
streaming and compaction were none of them exercised, and printing "all passed" would
turn CI green while hiding the problem.

### Pricing contract verification

`verify-pricing.mjs` verifies the JSON contract of token pricing specifically — unit
tests cannot prove things about the serialisation layer: whether `BigDecimal` becomes
a string, whether `null` makes `Map.of` throw a 500, whether a record's derived
methods leak into the JSON.

```bash
# Rates must match the hms-core.pricing.models.* in effect on the running instance
node demo-app/verify-pricing.mjs http://localhost:8088 --rate=10,65,1.2
```

`--rate` exists for cross-checking: the script computes the cost by hand from it and
compares against the server, thereby confirming that the configuration override
really took effect. **Do not hard-code the built-in defaults** — that makes the check
fail precisely when the override is working. (Observed in practice: dev had 10/65/1.2
configured while the script computed against the built-in 15/75/1.5, so it declared a
mismatch when the server was in fact correct.)

> On Windows, if you see "no response (HTTP 0)" while curl connects fine, re-run with
> `BASE_URL=http://127.0.0.1:8088` — Node's fetch resolves `localhost` to IPv6 `::1`.

---

<div align="center">

Part of the [HMS Core](../hms-core/README.md) project · [Apache License 2.0](../LICENSE)

</div>
