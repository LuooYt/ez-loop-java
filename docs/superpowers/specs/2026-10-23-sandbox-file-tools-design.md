# 会话沙箱与文件类工具设计

- 日期：2026-10-23
- 状态：已批准，待实现
- 参考实现：`C:\Users\Administrator\Downloads\claude-code-main\claude-code-main`

## 1. 目标

为 hms-core 补齐参考项目中缺失的内置工具，并引入**会话级文件沙箱**：

- 通过 yml 配置一个沙箱根目录；
- 每个会话在根目录下拥有独立的空工作目录；
- 所有文件操作工具只能访问本会话的沙箱目录；
- 沙箱是**操作系统文件系统上的真实目录**，Bash 子进程可直接以它为 cwd 操作同一批文件。

非目标：

- 不实现操作系统级隔离（容器、namespace、只读根）；
- 不保证 Bash 的安全边界（见 3.2）；
- 不复制宿主项目到沙箱，沙箱初始为空；
- 不移植 `Brief` 工具（参考实现中它只改状态位，模型不消费，属于空壳）。

## 2. 范围

本次新增 12 个工具：

| 分组 | 工具 | 风险等级 |
|---|---|---|
| 文件 | `Read` | READ_ONLY |
| 文件 | `Write` | MEDIUM |
| 文件 | `Edit` | MEDIUM |
| 文件 | `Glob` | READ_ONLY |
| 文件 | `Grep` | READ_ONLY |
| 文件 | `ListFiles` | READ_ONLY |
| 执行 | `Bash` | HIGH |
| 笔记 | `NotebookEdit` | MEDIUM |
| IDE | `LSP` | READ_ONLY |
| Git | `EnterWorktree` | HIGH |
| Git | `ExitWorktree` | MEDIUM |
| 通知 | `Notification` | READ_ONLY |

配套移植的基础设施：

- `ProcessExecutor`（Bash / Notification / LSP 共用）；
- LSP 子系统：`LSPClient`、`LSPServerConfig`、`LSPServerInstance`、`LSPServerManager`、`LSPDiagnosticRegistry`；
- `WorktreeManager`。

## 3. 沙箱模型

### 3.1 目录布局

```text
<hms-core.sandbox.root>/
└── <sessionId>/
    ├── ...                    工具创建与读取的工作文件
    └── worktrees/<slug>/      EnterWorktree 创建的工作树
```

- 会话创建时新建空目录，不复制宿主文件。
- 子 Agent、群聊会话、跨 Agent 委派的一次性会话都是独立会话，各自拥有沙箱目录。
- 会话销毁或空闲回收时按配置决定是否删除目录。

### 3.2 安全边界

文件类工具（Read/Write/Edit/Glob/Grep/ListFiles/NotebookEdit）是**硬边界**，全部经过 `Sandbox.resolve`：

1. 拒绝绝对路径（含 Windows 盘符 `C:\...` 与 UNC 路径），只接受相对路径；
2. 基于当前逻辑工作目录 `resolve`，随后 `normalize`；
3. 词法检查：结果必须以沙箱根开头；
4. 符号链接检查：
   - 目标已存在：`toRealPath()` 后再次检查前缀；
   - 目标不存在：从最近的已存在祖先取 `toRealPath()`，拼回剩余路径段后检查前缀；
5. 读取、写入、列目录、遍历匹配使用同一套校验；
6. 读取受 `max-file-bytes` 限制；Glob/Grep 单次遍历最多 100000 个节点、最多返回 1000 条结果，防止大目录耗尽资源；
7. Glob/Grep/ListFiles 遍历时**不跟随符号链接**，符号链接项本身跳过。

`Bash` 只做**缓解**，不是安全边界：

- 子进程 cwd 固定为会话沙箱目录；
- 超时与输出长度可配置；
- 复用 `DangerousPatterns` 做危险命令审查；
- 无法阻止 `cd /`、绝对路径或脚本内部的越界访问。工具描述与 README 必须明确说明这一点。

`LSP` 的 workspaceRoot 固定为会话沙箱；未配置语言服务器时不注册该工具。

