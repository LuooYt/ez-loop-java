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

### 配置 API Key

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

方式二：`application.yml`

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

> 💡 想先体验？仓库自带可视化演示应用（Web 界面 + SSE），在 `app/` 目录运行 `mvn spring-boot:run` 即可，地址 `http://localhost:8088`。


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
