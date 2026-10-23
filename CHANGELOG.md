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

---

### 第 3 轮：资源与可用性（tag `iter-3`）

> tag 基线：`pre-iter-3`

本轮处理「一个参数就能把进程拖垮」的资源问题，以及若干让工具对模型说假话的语义缺陷。
无破坏性变更。

#### 修复

- **超长 glob 的指数级展开**。`**/` 的「零或多级」语义靠枚举变体实现，每多一段
  翻倍 —— 20 段就是 100 万个 `PathMatcher`，且爆炸发生在遍历开始之前。现在
  `**/` 最多 6 段、变体最多 64 个，超限返回可操作的参数错误。`Glob` 与 `Grep`
  的 `glob` 参数共用同一道检查。
- **Grep 的灾难性回溯（ReDoS）**。`(a+)+$` 一类正则对超长不匹配输入是指数级
  回溯，而 Java 正则没有超时机制。现在正则文本上限 1000 字符、启发式拒绝
  「被量词修饰的分组内部还含 `+`/`*`」的结构，且遍历与逐行匹配共享 10 秒预算，
  超时按「结果截断」标注。残余风险（单次 `matcher.find()` 已陷入回溯时无法中断）
  已在代码注释中记录。
- **WebFetch 会把整个响应体读进内存**。原先用 `BodyHandlers.ofString()`，100KB
  的上限只在读完以后才生效。现在按字节有界读取（`min(maxLength*4, 2MB)`），
  按 `Content-Type` 的 charset 解码，确实被截断时显式标注；`maxLength` 钳制在
  `[1, 200000]`。参数解析改用 `ToolValidator`，模型传字符串形式的数字/布尔不再
  抛 `ClassCastException`。SSRF 判定补上 `100.64.0.0/10`（CGNAT）与 `fc00::/7`
  （IPv6 ULA），并在每一跳发请求前重新解析地址。
- **语言服务器看不到写后的新内容**。`Write`/`Edit` 写盘后不再同步，模型
  「改文件 → 查 diagnostics」拿到的是修改前的快照。现在写盘成功后 best-effort
  推 `didOpen`/`didChange` + `didSave`，失败只记 debug。同步只针对**已在运行**的
  服务器，不会因为写了个文件就懒启动语言服务器。
- **LSP 诊断超限即丢弃**。超过每文件 10 条 / 总计 30 条的部分既没返回也没留下。
  现在超出部分退回队列、下一轮继续；「已交付」标记也推迟到内容确实交出去之后
  （新增 `commitDelivered`）。
- **空助手消息写入历史**。模型返回空内容时仍把这条 assistant 加进历史，之后每次
  请求都会带上它，上游判为非法消息直接 400。现在无文本且无工具调用时不再入历史。
- **MicroCompact 的空闲策略是死代码**。活跃时间记在压缩检查开头，而检查由模型
  响应驱动 —— 间隔永远是 0。改为在请求入口记录。
- **通知超时被当成「环境没有通知服务」**。`exitCode == -1` 同时表示启动失败、
  超时和线程中断，原先一律返回「do not retry」。现在超时单独上报。
- **搜索「无结果」被报成故障**。所有后端都正常返回空时仍抛异常，模型据此反复换
  关键词重试；现在返回空列表，由 `WebSearch` 输出「No results found」。
- **`Edit` 不读 `max-file-bytes` 配置**。注册时用了无参构造器，该配置对它完全无效。
- **重申的记忆在淘汰时仍最先被丢**。`LinkedHashMap` 覆盖已有 key 不改顺序，
  重复 `remember` 不会把条目挪到「最新」端。
- **MICRO 压缩事件的一次性提示额度被白白消耗**。「已提示」标记在判断回调是否
  存在之前就置位，导致回调晚些注册时永远收不到该事件。

#### 测试

