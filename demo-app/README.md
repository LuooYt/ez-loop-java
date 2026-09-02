# HMS Web Demo

基于 HMS Core SDK 的 AI Agent Web 控制台 —— 同时也是**最小集成示例**：SSE 流式对话、工具日志、权限确认弹窗全部打通，Controller 层几乎没有胶水代码。

## 快速开始

### 前置条件

- JDK 25（配置 `JAVA_HOME`）
- Maven 3.9+
- API Key（Anthropic 或 OpenAI 兼容服务）
- `hms-core-0.2.0-SNAPSHOT.jar` 已安装到本地 Maven 仓库（在 `hms-core/` 下执行 `mvn install`）

### 配置

设置环境变量：

```bash
# Anthropic 原生 API（推荐）
export AI_API_KEY="sk-ant-xxx"
export CLAUDE_CODE_PROVIDER="anthropic"

# 或 OpenAI 兼容 API
export AI_API_KEY="sk-xxx"
export CLAUDE_CODE_PROVIDER="openai"

# 可选配置
export AI_BASE_URL="https://api.anthropic.com"
export AI_MODEL="claude-sonnet-4-20250514"
```

也可直接改 `src/main/resources/application.yml`。

### 启动

```bash
cd demo-app
mvn spring-boot:run
```

访问 http://localhost:8088（端口由 `SERVER_PORT` 覆盖）

## 集成方式：Controller 只需一行

SSE 桥接（发射器生命周期、事件序列化、虚拟线程调度、用户回答的异步等待）全部由 hms-core 的 `HmsSseBridge` 承担，本 demo **不含**任何 SSE 胶水代码：

```java
@Autowired
private HmsSseBridge sseBridge;

@GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter chatStream(@PathVariable String sessionId, @RequestParam String message) {
    return sseBridge.stream(sessionId, message);   // 完
}
```

另外只需两个转发端点和销毁时的清理：

```java
// 前端提交 AI 提问的回答 / 权限确认
sseBridge.submitAskResponse(sessionId, response);
sseBridge.submitPermissionResponse(sessionId, response);

// 销毁会话：释放 SSE 连接 + 等待中的请求
sseBridge.release(sessionId);
// 仅取消执行：只释放等待中的请求，保留 SSE 连接
sseBridge.cancelPending(sessionId);
```

> 💡 提交回答是**尽力交付**语义：无待答请求（例如已超时）时同样返回成功，前端无需处理这种竞态。

## 项目结构

```
demo-app/
├── pom.xml
├── README.md
├── src/main/java/com/inspirationi/hmsweb/
│   ├── HmsWebDemoApplication.java      # 启动类（scanBasePackages 含 com.inspirationi.loop）
│   ├── config/WebMvcConfig.java        # CORS + 静态资源配置
│   ├── controller/
│   │   ├── SessionController.java      # 会话管理 API（含手动压缩、提示词读写）
│   │   ├── ChatController.java         # 对话 API（同步 + SSE 流式）
│   │   ├── ToolController.java         # 工具管理 API
│   │   ├── PermissionController.java   # 权限管理 API
│   │   └── MetricsController.java      # 指标查询 API
│   └── model/                          # DTO（ApiResponse / CompactResponse / ...）
├── src/main/resources/
│   ├── application.yml                 # 应用配置
│   └── static/                         # 前端 SPA
│       ├── index.html
│       ├── css/                        # 含 command-palette.css
│       └── js/
│           ├── api.js                  # REST 封装
│           ├── sse-client.js           # EventSource 封装
│           ├── commands.js             # slash 命令注册表
│           └── components/             # chat-panel / command-palette / ...
├── api-test.mjs                        # 接口冒烟测试（见下）
└── src/test/java/                      # 23 个接口集成测试
```

> 说明：早期版本在 `service/SessionBridgeService.java` 里手写了 272 行 SSE 桥接代码，现已全部下沉到 hms-core，该文件与 `service/` 包已删除。

## API 概览

| 模块 | 路径 | 说明 |
|------|------|------|
| 会话 | `POST/GET/DELETE /api/sessions` | 创建/查询/销毁 |
| 会话控制 | `POST /api/sessions/{id}/pause`、`/resume`、`/cancel` | 暂停/恢复/取消当前执行 |
| 会话运维 | `POST /api/sessions/cleanup?idleSeconds=` | 批量清理空闲会话 |
| 会话查询 | `GET /api/sessions/{id}/tokens`、`/messages` | Token 统计 / 历史消息 |
| 手动压缩 | `POST /api/sessions/{id}/compact` | 返回 `{compacted, layer, messagesBefore, messagesAfter, reason}` |
| 提示词 | `GET/PUT /api/sessions/{id}/prompt` | 读取 `{sessionPrompt, globalPrompt}` / 更新 `{sessionPrompt}` |
| 对话 | `POST /api/chat/{id}` | 同步对话 |
| 流式 | `GET /api/chat/{id}/stream?message=` | SSE 流式对话（EventSource 直连） |
| 回答 | `POST /api/chat/{id}/ask-response` | 提交 AI 提问的回答 |
| 权限 | `POST /api/chat/{id}/permission-response` | 提交权限确认（`allow` / `deny`） |
| 工具 | `GET /api/tools`、`/api/tools/{id}` | 全局 / 会话工具列表与管理 |
| 权限配置 | `GET/PUT/POST /api/permissions` | 权限模式/规则 |
| 指标 | `GET /api/metrics/{id}` | Token 统计/仪表盘 |

