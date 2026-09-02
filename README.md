# HMS Core — AI Agent SDK for Java

> **让 Java 应用一键接入 AI 自主迭代范式能力** — 一个依赖、一次扫描、几行代码，即可获得完整的多轮 AI 对话、工具调用、权限管控与多会话隔离能力。

---

## 🚀 为什么选 HMS Core（低成本集成）

- **① 一个依赖搞定** — 引入 `hms-core` jar，Spring Boot 自动装配全部 Bean，无需手写任何配置类
- **② 开箱即用** — 内置 **20 个工具**（Web 搜索/抓取、任务管理、子 Agent、MCP、Skill…）+ 完整权限体系
- **③ 三行代码跑通** — 注入 `HmsSessionManager`，调用 `createSession()` → `send()`，对话即完成
- **④ 可选多语言** — 内置提示词为中文；开启 `hms-core.i18n.enabled` 后自动检测系统语言并用大模型翻译（默认关闭：翻译在启动时同步进行，会阻塞数十秒）
- **⑤ 天然适合 Web** — 内置 `HmsSseBridge`，SSE 流式对话**一行接入**（含交互式提问与权限弹窗）；换 WebSocket / 消息队列也只需写一个事件 sink
- **⑥ 可无限扩展** — 自定义工具、Hook 钩子、权限规则、风险检测器，按 Spring Bean 注入

---


## 📦 快速集成（3 分钟跑通）


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
  provider: ${HMS_CORE_PROVIDER:openai}  #anthropic

  # 上下文窗口须与所用模型的真实窗口一致：配小了会过早压缩（白花摘要费用还丢
  # 上下文），配大了压缩来不及（请求超限被上游拒绝）。
  # reserved-tokens 留给模型输出与压缩摘要本身，必须显著小于窗口。
  context-window: 200000
  reserved-tokens: 20000
```

### 三行代码实现 AI 对话

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

> 💡 想先体验？仓库自带可视化演示应用（Web 界面 + SSE），在 `demo-app/` 目录运行 `mvn spring-boot:run` 即可，地址 `http://localhost:8088`。界面支持 slash 命令，输入 `/` 查看。
>
> 🔍 想验证接口是否跑通？先启动应用，再跑 `node demo-app/api-test.mjs`（Node 20+，零依赖）。


---

## ✨ 开箱即用能力

### 🤖 AI Agent 引擎
- **Agent Loop** — 完整 Agent 循环（阻塞 + 流式双模式），多轮对话 + 工具调用 + 自动回传
- **Token 追踪** — 输入/输出 Token 实时统计、上下文窗口使用率监控
- **三层上下文压缩** — 微压缩（本地截断，0 API 调用）→ Session Memory（AI 摘要）→ 全量压缩，93% 阈值自动触发，熔断保护
- **手动压缩** — `compactNow(sessionId)` 绕过阈值与熔断，直接全量压缩并返回 `CompactionResult`
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

> 工具描述均为优化后的中文（开启 `hms-core.i18n.enabled` 后可自动翻译为系统语言）。自定义工具同样遵循同一套协议。

### 🔒 安全与权限
- **5 级权限模式** — `STRICT` / `SAFE` / `DEFAULT` / `TRUSTED` / `BYPASS`
- **8 步规则评估链** — 模式检查 → ToolContext 覆盖 → 风险等级 → 规则匹配 → 风险检测 → 用户确认
- **可扩展风险检测** — `RiskDetector` 接口注入场景特定安全检查
- **拒绝追踪** — 连续拒绝自动降级，防止 AI 反复试探

### 🔌 集成能力
- **MCP 协议** — 一键连接外部 MCP 服务器（StdIO / HTTP SSE），工具自动注册
- **Hook 系统** — 工具调用前后插入自定义逻辑（`PRE_TOOL_USE` 阻止执行或改写入参、`POST_TOOL_USE` 改写结果）
- **指标收集** — 消息数、工具使用、API 调用、Token 用量、错误类型统计


### 一次请求的核心流程

```
HmsSessionManager.send(sessionId, message)
    │
    ├── 会话状态检查 + 输入校验
    ├── AgentLoop.run(message)
    │       ├── 追加 UserMessage
    │       ├── while (迭代 < max-iterations):
    │       │       ├── ChatModel.call(prompt) → AI 回复
    │       │       ├── 有 tool_calls？
    │       │       │       ├── Hook + 权限评估（8 步链）
    │       │       │       ├── 执行工具 → 结果回传
    │       │       │       ├── 上下文超阈值？→ 三层压缩
    │       │       │       └── 继续下一轮
    │       │       └── 无 tool_calls
    │       │               ├── 上下文超阈值？→ 三层压缩
    │       │               └── 结束
    └── 回调 onComplete(response)
```

---

---

## 📄 License

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