`Worktree` 只在会话沙箱内操作：要求沙箱根是一个 git 仓库，工作树创建在 `<sandbox>/worktrees/<slug>`，不访问宿主机其他仓库。

## 4. 配置

新增 `SandboxProperties`，前缀 `hms-core.sandbox`：

```yaml
hms-core:
  sandbox:
    enabled: true
    root: ./sandbox
    cleanup-on-destroy: false
    max-file-bytes: 10485760
    bash:
      timeout-seconds: 120
      max-output-chars: 40000
    lsp:
      servers:
        java:
          command: jdtls
          args: []
          extensions:
            ".java": java
          env: {}
          startup-timeout: 30000
    notification:
      system-command: true
```

字段说明：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | 沙箱开关 |
| `root` | 空 | 沙箱根目录，必须为绝对或可解析的真实路径 |
| `cleanup-on-destroy` | `false` | 会话销毁时是否递归删除沙箱目录 |
| `max-file-bytes` | 10 MiB | 单个文件读取上限 |
| `bash.timeout-seconds` | 120 | Bash 命令超时 |
| `bash.max-output-chars` | 40000 | Bash 输出截断阈值 |
| `lsp.servers` | 空 | 语言服务器映射；为空则不注册 LSP |
| `notification.system-command` | `true` | 无 SDK 通知回调时是否调用系统通知命令 |

启用判定：`enabled=true` 且 `root` 非空。不满足时启动日志 warn，并且**不注册**全部路径类工具：

`Read`、`Write`、`Edit`、`Glob`、`Grep`、`ListFiles`、`Bash`、`NotebookEdit`、`LSP`、`EnterWorktree`、`ExitWorktree`。

`Notification` 与文件系统无关，始终注册。

启动时创建根目录；若创建失败（权限、磁盘等），视为沙箱未生效：记录 error，不注册路径类工具。

## 5. 组件设计

### 5.1 Sandbox

包：`com.inspirationi.loop.sandbox`

职责：

- 持有会话沙箱根的真实路径；
- `resolve(String path)` 完成第 3.2 节的校验，失败抛 `SandboxViolationException`；
- 维护逻辑当前目录 `currentDir()`，供 EnterWorktree/ExitWorktree 切换；默认等于沙箱根；
- `toRelativeDisplay(Path)` 把绝对路径转成对模型友好的沙箱相对路径；
- 通过 `ToolContext` 的共享 state 传递（key 常量），子上下文自动回落继承。

### 5.2 SandboxManager

Spring Bean，职责：

- `Sandbox create(String sessionId)`：创建 `root/<sessionId>`（含父目录），设置 POSIX 权限 0700（Windows 忽略）；
- `Optional<Sandbox> get(String sessionId)`；
- `void destroy(String sessionId)`：按 `cleanup-on-destroy` 决定删除；删除前校验目标路径确实位于根内且目录名与会话 id 一致；
- `boolean enabled()`。

目录命名使用会话 id（UUID）；实现仍对 id 做字符白名单校验（字母、数字、`-`、`_`），拒绝其他字符，防止路径拼接注入。

### 5.3 ToolContext 集成

- 不修改 `ToolContext` 构造签名；
- 会话创建时 `toolContext.set(Sandbox.STATE_KEY, sandbox)`；
- 工具通过 `Sandbox.from(context)` 获取；取不到时返回明确错误文本，而不是 NPE。

### 5.4 会话生命周期

| 位置 | 改动 |
|---|---|
| `DefaultHmsSessionManager.doCreateSession` | 创建沙箱并写入会话上下文 |
| 子 Agent 工厂（同一会话内派生） | 传入主会话的同一个 `Sandbox`，子 Agent 与主会话共享沙箱 |
| 会话数超限回滚 | 删除刚创建的沙箱目录（无论 cleanup 配置如何，空目录都应清理） |
| `destroySession` | 调用 `SandboxManager.destroy` |
| `destroyIfExists` | 调用 `SandboxManager.destroy` |
| `cleanupIdleSessions` | 复用销毁路径，自动获得清理行为 |
| 会话销毁 | 同时关闭该会话的 LSP 管理器（若已创建） |
| `close()` | 逐个销毁会话时同样调用 `SandboxManager.destroy`，释放沙箱资源与 LSP 进程 |
| `AgentRuntimeConfig` / `AgentRuntimeFactory` | 仅透传 `SandboxManager` / `SandboxProperties` 给 `DefaultHmsSessionManager.Builder`；Agent 的所有会话仍统一经该类创建 |

