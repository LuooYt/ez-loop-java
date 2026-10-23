# 权限语义说明

本文描述 `com.inspirationi.loop.permission` 的权限模型：一次工具调用在什么条件下
被放行、拒绝或送去用户确认。实现见 `PermissionRuleEngine.evaluate()` —— 它是
**唯一的判定入口**，本文的优先级表与该方法的编号、类注释三者保持一致；改其中
任何一处都必须同步另外两处。

## 一次判定的完整顺序

自上而下，**先命中先返回**：

| # | 条件 | 结果 |
|---|---|---|
| 1 | `ToolContext` 里有 `PERMISSION_MODE_OVERRIDE` | 用覆盖值替换当前模式，继续往下判 |
| 2 | 匹配到显式 **DENY** 规则 | `DENY` |
| 3 | 模式为 `STRICT` | `READ_ONLY` → `ALLOW`，其余 → `DENY` |
| 4 | 匹配到显式 **ASK** 规则 | `ASK`（附触发规则名） |
| 5 | 模式为 `BYPASS` | `ALLOW` |
| 6 | 工具风险等级在模式的自动放行阈值内 | `ALLOW` |
| 7 | 匹配到显式 **ALLOW** 规则 | `ALLOW` |
| 8 | `RiskDetector` 检出风险 | `ASK`（附风险说明） |
| 9 | 以上都不命中 | `ASK` |

### 三条容易被误解的顺序

**DENY 与 ASK 规则排在模式之前。** 用户亲手设过的规则是明确意图，模式只是
「用户没有专门说明时如何自动判断」的默认值。因此：

- `BYPASS` **不覆盖**显式 `DENY`，也不覆盖显式 `ASK`。旁路的语义是「不再逐次
  确认」，不是「取消我设过的规则」。需要彻底放开，应当删除规则或换一个会话。
- `TRUSTED` 下一条 `ASK` 规则仍然要弹确认 —— 否则「只放行 CRITICAL 之外的一切」
  会把用户专门标出来的工具一并放过去。

**`STRICT` 排在 ASK 规则之前。** 该模式的含义是「非只读一律不行」。把它的 `DENY`
降级成一次确认是**放松**约束而不是收紧，所以 ASK 规则无法让 `STRICT` 下的写操作
变成可确认的操作。

**`ALLOW` 规则排在风险等级自动放行之后。** 两者结论相同（都是放行），顺序不影响
结果；把它放在后面只是因为风险等级的判断成本更低。但 `ALLOW` 规则**先于**
`RiskDetector`：用户显式放行过的命令，不再被启发式检测拦下来确认。

## 权限模式

| 模式 | 自动放行的最高风险等级 | 适用场景 |
|---|---|---|
| `STRICT` | `READ_ONLY` | 代码分析、架构审查等纯读场景 |
| `SAFE` | `LOW` | 需要轻度副作用、但拒绝高风险操作的场景 |
| `DEFAULT` | `MEDIUM` | 日常交互（默认） |
| `TRUSTED` | `HIGH` | 高度信任的内部系统 |
| `BYPASS` | 全部（`CRITICAL`） | 自动化脚本；仍然先过 DENY / ASK 规则 |

风险等级由工具自己声明（`Tool.riskLevel()`），默认按 `isReadOnly()` 推断。
模式只决定「超过阈值时怎么办」—— 超过阈值且没有规则命中时，走风险检测与默认
`ASK`。

## 规则

### 形状

规则是 `(工具名, 内容, 行为)`：

```java
PermissionRule.forTool("WebSearch", PermissionBehavior.ALLOW);          // 工具级："*"
PermissionRule.forCommand("Bash", "npm", PermissionBehavior.ALLOW);     // 前缀：Bash(npm:*)
PermissionRule.forExactCommand("Bash", "rm -rf /tmp/x", PermissionBehavior.DENY); // 精确一条
```

字符串形式（`PermissionSettings` 的项目级/用户级规则用它）为 `工具名` 或
`工具名(内容)`，例如 `Bash(npm:*)`、`Write`。

### 匹配规则

