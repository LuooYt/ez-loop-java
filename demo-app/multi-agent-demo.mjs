#!/usr/bin/env node
/**
 * 多智能体能力演示 —— 用真实模型跑一轮，把 hms-core 的多智能体能力摊开看。
 *
 * 用法（先自己把 demo-app 跑起来，默认连 8088）：
 *   node demo-app/multi-agent-demo.mjs
 *   SERVER_PORT=9090 node demo-app/multi-agent-demo.mjs
 *
 * 纯客户端：只发 HTTP 请求，不启动/停止/构建任何东西。零依赖，只用 Node 20+ 内置 fetch。
 *
 * 与前缀同目录的 api-test.mjs 的分工：那个是**回归测试**（断言 + 退出码），
 * 这个是**演示**（打印过程 + 人工观看）。因此这里不做断言，跑完即退出码 0；
 * 唯一的硬失败是被测实例连不上。演示要消耗真实 token（约 5~10 次模型调用），
 * 别在计费敏感的环境反复跑。
 *
 * 这一轮会依次展示：
 *   1. 注册四个在「提示词 / 工具集 / 权限 / 记忆 / 主动性」上互不相同的 Agent
 *   2. 默认 Agent 自主调用 ListAgents 发现同事，再自主委派（不是脚本指定的）
 *   3. 回看完整消息轨迹 —— 每一次工具调用的入参与结果都在里面
 *   4. 被委派方的 Agent 级记忆（跨会话共享、随 Agent 存活）
 *   5. 主动 Agent 被心跳唤醒、处理工作项、产物落后台会话
 */

const PORT = process.env.SERVER_PORT || '8088';
const BASE = process.env.BASE_URL || `http://localhost:${PORT}`;

// 与 AgentController 里的常量一一对应
const RESEARCHER = 'researcher';
const SCOUT = 'scout';

const C = {
  reset: '\x1b[0m', bold: '\x1b[1m', dim: '\x1b[2m',
  red: '\x1b[31m', green: '\x1b[32m', yellow: '\x1b[33m', cyan: '\x1b[36m',
};

const TOTAL_STEPS = 6;

function step(n, title) {
  console.log(`\n${C.bold}${C.cyan}[${n}/${TOTAL_STEPS}] ${title}${C.reset}`);
}

function info(msg) { console.log(`  ${msg}`); }
function ok(msg) { console.log(`  ${C.green}✓${C.reset} ${msg}`); }
function warn(msg) { console.log(`  ${C.yellow}!${C.reset} ${msg}`); }
function fail(msg) { console.log(`  ${C.red}✗${C.reset} ${msg}`); }

/** 截断长文本，保留单行可读性。 */
function clip(text, max = 160) {
  if (text == null) return '';
  const oneLine = String(text).replace(/\s+/g, ' ').trim();
  return oneLine.length > max ? oneLine.slice(0, max) + '…' : oneLine;
}

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`${method} ${path} 返回非 JSON（HTTP ${res.status}）：${clip(text, 300)}`);
  }
  return { status: res.status, json };
}

/** 取 ApiResponse.data，失败时抛出可读错误。 */
function unwrap(path, { json }) {
  if (!json || typeof json !== 'object') {
    throw new Error(`${path} 响应结构异常`);
  }
  if (json.success === false) {
    throw new Error(`${path} 失败：${json.message}`);
  }
  return json.data;
}

/** 打印一条会话消息 —— 工具调用是重点。 */
function printMessage(msg, index) {
  const roleColor = msg.role === 'user' ? C.cyan
    : msg.role === 'assistant' ? C.green
      : msg.role === 'tool' ? C.yellow : C.dim;

  console.log(`  ${C.dim}${String(index + 1).padStart(2)}.${C.reset} `
    + `${roleColor}${msg.role}${C.reset}`
    + (msg.toolName ? ` ${C.bold}${msg.toolName}${C.reset}` : ''));

  if (msg.content) console.log(`      ${clip(msg.content, 240)}`);
  if (msg.toolArguments) console.log(`      ${C.dim}入参 ${clip(msg.toolArguments, 240)}${C.reset}`);
  if (msg.toolResult) console.log(`      ${C.dim}结果 ${clip(msg.toolResult, 240)}${C.reset}`);
}