### 5.5 工具行为

#### Read

- 输入：`file_path`（相对）、可选 `line_start`、`line_end`；
- 文本按 UTF-8 读取，输出行号前缀，单次最多 2000 行；
- 图片扩展名（png/jpg/jpeg/gif/webp/svg/bmp/ico）返回 MIME 与 Base64，单张上限 20 MiB；
- 超过 `max-file-bytes` 的文本文件拒绝读取并提示分段。

#### Write

- 输入：`file_path`、`content`；
- 自动创建沙箱内父目录；
- 覆盖已存在文件；返回创建/更新状态与行数。

#### Edit

- 输入：`file_path`、`old_string`、`new_string`；
- `old_string` 必须恰好匹配一处，零处或多处都返回错误；
- 返回 unified diff 摘要。

#### Glob

- 输入：`pattern`、可选 `path`（相对目录，默认沙箱根）；
- 使用 `PathMatcher` 匹配，跳过符号链接目录（不跟随）；
- 结果按修改时间倒序，最多返回 1000 条。

#### Grep

- 输入：`pattern`（正则）、可选 `path`、`glob`、`case_insensitive`；
- 跳过二进制文件与 `node_modules`、`.git`、`target` 等目录；
- 单个文件超过 `max-file-bytes` 跳过；
- 返回 `文件:行号:内容`，结果上限 1000 行；
- 正则非法时返回参数错误。

#### ListFiles

- 输入：可选 `path`（默认沙箱根）；
- 仅为目录，输出子项类型、大小、修改时间；不跟随符号链接。

#### Bash

- 输入：`command`、可选 `timeout_seconds`（不超过配置上限）、可选 `description`；
- 使用 `ProcessExecutor`，cwd 为沙箱当前目录；
- 合并 stderr 到输出，追加退出码；
- 输出按 `max-output-chars` 截断并标注；
- 超时杀进程树；
- 工具描述明确「非安全边界」。

#### NotebookEdit

- 输入：`notebook_path`、`cell_number`、`new_source`、可选 `cell_type`、`edit_mode`（replace/insert/delete）；
- 使用 Jackson 读写 `.ipynb`，保持其他单元格原样；
- 只接受沙箱内路径，扩展名必须为 `.ipynb`。

#### LSP

- 输入：`operation`（goToDefinition/findReferences/hover/documentSymbols）、`file_path`、`line`、`character`；
- 每个会话一个 `LSPServerManager`，workspaceRoot 为会话沙箱；
- 服务器按扩展名路由，首次使用时延迟启动；
- 未配置对应扩展名的服务器时返回可读错误；
- 应用关闭或会话销毁时关闭管理器。

#### EnterWorktree / ExitWorktree

- `EnterWorktree`：沙箱根必须是 git 仓库；在 `<sandbox>/worktrees/<slug>` 创建或恢复工作树；成功后 `Sandbox.currentDir` 切换到工作树；同一会话重复进入返回错误；
- `ExitWorktree`：恢复 `currentDir` 到沙箱根；可选删除工作树（`action=remove`）；
- 不访问沙箱外的 git 仓库。

#### Notification

- 输入：`title`、`message`、可选 `level`、`sound`；
- 优先使用 `ToolContext` 中可注入的 `NotificationSink`
  （接口位于 `com.inspirationi.loop.tool`，SDK 调用方通过
  `toolContext.set(NotificationSink.CONTEXT_KEY, sink)` 注入）；
- 未注入且 `system-command=true` 时，按平台调用：
  - Windows：PowerShell Toast 或 `msg`；
  - macOS：`osascript`；
  - Linux：`notify-send`；
- 全部失败时返回错误文本，不抛异常。