> 状态码约定：Controller 主动校验失败返回 **HTTP 200 + `success:false`**，只有 hms-core 抛出的异常才经 `ApiExceptionHandler` 转成 **400**。不使用 404 —— 前端只判 `success` 字段。

## 前端 slash 命令

聊天输入框输入 `/` 唤出补全浮层：前缀过滤、↑↓ 选择、Tab 补全、Enter 执行、Esc 关闭。共 13 个命令，注册表在 `static/js/commands.js`（新增命令只改那一处）。

| 分类 | 命令 |
|------|------|
| 纯前端 | `/help` `/clear` `/new` `/cancel` `/context` `/cost` `/export` |
| 调用既有端点 | `/pause` `/resume` `/cleanup` `/tools` |
| 调用新增端点 | `/compact` `/prompt`（无参查看，带参更新） |

两条关键语义：

- **命令不写入 messageHistory** —— 它们是控制台操作而非对话内容。入历史会白占 token 窗口、参与压缩，还会让 AI 把 `/clear` 当成用户在说话。因此刷新页面或切换会话后命令痕迹消失。
- **只有 `/cancel` 能在流式输出中执行**（注册表的 `duringStream` 字段）。为此输入框在流式期间保持可编辑 —— 普通消息由 `sendMessage()` 的守卫拦下，其余命令由 `runCommand()` 按注册表拒绝，消息不会漏发。

## SSE 事件契约

前端 `sse-client.js` 监听以下 8 个事件，字段名由 hms-core 的 `HmsEvent` 定义：

| 事件 | 字段 |
|------|------|
| `token` | `token` |
| `tool_use` | `toolName`、`input`、`result`（超 5000 字符截断） |
| `thinking` | `thinking`（超 2000 字符截断） |
| `ask_user` | `question`、`options` |
| `permission` | `toolName`、`description` |
| `compaction` | `layer`、`messagesBefore`、`messagesAfter`、`reason` |
| `complete` | `content`、`totalTokens`、`toolCallsCount`、`interrupted` |
| `error` | `message`、`code` |

> ⚠️ 这是前后端契约。修改 `HmsEvent` 的 record 组件名等于改动字段名，会破坏前端（消费方见 `static/js/components/chat-panel.js`）。

> 💡 `compaction` 事件只在**自动**压缩时推送。手动压缩（`POST /api/sessions/{id}/compact`）的结果由 HTTP 响应同步返回，不走 SSE —— 压缩事件回调是请求级的，而手动压缩只允许在无请求执行时进行，此时回调指向的 emitter 早已关闭。

## 相关配置

```yaml
hms-core:
  # 等待用户回答（AI 提问 / 权限确认）的上限秒数
  # 超时后按默认值处理：提问 → skip，权限 → deny
  user-response-timeout-seconds: 300
  # 上下文窗口与预留 Token —— 本文件内有详细注释说明配错的后果
  context-window: 200000
  reserved-tokens: 20000
  sse:
    # SSE 连接空闲超时（分钟）
    emitter-timeout-minutes: 30
```

## 运行测试

```bash
mvn test
```

23 个集成测试，覆盖 5 个 Controller 的 27 个端点。用 JDK 自带 `HttpClient` 打真实 HTTP（Spring Boot 4 已移除 `@AutoConfigureMockMvc` 与 `TestRestTemplate`）。注意：`chatSyncValidSession` 会真实调用 AI API，未配置有效 API Key 时该用例失败属预期。

其中 `SessionCommandApiTests` 专测手动压缩与提示词端点，不发起 AI 调用。

## 接口冒烟测试

`api-test.mjs` 是黑盒端到端测试，**纯客户端**：只发 HTTP 请求，不启动/停止/构建任何东西，因此不会打断你正在调试的实例。零依赖，只用 Node 20+ 内置 fetch。

```bash
# 先自己把应用跑起来，然后：
node demo-app/api-test.mjs

node demo-app/api-test.mjs --list              # 列出全部测试组
node demo-app/api-test.mjs --only compact      # 只跑一组
```

12 个测试组：`tool` `session` `contract` `permission` `chat` `toolcall` `compact` `stream` `lifecycle` `concurrency` `interactive` `metrics`。

**退出码**：0 全部通过，非 0 为失败数。特例 —— 核心组（`chat` / `toolcall` / `stream` / `compact` / `interactive`）因模型链路不可用而整组跳过时退出 **1**，因为「零失败」此时不等于「验证通过」：agent 循环、工具调用、流式、压缩一个都没被验证，打印"全部通过"会让 CI 变绿而掩盖问题。

> ⚠️ Windows 上若报「无响应（HTTP 0）」而 curl 却能连通，用 `BASE_URL=http://127.0.0.1:8088` 再跑一次 —— Node 的 fetch 会把 `localhost` 解析成 IPv6 `::1`。
