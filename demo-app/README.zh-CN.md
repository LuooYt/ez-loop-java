<div align="center">

# HMS Web Demo

**HMS Core SDK 参考控制台**

[English](README.md) · **中文**

</div>

---

基于 HMS Core SDK 的 AI Agent Web 控制台 —— 同时也是**最小集成示例**：SSE 流式
对话、工具日志、权限确认弹窗全部打通，而 Controller 层几乎没有胶水代码。

## 快速开始

### 前置条件

- JDK 25（配置 `JAVA_HOME`）
- Maven 3.9+
- API Key（Anthropic 或 OpenAI 兼容服务）
- `hms-core-1.0.0.jar` 已安装到本地 Maven 仓库
  （在 `hms-core/` 下执行 `mvn install`）

### 配置

应用自带两个 profile：

| Profile | 用途 | 纳入版本管理 |
|---|---|---|
| `prod` | 全部经环境变量注入 | ✅（默认） |
| `dev` | 本地调试，含密钥 | ❌（见 `.gitignore`） |

**默认走 `prod` 是有意为之**：`application-dev.yml` 未纳入版本管理，新克隆的仓库里
并不存在 —— 默认 `dev` 会因找不到密钥与端点而启动失败。`prod` 全部读环境变量，
缺什么报什么，是更可诊断的默认值。

```bash
# Anthropic 原生 API
export AI_API_KEY="sk-ant-xxx"
export HMS_CORE_PROVIDER="anthropic"

# 或 OpenAI 兼容 API
export AI_API_KEY="sk-xxx"
export HMS_CORE_PROVIDER="openai"

# 可选
export AI_BASE_URL="https://api.anthropic.com"
export AI_MODEL="claude-sonnet-4-20250514"
```

本地调试请自建 `application-dev.yml` 并显式激活：

```bash
export SPRING_PROFILES_ACTIVE=dev
```

### 启动

```bash
cd demo-app
mvn spring-boot:run
```

访问 http://localhost:8088（端口由 `SERVER_PORT` 覆盖）。

## 集成方式：Controller 只需一行

SSE 桥接 —— 发射器生命周期、事件序列化、虚拟线程调度、用户回答的异步等待 ——
全部由 hms-core 的 `HmsSseBridge` 承担。本 demo **不含**任何 SSE 胶水代码：