- 新增 `WebFetchBoundedReadTest`、`LspWriteSyncTest`、`LSPDiagnosticRegistryTest`、
  `MicroCompactTest`、`MicroEventThrottleTest`，扩充 `GrepToolTest`（+3）、
  `GlobListFilesToolTest`（+2）、`WebFetchSsrfTest`（+2）、`EmptyResponseTest`（+1）、
  `NotificationToolTest`（+1）、`FallbackSearchProviderTest`（+2）、
  `SandboxToolRegistrationTest`（+1）、`AgentMemoryTest`（+2），共 +35。
- `hms-core` 636 个测试、`demo-app` 29 个测试全绿。

---

### 第 4 轮：架构收敛（tag `iter-4`）

> tag 基线：`pre-iter-4`

本轮不做新功能，把前几轮补丁留下的「同一件事有三种说法」收敛成一处：权限顺序、
配置装配、参数校验、前端渲染各自只有一个入口。

#### 破坏性变更

- **权限判定顺序固定为九步**，`ASK` 规则现在真的生效：

  | # | 条件 | 结果 |
  |---|---|---|
  | 1 | `ToolContext` 的模式覆盖 | 替换当前模式 |
  | 2 | 显式 `DENY` 规则 | `DENY` |
  | 3 | `STRICT` 模式 | 只读 `ALLOW`，其余 `DENY` |
  | 4 | 显式 `ASK` 规则 | `ASK` |
  | 5 | `BYPASS` 模式 | `ALLOW` |
  | 6 | 风险等级在模式阈值内 | `ALLOW` |
  | 7 | 显式 `ALLOW` 规则 | `ALLOW` |
  | 8 | `RiskDetector` 检出风险 | `ASK` |
  | 9 | 默认 | `ASK` |

  相对第 1 轮的顺序，变化是 **`ASK` 规则插到了 `BYPASS` / 风险自动放行之前**。
  在此之前 `PermissionSettings` 会收集 ASK 规则并让 `getAllRules()` 返回它，但
  `evaluate` 只查 DENY 与 ALLOW —— 一个声明了却永不生效的行为类型。若某个集成方
  依赖「BYPASS 会越过 ASK 规则」，需要改为删除该规则或调整模式。
- **`BYPASS` 的语义收窄为「不再逐次确认」**：显式 `DENY` 仍然拒绝，显式 `ASK`
  仍然要确认。此语义自第 1 轮起已生效，本轮把它写进文档并与实现逐条对齐。
- **单提供商不再阻断启动**。配置的 provider 不可用但另一个可用时，启动并告警
  回退；两者都不可用时抛出的异常文本会列出两个 Bean 名与配置项名。

#### 修复

- **工具参数类型在入口统一校验**。模型把 `42` 塞进字符串参数时，`activityDescription`
  里的裸 `(String)` 强转会抛 `ClassCastException`，而它不在工具调用的 try/catch
  之内 —— 整轮 Agent 中止，模型连「换个类型重发」的机会都没有。现在在解析参数
  之后、Hook/权限/执行之前，按工具 `inputSchema()` 声明校验类型，错误文本点名
  参数与期望类型。数值/布尔仍接受字符串形式（`"5"`、`"false"`），未声明参数与
  非法 schema 一律放行。
- **demo 前端 Markdown 渲染的 XSS**。原实现先排版后转义，`<img onerror>` 与
  `[x](javascript:...)` 都能执行。现在整体转义在前、排版在后；链接只放行
  `http/https/mailto`，其余按纯文本输出；代码块通过占位符搬运，内部不再二次解析。
- **配置装配的一致性**。`globalPrompt` 加了按名字的 `@ConditionalOnMissingBean`；
  `activeChatModel` 用 `ObjectProvider` + `@Qualifier` 懒解析，两种 provider 都
  注册时按配置选择，而不是让 Spring 按类型注入报歧义。
- **`DangerousPatterns.detect` 的裸强转**。`(String) input.get("command")` 改为
  `instanceof`：权限评估不在工具参数校验之后（SDK 可以直接调 `evaluate`）。