async function main() {
  console.log(`${C.bold}多智能体演示${C.reset} —— 目标 ${C.bold}${BASE}${C.reset}`);
  console.log(`${C.dim}（真实模型调用，脚本本身不做断言；观察输出即可）${C.reset}`);

  // ── 0. 可达性 ────────────────────────────────────────────────────────
  try {
    await api('GET', '/api/agents');
  } catch (e) {
    fail(`连不上被测实例 ${BASE}`);
    console.log(`  ${C.dim}先启动 demo-app：cd demo-app && mvn spring-boot:run${C.reset}`);
    console.log(`  ${C.dim}错误：${e.message}${C.reset}`);
    process.exit(1);
  }

  // ── 1. 注册演示 Agent ───────────────────────────────────────────────
  step(1, '注册演示 Agent（幂等，可反复调用）');
  unwrap('/api/agents/demo/setup', await api('POST', '/api/agents/demo/setup'))
    .forEach((line) => ok(line));
  info(`${C.dim}默认 Agent 由容器启动时自动注册，此处不重复${C.reset}`);

  // ── 2. 差异对比 ────────────────────────────────────────────────────
  step(2, '各 Agent 的差异化配置 —— 提示词 / 工具集 / 权限 / 记忆 / 主动性');
  const agents = unwrap('/api/agents', await api('GET', '/api/agents'));

  for (const a of agents) {
    console.log(`\n  ${C.bold}${a.agentId}${C.reset} 「${a.name}」`);
    info(`${C.dim}${clip(a.description, 100)}${C.reset}`);
    info(`模式 ${C.bold}${a.activation}${C.reset} · 权限 ${a.permissionMode} · `
      + `工具 ${C.bold}${a.toolCount}${C.reset} 个 · `
      + `记忆 ${a.memoryEnabled ? `${a.memoryEntries} 条` : '关闭'}`);
    if (a.basePrompt) {
      info(`${C.dim}提示词首行：${clip(a.basePrompt.split('\n')[0], 90)}${C.reset}`);
    }
  }

  // 工具集隔离的直观证据：只读审查员的清单明显更短
  const byId = Object.fromEntries(agents.map((a) => [a.agentId, a]));
  if (byId.default && byId.auditor) {
    console.log();
    ok(`工具集隔离：默认 Agent ${byId.default.toolCount} 个 vs `
      + `只读审查员 ${byId.auditor.toolCount} 个 —— 后者被 ToolSelect.readOnlyOnly() 收窄`);
    const missing = byId.default.tools.filter((t) => !byId.auditor.tools.includes(t));
    if (missing.length > 0) {
      info(`${C.dim}审查员看不到：${clip(missing.join(', '), 200)}${C.reset}`);
    }
  }

  // ── 3. 让默认 Agent 自主发现并委派 ─────────────────────────────────
  step(3, '给默认 Agent 一个任务 —— 发现同事、挑选、委派，全由模型自己决定');
  const { sessionId } = unwrap('/api/agents/default/sessions',
    await api('POST', '/api/agents/default/sessions'));
  info(`会话已创建：${C.dim}${sessionId}${C.reset}`);

  const task = '请先调用 ListAgents 看看有哪些同事可以帮忙，然后把下面这件事交给最合适的那位去做，'
    + '并把它的结论原样转述给我：统计「人工智能正在改变软件开发的方式」这句话里有几个汉字。';
  info(`${C.dim}任务：${clip(task, 200)}${C.reset}`);
  info('等待模型响应…');

  const startedAt = Date.now();
  const chat = unwrap(`/api/chat/${sessionId}`,
    await api('POST', `/api/chat/${sessionId}`, { message: task }));
  const elapsed = ((Date.now() - startedAt) / 1000).toFixed(1);

  console.log();
  ok(`模型回复（${elapsed}s，工具调用 ${chat.toolCallsCount} 次，`
    + `本轮 token ${chat.totalTokens}）`);
  console.log(`\n  ${C.bold}${clip(chat.content, 800)}${C.reset}`);

  if (chat.toolCallsCount === 0) {
    warn('本轮没有发生工具调用 —— 模型可能没走自主发现/委派路径，'
      + '可重跑一次或换个说法，轨迹以第 4 步为准');
  }

  // ── 4. 回看完整轨迹 ────────────────────────────────────────────────
  step(4, '完整消息轨迹 —— 每一次工具调用的入参与结果');
  const messages = unwrap(`/api/agents/sessions/${sessionId}/messages`,
    await api('GET', `/api/agents/sessions/${sessionId}/messages`));
  messages.forEach(printMessage);

  const delegated = messages.some((m) => m.toolName === 'Agent');
  const discovered = messages.some((m) => m.toolName === 'ListAgents');
  const selfDelegated = messages.some((m) => m.toolName === 'Agent'
    && m.toolResult && m.toolResult.includes('不能把任务委派给自己'));

  console.log();
  if (discovered) ok('模型自主调用了 ListAgents —— 发现能力生效');
  else warn('未见 ListAgents 调用');
  if (delegated) ok('模型自主调用了 Agent —— 委派能力生效（目标由模型自己挑的）');
  else warn('未见 Agent 委派调用');
  if (selfDelegated) {
    ok('自我委派被护栏拒绝、模型随即自行改派 —— 这条护栏在真实模型上确实拦得住');
  }

  // ── 5. Agent 级记忆 ────────────────────────────────────────────────
  step(5, `Agent 级记忆 —— 与「${RESEARCHER}」直接对话，让它自主写入`);
  info(`${C.dim}为何不查被委派方的记忆：委派会话的记忆只读（否则它成了一条绕过`
    + `调用方审查的写入通道），所以只能与 Agent 本身对话才会写入${C.reset}`);

  const { sessionId: researcherSession } = unwrap(`/api/agents/${RESEARCHER}/sessions`,
    await api('POST', `/api/agents/${RESEARCHER}/sessions`));
  const memoryTask = '请把这条信息记进你的长期记忆，供以后所有会话使用：'
    + '本项目的内部代号是「山海」，长期有效。记住后简短确认即可。';
  const memoryChat = unwrap(`/api/chat/${researcherSession}`,
    await api('POST', `/api/chat/${researcherSession}`, { message: memoryTask }));
  ok(`回复（工具调用 ${memoryChat.toolCallsCount} 次）：${clip(memoryChat.content, 220)}`);

  const memory = unwrap(`/api/agents/${RESEARCHER}/memory`,
    await api('GET', `/api/agents/${RESEARCHER}/memory`));

  if (!memory.enabled) {
    warn('该 Agent 未启用记忆');
  } else {
    console.log();
    info(`条目 ${C.bold}${memory.size}${C.reset} 条 · digest 版本 ${memory.digestVersion}`);
    for (const e of memory.entries) {
      info(`· [${e.tags.join(',') || '无标签'}] ${clip(e.content, 140)} ${C.dim}(${e.source})${C.reset}`);
    }
    if (memory.entries.length === 0) {
      warn('记忆仍为空 —— 模型这轮没调用写工具，可重跑本步');
    } else {
      console.log();
      info(`${C.dim}digest（此后写入 system prompt 的正是这段）↓${C.reset}`);
      console.log(`${C.dim}${clip(memory.digest, 500) || '（空）'}${C.reset}`);
      info(`${C.dim}这是 Agent 级记忆：该 Agent 名下所有会话共享，随 Agent 存活${C.reset}`);
    }
  }

  // ── 6. 主动 Agent 的心跳 ───────────────────────────────────────────
  step(6, '主动 Agent 被心跳唤醒 —— 处理工作项，产物落后台会话');
  const work = unwrap(`/api/agents/${SCOUT}/work`,
    await api('POST', `/api/agents/${SCOUT}/work`,
      { prompt: '用一句话汇报系统当前状态，并把「巡检已完成」这条结论记进你的记忆。' }));
  ok(`已投递工作项 ${work.workItemId}（队列 ${work.queueSize} 项）`);

  info('触发一次心跳…');
  const beat = unwrap(`/api/agents/${SCOUT}/heartbeat`,
    await api('POST', `/api/agents/${SCOUT}/heartbeat`));
  info(`ran=${beat.ran} · 累计心跳 ${beat.totalRuns} 次 · 连续无产出 ${beat.idleRuns} 次`
    + (beat.note ? ` ${C.dim}(${beat.note})${C.reset}` : ''));

  if (beat.backendSessionId) {
    ok(`后台会话：${C.dim}${beat.backendSessionId}${C.reset}`);
    const backendMsgs = unwrap(`/api/agents/sessions/${beat.backendSessionId}/messages`,
      await api('GET', `/api/agents/sessions/${beat.backendSessionId}/messages`));
    console.log();
    backendMsgs.forEach(printMessage);
  } else {
    warn('尚未创建后台会话 —— 心跳可能被跳过（后台会话正忙 / 降频 / 已达上限自动暂停）');
  }

  // 巡检员自己写的记忆
  const scoutMemory = unwrap(`/api/agents/${SCOUT}/memory`,
    await api('GET', `/api/agents/${SCOUT}/memory`));
  if (scoutMemory.enabled && scoutMemory.entries.length > 0) {
    console.log();
    ok(`巡检员记忆已沉淀 ${scoutMemory.entries.length} 条：`);
    for (const e of scoutMemory.entries) {
      info(`· ${clip(e.content, 120)} ${C.dim}(${e.source})${C.reset}`);
    }
  }

  console.log(`\n${C.bold}演示结束${C.reset} ${C.dim}—— 全过程可在浏览器前端或 `
    + `GET /api/agents 复核${C.reset}\n`);
}

main().catch((e) => {
  console.error(`\n${C.red}演示中断：${e.message}${C.reset}`);
  process.exit(1);
});