```java
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

> 提交回答是**尽力交付**语义：无待答请求（例如已超时）时同样返回成功，
> 前端无需处理这种竞态。

## 项目结构

```
demo-app/
├── pom.xml
├── src/main/java/com/inspirationi/hmsweb/
│   ├── HmsWebDemoApplication.java      # 启动类（scanBasePackages 含 com.inspirationi.loop）
│   ├── config/WebMvcConfig.java        # CORS + 静态资源配置
│   ├── controller/
│   │   ├── SessionController.java      # 会话管理 API（含手动压缩、提示词读写）
│   │   ├── ChatController.java         # 对话 API（同步 + SSE 流式）
│   │   ├── ToolController.java         # 工具管理 API
│   │   ├── PermissionController.java   # 权限管理 API
│   │   ├── MetricsController.java      # 指标查询 API
│   │   └── ApiExceptionHandler.java    # 把 SDK 异常翻译成统一信封
│   └── model/                          # DTO（ApiResponse / CompactResponse / ...）
├── src/main/resources/
│   ├── application.yml                 # 公共配置（不含密钥）
│   ├── application-prod.yml            # 生产 —— 全部经环境变量注入
│   └── static/                         # 前端 SPA
│       ├── index.html
│       ├── css/
│       └── js/
│           ├── api.js                  # REST 封装
│           ├── sse-client.js           # EventSource 封装
│           ├── commands.js             # slash 命令注册表
│           └── components/             # chat-panel / command-palette / ...
├── api-test.mjs                        # 接口冒烟测试（见下）
├── verify-pricing.mjs                  # 计费契约验证
└── src/test/java/                      # 25 个接口集成测试
```

> 早期版本在 `service/SessionBridgeService.java` 里手写了 272 行 SSE 桥接代码，
> 现已全部下沉到 hms-core，该文件与 `service/` 包已删除。

## API 概览

| 模块 | 路径 | 说明 |
|---|---|---|
| 会话 | `POST/GET/DELETE /api/sessions` | 创建/查询/销毁。响应含 `status`（生命周期）与 `activity`（运行时活动） |
| 会话控制 | `POST /api/sessions/{id}/pause`、`/resume`、`/cancel` | 暂停/恢复/取消当前执行 |
| 会话运维 | `POST /api/sessions/cleanup?idleSeconds=` | 批量清理空闲会话 |
| 会话查询 | `GET /api/sessions/{id}/tokens`、`/messages` | Token 统计（四类 token + `cost` / `pricingModel`）/ 历史消息 |
| 手动压缩 | `POST /api/sessions/{id}/compact` | 返回 `{compacted, layer, messagesBefore, messagesAfter, reason}` |
| 熔断重置 | `POST /api/sessions/{id}/compact/reset-circuit-breaker` | 恢复自动压缩，返回 `{wasBroken}`。熔断是永久的，没有这个端点用户只能销毁会话重来 |
| 提示词 | `GET/PUT /api/sessions/{id}/prompt` | 读取 `{sessionPrompt, globalPrompt}` / 更新 `{sessionPrompt}` |
| 对话 | `POST /api/chat/{id}` | 同步对话 |
| 流式 | `GET /api/chat/{id}/stream?message=` | SSE 流式对话（EventSource 直连） |
| 回答 | `POST /api/chat/{id}/ask-response` | 提交 AI 提问的回答 |
| 权限 | `POST /api/chat/{id}/permission-response` | 提交权限确认（`allow` / `deny`） |
| 工具 | `GET /api/tools`、`/api/tools/{id}` | 全局 / 会话工具列表与管理 |
| 权限配置 | `GET/PUT/POST /api/permissions` | 权限模式与规则 |
| 指标 | `GET /api/metrics/{id}` | Token 统计（含 `cost` / `pricingModel`）/ 仪表盘 |

> **状态码约定。** Controller 主动校验失败返回 **HTTP 200 + `success: false`**；
> 只有 hms-core 抛出的异常才经 `ApiExceptionHandler` 转换。转换按错误码分类：
> 调用方错误（1xxx / 2xxx / 3xxx）转 **400** 并只记 debug 日志，服务端与上游故障
> （5xxx 及以上）转 **500** 并打完整堆栈。不使用 404 —— 前端只判 `success` 字段。
>
> `cost` 为 `null` 表示**该模型定价未知**，前端必须与 `0` 区分显示
> （见 `Format.cost`）—— 把未知显示成 `$0.00` 会让「没配价目表」被读成「没花钱」。

## 前端 slash 命令

聊天输入框输入 `/` 唤出补全浮层：前缀过滤、↑↓ 选择、Tab 补全、Enter 执行、
Esc 关闭。共 13 个命令，注册表在 `static/js/commands.js`（新增命令只改那一处）。

| 分类 | 命令 |
|---|---|
| 纯前端 | `/help` `/clear` `/new` `/cancel` `/context` `/cost` `/export` |
| 调用既有端点 | `/pause` `/resume` `/cleanup` `/tools` |
| 调用新增端点 | `/compact` `/prompt`（无参查看，带参更新） |

两条关键语义：

- **命令不写入 `messageHistory`** —— 它们是控制台操作而非对话内容。入历史会白占
  token 窗口、参与压缩，还会让 AI 把 `/clear` 当成用户在说话。因此刷新页面或
  切换会话后命令痕迹消失。
- **只有 `/cancel` 能在流式输出中执行**（注册表的 `duringStream` 字段）。为此
  输入框在流式期间保持可编辑 —— 普通消息由 `sendMessage()` 的守卫拦下，其余命令
  由 `runCommand()` 按注册表拒绝，消息不会漏发。

## 运行时活动状态

界面实时显示会话「此刻正在做什么」，数据源是 hms-core 的 `SessionActivity`
（六态，与 `SessionStatus` 正交 —— 后者管能否接收消息）。

| 状态 | 展示 | 触发时机 |
|---|---|---|
| `IDLE` | 空闲 | 无请求执行 |
| `CALLING_MODEL` | 思考中 | 请求已发出、首个内容未到 |
| `THINKING` | 深度思考中 | 收到 extended thinking 分片 |
| `RESPONDING` | 回复中 | 首个正文 token 到达 |
| `USING_TOOL` | 调用工具 · 工具名 | 工具开始执行 |
| `WAITING_USER` | 待你确认 | 等待回答提问或权限确认 |

三个展示位：

- **`chat-header` 徽章**（`#session-activity`）—— 常驻可见，覆盖全程，
  忙碌时圆点带呼吸动画
- **空气泡占位符** —— 回答开始前跟随当前状态，不再恒显「思考中」
- **侧栏圆点** —— 忙碌时显示 activity 配色，空闲时回落到生命周期状态

典型序列（实测）：`思考中 → 调用工具 · TodoWrite → 思考中 → 回复中 → 空闲`

> `CALLING_MODEL` 之所以单列一态：「深度思考中」只在流式且开启 extended thinking
> 时可实时观测，阻塞路径下 thinking 内容随响应一起返回（模型早已答完），未开
> thinking 时更是整段等待期毫无信号。有了它，四种组合下界面都不会出现空白期。

## SSE 事件契约

前端 `sse-client.js` 监听以下 9 个事件，字段名由 hms-core 的 `HmsEvent` 定义：

| 事件 | 字段 |
|---|---|
| `token` | `token` |
| `tool_use` | `toolName`、`phase`（`START` / `PROGRESS` / `END`）、`input`、`result`（超 5000 字符截断） |
| `thinking` | `thinking`（超 2000 字符截断） |
| `activity` | `activity`（状态枚举名）、`label`（展示文案）、`detail`（如工具名，可为 null） |
| `ask_user` | `question`、`options` |
| `permission` | `toolName`、`description` |
| `compaction` | `layer`、`messagesBefore`、`messagesAfter`、`reason` |
| `complete` | `content`、`totalTokens`、`toolCallsCount`、`interrupted` |
| `error` | `message`、`code` |