- **无回调时 ASK 判定静默拒绝**。headless 调用里「需要确认但没有注册回调」与
  「被规则拒绝」表现完全一样。现在记一行 info 说明原因。

#### 文档

- 新增 [`docs/permissions.md`](docs/permissions.md)：九步优先级表、五种模式、
  规则语法与词边界匹配、三级规则作用域、「始终允许」持久化收窄的完整语义，
  以及三条容易被误解的顺序（DENY 压过 BYPASS、STRICT 先于 ASK、ALLOW 先于 RiskDetector）。
- `hms-core/README.md` 与 `README.zh-CN.md` 的权限评估链从「八步、BYPASS 第一」
  改为与实现一致的九步，`BYPASS` 行为描述同步修正。

#### 未做的事（计划内但裁掉）

- **压缩管线抽象（`CompactionStrategy`）**。三个压缩层的调用点已被
  `attempt()` / `succeed()` 收敛，`succeed()` 是所有深压缩产出的唯一写回点且已
  统一过 `HistoryInvariants.verify`；再套一层接口，两处调用只会变成转发。
  按「只做转发的抽象是多余的轮子」的约定不做。新增压缩层时直接复用
  `succeed()` 即可获得校验、计数与事件通知。
- **统一事件出口**。压缩/工具/Agent 状态事件的消费方当前只有一个 SSE 桥，
  合并出口要改动前端协议；本轮只补了关键路径的日志（见上）。

#### 测试

- 新增 `AppConfigWiringTest`（7）、`ToolArgumentTypeGuardTest`（5）、
  `PermissionPrecedenceTest`（7）、`demo-app/markdown-render-test.mjs`（14），
  扩充 `ToolValidatorTest`（+4），共 +23（Java）/ +14（Node）。
- 修掉 `HistoryMutationAtomicityTest` 的一处测试自身竞态：读者采样数依赖线程
  调度，负载高时写者先收工，断言随机变红。改为「写满 400 次后等读者采到足够样本」。
- `hms-core` 659 个测试、`demo-app` 29 个测试、`markdown-render-test.mjs` 14 项全绿。

---

### 第 4 轮后续：端到端手测（tag `iter-4.1`）

> tag 基线：`iter-4`

#### 修复

- **开启提示词缓存后自动压缩永不触发**。`TokenTracker` 把 `lastPromptTokens`
  记为上游的 `input_tokens`，而缓存命中的提示词前缀由
  `cache_read_input_tokens` 单独上报。缓存生效后，压缩判据看到的只剩「本轮新增了
  多少」：实测同一会话一次请求 `input=223 / cache_read=10112`，真实上下文约 10K，
  判据只有 223 —— 阈值（有效窗口的 93%）永远达不到，上下文一路涨到被上游以 400
  拒绝。现在按 `input + cache_read + cache_creation` 计算。
  累计与计费口径不变：`usageSnapshot()` 仍分列四项，缓存按折价计费。

#### 已知问题（手测发现，未在本轮修复）

- **响应含 `thinking` 块时 `tool_use` 不被解析**。在 `spring-ai-anthropic:2.0.1`
  接返回 `thinking` 的 Anthropic 兼容端点（如 DeepSeek）时偶发：上游确实返回了
  `tool_use`，`AgentLoop` 却记录 `hasToolCalls=false`，整轮退化为纯文本。用中转
  剥离 `thinking` 块后同一请求正常执行；原生 Anthropic 端点未复现。在此之前接
  这类端点应关闭 extended thinking（请求体 `"thinking": {"type": "disabled"}`）。

#### 测试

- 新增 `TokenTrackerTest.cachedPromptTokensCountTowardContextSize`（+1）。
  `hms-core` 660 个测试、`demo-app` 29 个测试全绿。

---

### 第 4 轮后续：长任务「卡死」（tag `iter-4.2`）

> tag 基线：`iter-4.1`

用户反馈：执行一个「涵盖所有工具」的复杂任务时，界面停在
`🛠 Agent ▼ (执行中...)` 后再无输出。复现结论**不是死锁**，而是四件事叠在一起：