- **工具名**大小写不敏感地整体相等（不做前缀或包含匹配）。
- `*`：匹配该工具的全部调用。
- 以 `:*` 结尾：**词边界**前缀匹配 —— 命令必须恰好等于前缀，或前缀之后紧跟空白。
  `Bash(ls:*)` 不匹配 `lsfoo`，`Bash(git:*)` 不匹配 `git-reset-hard.sh`。
  `;`、`&`、`|`、`>` 等**不算**边界：`git;rm -rf /` 不归 `git:*` 管。
- 其余内容：与整条命令做大小写不敏感的**精确**比较。

匹配逻辑只存在于 `matchesRule()` 一处，`DENY` / `ASK` / `ALLOW` 三条分支共用，
避免出现「DENY 用词边界、ALLOW 还是裸 `startsWith`」这类不对称。

### 命令从哪里来

规则内容匹配的是「命令文本」，它由 `setCommandExtractors()` 配置的
`工具名 → 参数名` 映射从工具入参里取。**默认映射为空**，即不做命令提取 ——
此时任何带内容的规则都不会命中，只有工具级规则（`*`）有效。

```java
engine.setCommandExtractors(Map.of(
    "Bash", "command",
    "Write", "file_path",
    "Edit", "file_path"));
```

`RiskDetector` 与此无关：它拿到的是 `(toolName, input)` 原始入参，自己决定看哪个字段
（例如 `DangerousPatterns` 只对 `Bash` / `PowerShell` 读 `command`）。默认**一个检测器
都没注册**，需要 `addRiskDetector(new DangerousPatterns())` 才会启用；纯 Web 场景可以不加。

### 作用域与合并顺序

| 作用域 | 添加方式 | 生命周期 |
|---|---|---|
| 项目级 | `setProjectRules(allow, deny, mode)` | 由集成方注入，可整体替换 |
| 用户级 | `setUserRules(...)` / `addUserRule(rule)` | 内存常驻，集成方自行持久化 |
| 会话级 | `addSessionRule(rule)` | 会话内有效 |

`getAllRules()` 按「项目级 → 用户级 → 会话级」合并；同一行为有多条命中时取**第一条**。
模式优先级为「项目级 > 用户级 > `DEFAULT`」。全部规则只存在内存里，本模块不读写
磁盘配置文件。

### 「始终允许」会把规则放到哪一层

用户点「始终允许」时，`applyChoice()` 生成 **精确到这一条命令** 的规则
（不截成前缀），再按影响面决定层级：

| 情形 | 落点 |
|---|---|
| 普通命令 | 用户级规则，跨会话保留 |
| 敏感命令（`rm`、`chmod`、`sudo`、`git`、`curl`…，见 `SENSITIVE_ALLOW_COMMANDS`） | **仅本会话**，重启即失效 |
| Shell 类工具（`bash`、`powershell`、`cmd`…）的**工具级**放行 | **不记录**，只放行这一次 |
| 风险检测器判定的危险通配符 | 工具级 → 不记录；带内容 → 仅本会话 |

一次确认换来的放行范围，必须与用户当时看到的那条命令相同。「始终拒绝」不受此限：
`DENY` 收窄只会少拦，不会越权。

## 扩展点

- `RiskDetector`：实现接口后 `addRiskDetector()` 注入。返回非空字符串即视为检出风险，
  判定结果变为 `ASK` 并附带该说明。`DangerousPatterns` 只是内置实现之一，
  纯 Web 场景可以不加。
- `ToolContext` 的 `PERMISSION_MODE_OVERRIDE`：按会话/请求临时覆盖模式，
  例如多智能体里给某个子 Agent 收紧到 `STRICT`。它只影响**模式**，绕不过 DENY / ASK 规则。
- `PermissionRuleEngine.inheritPolicyFrom()`：让子 Agent 的引擎继承命令提取映射与风险检测器
  （策略），但不继承规则与模式（那是各 Agent 自己的）。

## 相关代码

| 内容 | 位置 |
|---|---|
| 判定入口与优先级 | `permission/PermissionRuleEngine.evaluate()` |
| 规则匹配 | `permission/PermissionRuleEngine.matchesRule()` |
| 规则、模式、决策类型 | `permission/PermissionTypes.java` |
| 规则的存储与合并 | `permission/PermissionSettings.java` |
| 「始终允许」的落点 | `permission/PermissionRuleEngine.applyChoice()` |
| 拒绝后的降级 | `permission/DenialTracker.java` |
