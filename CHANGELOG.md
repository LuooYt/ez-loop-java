# 变更日志

本文件记录对使用者可见的行为变更，按「轮次」组织。每一轮都可独立发布与回滚，
对应的 git tag 见每节标题。

版本号沿用 `hms-core/pom.xml` 的 `<version>`；未发布前的改动归入「未发布」。

---

## 未发布

### 第 1 轮：止血（tag `iter-1`）

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

---

### 第 2 轮：稳定性（tag `iter-2`）

> tag 基线：`pre-iter-2`

本轮修「并发下会串台、断线后不自愈、失败即丢数据」的问题。无破坏性变更。

#### 修复

- **SSE 事件串台**。事件原先按 `sessionId` 现查发射器，用户刷新页面后，
  上一轮仍在执行的事件（包括它的权限确认请求）会推到新连接上；旧一轮收尾
  还会清掉新一轮正在等待的用户回答。现在每轮执行绑定自己的连接，被顶替后
  事件直接丢弃、也不再清理别人的等待请求；线程池拒绝执行（停机中）时补一条
  error 并当场收尾，不再让连接挂到超时。
- **MCP SSE 传输层的监听循环**。循环条件曾是「已连接」，而该标志要等握手
  轮询醒来才置位 —— 服务端紧跟着推来的事件会被挡在门外，此后传输层再也收不到
  响应；断线后 `connected` 仍为 true，请求各自干等满超时且永不重连。现在循环只看
  「是否主动关闭」，断线统一清空状态、让等待方立刻失败，下一次请求自动重连。
- **心跳失败丢工作项**。工作项 poll 出来后就调 `send`，异常被吞掉，工作项随之
  蒸发；同时「有工作项」被当成产出，退避永不生效。现在失败的工作项放回队首并
  累加尝试次数（3 次后丢弃并记 error），失败计入空闲退避。
- **清理过期 worktree 误清当前会话**。删除任意 worktree 都会清空
  `currentSession`，定时清理删掉一个无关条目后，当前会话的工作树再没人收尾。
- **TaskManager 内存只增不减**。已结束任务的 Future 与记录都不回收。

#### 测试

- 新增 `SseEmitterIsolationTest`（4）、`HttpSseTransportTest`（3），
  扩充 `AgentHeartbeatTest`（+2）、`WorktreeManagerTest`（+1）、
  `TaskManagerStateTest`（+3）。
- `hms-core` 601 个测试、`demo-app` 29 个测试全绿。
