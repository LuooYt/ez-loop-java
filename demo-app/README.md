# HMS Web Demo

基于 HMS Core SDK 的 AI Agent Web 控制台。

## 快速开始

### 前置条件

- JDK 25（配置 `JAVA_HOME`）
- Maven 3.9+
- API Key（Anthropic 或 OpenAI 兼容服务）
- hms-core-0.2.0-SNAPSHOT.jar 已安装到本地 Maven 仓库

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

### 启动

```bash
cd D:/idaeProject/hms-web-demo/app
mvn spring-boot:run
```

访问 http://localhost:8080

## 项目结构

```
app/
├── pom.xml
├── README.md
├── src/main/java/com/inspirationi/hmsweb/
│   ├── HmsWebDemoApplication.java     # Spring Boot 启动类
│   ├── config/WebMvcConfig.java       # CORS + 静态资源配置
│   ├── controller/
│   │   ├── SessionController.java      # 会话管理 API
│   │   ├── ChatController.java        # 对话 API（SSE 流式）
│   │   ├── ToolController.java        # 工具管理 API
│   │   ├── PermissionController.java  # 权限管理 API
│   │   └── MetricsController.java     # 指标查询 API
│   ├── model/                         # DTO 模型
│   └── service/
│       └── SessionBridgeService.java  # SSE 桥接 + 异步回调管理
├── src/main/resources/
│   ├── application.yml                # 应用配置
│   └── static/                        # 前端 SPA
│       ├── index.html
│       ├── css/
│       └── js/
```

## API 概览

| 模块 | 路径 | 说明 |
|------|------|------|
| 会话 | `/api/sessions` | 创建/查询/销毁/暂停/恢复 |
| 对话 | `/api/chat/{id}` | 同步对话 |
| 流式 | `/api/chat/{id}/stream` | SSE 流式对话 |
| 工具 | `/api/tools` | 工具列表/管理 |
| 权限 | `/api/permissions` | 权限模式/规则 |
| 指标 | `/api/metrics` | Token 统计/仪表盘 |
