# 变更日志

本文件记录对使用者可见的行为变更，按「轮次」组织。每一轮都可独立发布与回滚，
对应的 git tag 见每节标题。

版本号沿用 `hms-core/pom.xml` 的 `<version>`；未发布前的改动归入「未发布」。

---

## 未发布 —— 第 1 轮：止血

> tag 基线：`pre-iter-0`

本轮只修「会让会话卡死、权限被绕过、工具撒谎」的缺陷，不改变整体架构。

### 破坏性变更

- **`BYPASS` 不再覆盖显式 `DENY`**。权限判定顺序改为
  `DENY → BYPASS/STRICT → 风险自动放行 → ALLOW 规则 → 风险检测 → 用户确认`。
  依赖「BYPASS 能越过 DENY」的用法需要调整：BYPASS 现在只跳过确认，不取消拒绝。
- **`SendMessage` 从默认工具集中移除**。该类保留并标注 `@Deprecated(forRemoval)`，
  直接调用会返回明确错误。后台任务的任务体是普通 `Callable`，没有收件箱，
  过去的「Message queued」是虚假回执。替代做法：`TaskStop` 取消任务，
  或新建任务携带额外上下文。

### 修复

- **压缩会破坏 tool 配对，导致上游 400、会话卡死**。`FullCompact` 与
  `SessionMemoryCompact` 现在统一用 `HistoryInvariants.alignKeepStart` 对齐保留段
  边界，不会把 `tool_use` 切掉只留下 `tool_result`；`AutoCompactManager` 在写回
  历史前调用 `HistoryInvariants.verify`，任何一层产出的非法历史都会被拒绝而不是
  写进会话。
- **`Bash(ls:*)` 会匹配 `lsfoo`**。前缀通配改为词边界匹配：命令必须恰好等于前缀，
  或前缀后紧跟空白。
- **「始终允许」把危险命令持久化**。`rm`、`sudo`、`curl`、`git` 等命令的
  ALWAYS_ALLOW 降级为**仅本会话**的内存规则；Shell 类工具的工具级放行直接丢弃。
  安全命令仍可持久化。
- **demo 无鉴权却全裸暴露**。默认只监听 `127.0.0.1`；CORS 白名单收敛到本机
  且关闭 `allowCredentials`；切 `BYPASS` 需 `hms.demo.allow-bypass=true`；
  API 拒绝为 Shell 工具写入危险 ALLOW 规则。容器部署需显式设置
  `SERVER_ADDRESS=0.0.0.0`（`deploy/docker-compose.yml` 已设置）。

### 文档

- README 权限章节补充新的判定顺序，并注明「BYPASS 只跳过确认，不取消显式 DENY」。
- README 与 `demo-app/README*` 增加醒目警告：demo 无鉴权，仅限本机演示。

### 测试

- 新增 `FullCompactPairingTest`（9）、`PermissionRuleMatchingTest`（10）、
  `SendMessageToolRegistrationTest`（4），并改写 `DenyRuleOrderingTest` 与
  `ApiErrorHandlingTests` 中锁定旧（不安全）行为的用例。
- `hms-core` 588 个测试、`demo-app` 29 个测试全绿。