1. 子 Agent 在一次 `Agent` 工具调用里同步跑，期间**没有任何事件出口** —— 父循环
   只发了「Agent 开始」「Agent 结束」，中间几十次工具调用、几分钟，UI 只有一个转圈。
2. 会话互斥用的是 `synchronized (session)`，第二个请求**无限期排队**。用户刷新页面
   再发一句，界面就永远转圈，连「忙」这个提示都拿不到。
3. `cancel()` 只翻转父循环自己的标志。子 Agent 独立持有取消标志，**完全收不到信号**，
   点了「停止」也要等它自己跑满 50 轮迭代。
4. `WebFetch` 对**超时**也重试（30s × 3 次 = 92s 才拿到「读取超时」一句话）。子 Agent
   一次任务里连续几次这样的抓取，时间就被放大成十几分钟。

#### 修复

- **子 Agent 的工具事件接入父请求事件流**。`AgentToolExecutor` 在调用工具期间把本轮
  事件出口放进 `ToolContext`（`AgentTool.EVENT_SINK_KEY`），子 Agent 工厂读到后
  挂到自己循环上；SSE 因此能看到子 Agent 每一步 `Bash`/`WebFetch` 的 START/END。
- **同一会话的并发请求立刻失败而不是排队**。会话互斥改为
  `ReentrantLock.tryLock()`：拿不到锁即以 **`AGENT_BUSY`（2008）** 失败，错误信息
  说明「上一轮可能是耗时的工具链，请等待或先取消」。持有者结束后锁在 `finally` 中
  释放，`compactNow` 复用同一把锁。
- **取消会传给正在运行的子 Agent**。`AgentLoop` 登记其工具调用期间创建的子循环
  （`registerChild`），`cancel()` 逐个级联；子循环跑完后注销。子 Agent 仍无法再派生
  子 Agent，委派深度依旧恒为 1 层。
- **`WebFetch` 超时不再重试**。超时视为不可恢复（再等一整个超时大概率还是超时），
  连接被重置这类瞬时故障仍按 `max-retries` 重试。

#### 已知限制

- 取消只能落在**检查点**（模型调用之后、每次工具调用之前）。正在进行中的单次工具
  调用不会被强杀：一条 `sleep 120` 仍要跑完，子 Agent 的级联取消也遵循同一规则。
- 子 Agent 的迭代上限与主会话相同（默认 50）。事件现在能看见了，但没有单独的
  时间预算；需要更硬的上限时应在调用侧设置。

#### 测试

- 新增 `SessionBusyTest`（2）、`ChildLoopCancelTest`（2）、`SubAgentEventSinkTest`（1）、
  `WebFetchRetryPolicyTest`（2）。
  `hms-core` 667 个测试、`demo-app` 29 个测试全绿。
- 端到端验证：长任务运行中第二次发送 **29ms** 返回 `AGENT_BUSY`；`/cancel` **28ms**
  返回，日志同时在父循环与子循环上记录「cancelled by user」；SSE 流中出现子 Agent
  的 `Bash` START/END 事件。

---

### 第 4 轮后续：思考耗尽输出预算导致的空回复（tag `iter-4.3`）

> tag 基线：`iter-4.2`

现象：复杂任务完成后，再问「启动并让我看一下这个项目」，连续三次都只得到
「[本轮模型没有返回任何内容…]」。

根因不在会话历史，也不在压缩：接在 Anthropic 兼容端点上的思考模型
（`deepseek-flash`）**默认开启 thinking，而 `max_tokens` 是思考与正文共享的额度**。
长上下文下模型思考得足够久，`max_tokens` 在思考阶段就用完，响应里只有
`thinking_delta`、没有 `text_delta`：

```
"stop_reason": "max_tokens", "output_tokens": 8096, 0 个 text_delta
```

