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
│   │   ├── SessionController.java      # 会话管理 API
│   │   ├── ChatController.java         # 对话 API（同步 + SSE 流式）
│   │   ├── ToolController.java         # 工具管理 API
│   │   ├── PermissionController.java   # 权限管理 API
│   │   └── MetricsController.java      # 指标查询 API
│   └── model/                          # DTO（ApiResponse / ChatRequest / ...）
├── src/main/resources/
│   ├── application.yml                 # 应用配置
│   └── static/                         # 前端 SPA
│       ├── index.html
│       ├── css/
│       └── js/
│           ├── api.js                  # REST 封装
│           ├── sse-client.js           # EventSource 封装
│           └── components/             # chat-panel / permission-modal / ...
└── src/test/java/                      # 46 个接口集成测试
```

> 说明：早期版本在 `service/SessionBridgeService.java` 里手写了 272 行 SSE 桥接代码，现已全部下沉到 hms-core，该文件与 `service/` 包已删除。

## API 概览

| 模块 | 路径 | 说明 |
|------|------|------|
| 会话 | `POST/GET/DELETE /api/sessions` | 创建/查询/销毁/暂停/恢复/取消 |
| 对话 | `POST /api/chat/{id}` | 同步对话 |
| 流式 | `GET /api/chat/{id}/stream?message=` | SSE 流式对话（EventSource 直连） |
| 回答 | `POST /api/chat/{id}/ask-response` | 提交 AI 提问的回答 |
| 权限 | `POST /api/chat/{id}/permission-response` | 提交权限确认（`allow` / `deny`） |
| 工具 | `GET /api/tools` | 工具列表/管理 |
| 权限配置 | `GET/PUT/POST /api/permissions` | 权限模式/规则 |
| 指标 | `GET /api/metrics/{id}` | Token 统计/仪表盘 |

## SSE 事件契约

前端 `sse-client.js` 监听以下 7 个事件，字段名由 hms-core 的 `HmsEvent` 定义：

| 事件 | 字段 |
|------|------|
| `token` | `token` |
| `tool_use` | `toolName`、`input`、`result`（超 5000 字符截断） |
| `thinking` | `thinking`（超 2000 字符截断） |
| `ask_user` | `question`、`options` |
| `permission` | `toolName`、`description` |
| `complete` | `content`、`totalTokens`、`toolCallsCount`、`interrupted` |
| `error` | `message` |

> ⚠️ 这是前后端契约。修改 `HmsEvent` 的 record 组件名等于改动字段名，会破坏前端（消费方见 `static/js/components/chat-panel.js`）。

## 相关配置

```yaml
hms-core:
  # 等待用户回答（AI 提问 / 权限确认）的上限秒数
  # 超时后按默认值处理：提问 → skip，权限 → deny
  user-response-timeout-seconds: 300
  sse:
    # SSE 连接空闲超时（分钟）
    emitter-timeout-minutes: 30
```

## 运行测试

```bash
mvn test
```

覆盖 5 个 Controller 的 25+ 端点。注意：`chatSyncValidSession` 会真实调用 AI API，未配置有效 API Key 时该用例失败属预期。