> **这是前后端契约。** 修改 `HmsEvent` 的 record 组件名等于改动字段名，会破坏
> 前端（消费方见 `static/js/components/chat-panel.js`）。

> `compaction` 事件只在**自动**压缩时推送。手动压缩
> （`POST /api/sessions/{id}/compact`）的结果由 HTTP 响应同步返回，不走 SSE ——
> 压缩事件回调是请求级的，而手动压缩只允许在无请求执行时进行，此时回调指向的
> emitter 早已关闭。

> **`tool_use` 同一次调用会推送多次**，按 `phase` 分流：`START`（刚开始，
> `result` 为 null）→ 若干 `PROGRESS`（进度行）→ `END`（完成，带结果）。把每条都
> 当独立调用会让同一次调用渲染出多个气泡、用量统计翻几倍。前端做法见
> `chat-panel.js` 的 `tool_use` handler：START 建气泡、PROGRESS 追加、
> END 回填结果并计数。

> **空闲态不经 SSE 推送。** SSE 连接在 `complete` 之后即关闭，后端那条收尾的
> `IDLE` 事件已无接收端 —— `complete` / `error` 本身就是「回到空闲」的信号，
> 前端在 `onStreamEnd()` 里自置。侧栏其他会话的活动状态则来自
> `GET /api/sessions` 的 `activity` 字段（30 秒轮询）。

## 相关配置

```yaml
hms-core:
  # 等待用户回答（AI 提问 / 权限确认）的上限秒数。
  # 超时后按默认值处理：提问 → skip，权限 → deny。
  user-response-timeout-seconds: 300

  # 上下文窗口与预留 Token —— 该文件内有详细注释说明配错的后果。
  context-window: 200000
  reserved-tokens: 20000

  # Token 计费 —— 覆盖内置价目表（每百万 token 美元价）。
  # 键是模型名子串、大小写不敏感、长模式优先；三项须都填，缺项则整条作废并 warn。
  pricing:
    models:
      opus:
        input: 15.0
        output: 75.0
        cache-read: 1.5

  sse:
    # SSE 连接空闲超时（分钟）
    emitter-timeout-minutes: 30
```

## 运行测试

```bash
mvn test
```

25 个集成测试，覆盖 5 个 Controller 的 27 个端点。用 JDK 自带 `HttpClient` 打
真实 HTTP（Spring Boot 4 已移除 `@AutoConfigureMockMvc` 与 `TestRestTemplate`）。

注意 `chatSyncValidSession` 会真实调用 AI API，未配置有效 API Key 时该用例失败
属预期。`SessionCommandApiTests` 专测手动压缩、提示词端点与会话活动状态，
不发起 AI 调用。

## 接口冒烟测试

`api-test.mjs` 是黑盒端到端测试，**纯客户端**：只发 HTTP 请求，不启动/停止/构建
任何东西，因此不会打断你正在调试的实例。零依赖，只用 Node 20+ 内置 fetch。

```bash
# 先自己把应用跑起来，然后：
node demo-app/api-test.mjs

node demo-app/api-test.mjs --list              # 列出全部测试组
node demo-app/api-test.mjs --only compact      # 只跑一组
```

12 个测试组：`tool` `session` `contract` `permission` `chat` `toolcall` `compact`
`stream` `lifecycle` `concurrency` `interactive` `metrics`。

**退出码**：0 全部通过，非 0 为失败数。特例 —— 核心组（`chat` / `toolcall` /
`stream` / `compact` / `interactive`）因模型链路不可用而整组跳过时退出 **1**，
因为「零失败」此时不等于「验证通过」：agent 循环、工具调用、流式、压缩一个都没被
验证，打印「全部通过」会让 CI 变绿而掩盖问题。

### 计费契约验证

`verify-pricing.mjs` 专验 Token 计费的 JSON 契约 —— 单元测试证明不了序列化层的
问题：`BigDecimal` 会不会变成字符串、`null` 会不会让 `Map.of` 抛 500、record 的
派生方法会不会意外进 JSON。

```bash
# 费率须与运行实例生效的 hms-core.pricing.models.* 一致
node demo-app/verify-pricing.mjs http://localhost:8088 --rate=10,65,1.2
```

`--rate` 用于交叉核对：脚本按它手算费用再与服务端比对，据此确认「配置覆盖真的
生效」。**不要把费率写死成内置默认值** —— 那样配置生效时反而会报失败（实测踩过：
dev 配了 10/65/1.2，脚本按内置 15/75/1.5 手算，于是判定不一致，而服务端其实是对的）。

> Windows 上若报「无响应（HTTP 0）」而 curl 却能连通，用
> `BASE_URL=http://127.0.0.1:8088` 再跑一次 —— Node 的 fetch 会把 `localhost`
> 解析成 IPv6 `::1`。

---

<div align="center">

[HMS Core](../hms-core/README.zh-CN.md) 项目的一部分 · [Apache License 2.0](../LICENSE)

</div>