`AgentLoop` 看到「既无文本也无工具调用」，按「模型没返回内容」处理。重试同样
会耗尽，于是每一轮都是空的。已用 `AI_MAX_TOKENS=200` 复现该路径。

#### 变更

- **`AI_MAX_TOKENS` 默认值由 8096 提高到 16384**（`application-prod.yml` 的
  anthropic / openai 两处，README 同步）。思考仍然可能与正文争抢额度，16384
  为长上下文留出余量；不足时继续调大（实测该端点接受 65536）。
- **Ollama 部署编排的 `AI_MAX_TOKENS` 定为 `8192`**（tag `iter-4.5`，实测后确定，
  取代临时值 4096 / 65536）。取值依据见下方「输出预算探测」：预算与
  `HMS_RESERVED_TOKENS` 对齐才自洽，再大也拿不到。

#### 输出预算探测（`hms-demo`：qwen3.5:4b-q4_K_M，`num_ctx=32768`）

直接打 Ollama 的 OpenAI 兼容端点测量（`reasoning_effort` / `max_tokens` 组合）：

| 场景 | 结果 |
|---|---|
| 简单问答，`max_tokens=1024` | 思考吃光全部预算：`finish=length`、正文 0 字、`reasoning_len=2748` |
| 简单问答，`max_tokens=64` | 同上，正文 0 字 |
| 长文写作，`max_tokens=4096` | 思考 5051 字 + 正文 1199 字，`completion_tokens=1892`、`finish=stop` |
| 带 `tools` 的请求（3 次） | 思考开：26–67 token、1.1–3.5 秒；关思考：26 token、0.5 秒，工具与参数均正确 |
| prompt 30104 token + `max_tokens=8192` | **不报错**，在窗口边界截断：生成 2664 token、`finish=length`，Ollama 日志 `n_tokens=32767, truncated=1` |

两点结论：

1. **模型默认开启 thinking，思考与正文共享 `max_tokens`**（`ollama show` 显示
   `thinking default true`）。预算小于思考长度时，正文必然为空 —— 这正是
   「本轮模型没有返回任何内容」在小模型上的成因。单轮思考实测 1–2K token，
   工具决策只需几十 token。
2. **超出窗口的预算不会被拒绝，而是静默截断**。所以「把 max_tokens 调大」不能
   换来更长输出：可用输出 = `num_ctx - prompt`。按压缩阈值 ≈22855 计算，窗口
   最多只能再分配 ≈9913 token，`8192` 正是这个上界内的自洽取值。

副产物：`reasoning_effort: "none"` 能真正关闭 thinking（`think: false` 与
`reasoning_effort: "low"` 均被忽略），响应快 2–8 倍。Spring AI 2.0.1 支持
`spring.ai.openai.chat.reasoning-effort`，如需进一步提速可在容器里注入
`SPRING_AI_OPENAI_CHAT_REASONING_EFFORT=none`；本轮未默认开启，以免削弱 4B
模型的推理质量。

#### 已知限制

- 代码层仍把「思考占满预算」与「模型确实没产出」合并为同一句提示。若再次遇到
  空回复，先看上游的 `stop_reason` 与 `output_tokens`，再决定是否继续调大
  `AI_MAX_TOKENS` 或关闭 thinking。

#### 验证

- 新默认值生效：请求体 `max_tokens=16384`，同一会话正常返回正文。
- 容器侧（`hms-demo`，qwen3.5:4b + Ollama）重建镜像并重启后复测原任务
  「启动并让我看一下这个项目」：9 秒内 4 次工具调用、4 轮迭代，第 4 轮返回
  367 字正文，未再出现空回复；容器镜像同时带上了 `iter-4.2`/`iter-4.3` 的修复。
  注意该容器此前跑的是 4 小时前的旧 jar。
- `AI_MAX_TOKENS=8192` 复测：简单问答 5.7 秒返回 221 字；原任务 5.6 秒、2 次
  工具调用返回 134 字；继续对话把累计 token 从 18568 压到 6298（压缩触发），
  压缩后回答正常、无 400。