### 5.6 权限

- 工具通过 `riskLevel()` 声明风险等级（见第 2 节）；
- `ToolConfiguration` 为 `Bash` 注册命令提取器：
  `permissionRuleEngine.addCommandExtractor("Bash", "command")`；
- 文件工具默认按风险等级由 `PermissionRuleEngine` 评估；
- DEFAULT 模式下 MEDIUM 自动放行、HIGH 触发 ASK，与现有 demo 权限弹窗一致。

### 5.7 工具注册

`ToolConfiguration` 注入 `SandboxProperties`（可用性判断用 `isUsable()`，不额外依赖 `SandboxManager`）：

- 沙箱启用时注册 11 个路径类工具；
- `LSP` 额外要求 `lsp.servers` 非空；
- `Notification` 始终注册；
- 全局 `ToolContext` 不写入会话沙箱；沙箱只存在于会话上下文。

## 6. 错误处理

统一约定：工具返回面向模型的可读错误文本，不抛出未捕获异常。

- 路径越界：`Error: Path escapes the session sandbox. Use a relative path inside the sandbox.`
- 绝对路径：`Error: Absolute paths are not allowed. Use a path relative to the sandbox.`
- 沙箱缺失：`Error: Sandbox is not configured for this session.`
- 文件不存在、非普通文件、过大、正则非法、命令超时等均给出具体原因。
- `SandboxViolationException` 由工具捕获并转成上述文本。

## 7. 测试

全部按 TDD：先写失败测试，再实现。

### 7.1 沙箱路径

`SandboxPathTest`：

- 正常相对路径解析；
- `../`、`..\\`、多层穿越；
- 绝对路径（Unix 与 Windows 盘符、UNC）；
- 前缀相似目录（`root/s1` 不得匹配 `root/s10`）；
- 符号链接文件/目录逃逸（无创建权限时 `Assumptions.assumeTrue` 跳过）；
- 不存在的目标经符号链接父目录写入逃逸；
- 非法会话 id 被拒绝。

### 7.2 工具

每个工具至少覆盖：正常路径、越界拒绝、参数缺失/类型错误、大小或数量上限。

### 7.3 生命周期与注册

- 创建会话生成目录；
- `cleanup-on-destroy=false` 保留，`true` 删除；
- 会话数超限回滚不残留目录；
- 空闲回收走同一清理路径；
- 沙箱未启用时路径类工具不在注册表；
- `LSP` 未配置服务器时不注册。

### 7.4 权限

- `Bash` 在 DEFAULT 模式触发 ASK；
- `Read` 在 STRICT 模式放行；
- `Write` 在 SAFE 模式触发 ASK（`PermissionRuleEngine` 对未自动放行的高风险调用返回 ASK，不是 deny）。

## 8. 文档与配置示例

- `demo-app/src/main/resources/application.yml` 增加沙箱配置示例（不含密钥）；
- `application-prod.yml` 增加生产环境变量示例；
- `README.md` 中英双语补充：沙箱模型、配置项、12 个工具、Bash 边界说明、LSP 依赖本地语言服务器。

## 9. 实现顺序

1. 沙箱核心：`SandboxProperties`、`Sandbox`、`SandboxManager`、路径测试；
2. 文件与检索 6 个工具；
3. `ProcessExecutor`、`Bash`、`NotebookEdit`；
4. `DefaultHmsSessionManager` 生命周期接线与注册条件；
5. LSP 子系统与 `LSPTool`；
6. `WorktreeManager`、`EnterWorktree`、`ExitWorktree`；
7. `NotificationSink` 与 `Notification`；
8. 配置示例、README、全量回归。

## 10. 已知限制

- Bash 不是安全边界；恶意命令仍可访问沙箱外文件。
- LSP 需要部署环境安装对应语言服务器，工具只负责启动与通信。
- Windows 下符号链接创建需要额外权限，相关测试可能跳过。
- `cleanup-on-destroy` 默认关闭，长期运行需运维清理磁盘。
- 沙箱为空目录，agent 不能直接读取宿主项目；需要时由调用方自行把文件放入会话沙箱。
