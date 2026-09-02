#!/usr/bin/env node
/**
 * HMS Core 接口冒烟测试 —— 逐个验证 core 通过 demo-app 暴露的对外能力。
 *
 * 用法（先自己把 demo-app 跑起来，默认连 8088）：
 *   node demo-app/api-test.mjs
 *   node demo-app/api-test.mjs --only chat --only stream
 *
 * 纯客户端：只发 HTTP 请求，不启动/停止/构建任何东西。被测实例由你自己掌控
 * （IDE 里调试运行也行），脚本不会去动它 —— 这样跑测试不会打断你的调试会话。
 *
 * 零依赖：只用 Node 20+ 内置的 fetch。不需要 curl、jq。
 *
 * 环境变量全部可选 —— 未设置 AI_API_KEY 时脚本会实测一次模型链路，
 * 密钥写在 application.yml 里同样能跑全部用例（见 probeModel）：
 *   SERVER_PORT（默认 8088）/ BASE_URL   指定被测实例
 *   AI_API_KEY                          仅用于跳过模型探测，不参与请求
 *   HMS_CORE_CONTEXT_WINDOW             告知脚本被测实例的窗口配置（见压缩组）
 *   HMS_CORE_RESERVED_TOKENS            告知脚本被测实例的预留 token（默认 20000）
 *
 * 可选参数：
 *   --only <名>  只跑指定组，精确匹配组名，可重复传
 *   --list       列出所有测试组后退出
 *
 * 退出码：0 全部通过；非 0 为失败用例数。
 *
 * 本脚本不含任何密钥：要么从环境变量读，要么直接用被测应用自己的配置。
 *
 * ── 为什么是 JS 而不是 bash ────────────────────────────────────────────
 * 这套测试原先是 bash 版，在 Windows/Git Bash 下踩了一串与被测系统无关的坑，
 * 换 JS 是为了从根上消除它们，而不是继续打补丁：
 *   · curl 的 -d 会把命令行里的 UTF-8 中文按本地 GBK 发出，服务端收到非法
 *     起始字节直接 400。bash 版被迫把每个请求体写临时文件再 --data-binary。
 *     JS 的 fetch 直接发 UTF-8，问题不存在。
 *   · 没装 jq 时所有 JSON 断言退化为文本 grep，十几个数值断言静默失效
 *     （看起来"通过"，实际没在断言）。JS 有原生 JSON.parse。
 *   · bash 的裸 `wait` 会等待*所有*后台作业。被测应用本身就是后台作业且
 *     永不退出，于是并发组一执行到 `wait` 就永久挂起。JS 用 Promise.all，
 *     只等自己派生的任务。
 *   · 手写的 od + sed 百分号编码器容易与本地字符集打架。JS 有
 *     encodeURIComponent。
 *
 * ── 测试边界 ──────────────────────────────────────────────────────────
 * 纯黑盒：只打 demo-app 已暴露的 REST/SSE 端点，不改动被测应用。
 * hms-core 有几项能力没有对应的 HTTP 端点，因此无法在此验证，脚本会以
 * skip 显式标注而非静默略过（见「未覆盖能力」组）。
 *
 * ── 关于上下文压缩用例 ────────────────────────────────────────────────
 * 触发条件读自 core 源码（TokenTracker），不是猜的：
 *
 *   effectiveWindow = context-window - reserved-tokens
 *   threshold       = effectiveWindow * AUTO_COMPACT_THRESHOLD_PCT(0.93)
 *   判据            = lastPromptTokens >= threshold
 *
 * 两个后果：
 *   1. 判据是 lastPromptTokens（最近一次 prompt 的大小，近似"当前上下文"），
 *      不是累计用量。累计几十万也不会触发 —— 这是正确设计。
 *   2. 窗口必须显著大于预留值，否则有效窗口归零、getUsagePercentage() 恒 0。
 *
 * ── 降低阈值要调 reserved-tokens，不要调小 context-window ──
 * context-window 是「模型能吃多少」的声明。把它配得比模型真实窗口小，会让 core
 * 在远未真正超限时就判定超载（日志出现 >100% 占用率）并反复发起压缩；而压缩的
 * SessionMemory / Full 两层都要把这段历史发给模型做摘要 —— 历史对上游其实完全
 * 合法，压缩即使成功也是白压，失败则纯属浪费。实测每轮卡 130 秒以上直至超时。
 *
 * 被测实例侧写在 application.yml（环境变量 HMS_CORE_CONTEXT_WINDOW 仍作回退）：
 *   hms-core:
 *     context-window: 200000    # 与模型真实窗口一致
 *     reserved-tokens: 170000   # 有效窗口 30000、阈值 27900
 * 脚本读不到被测实例的配置文件，需用同名环境变量把同一组值告知它：
 *   HMS_CORE_CONTEXT_WINDOW=200000 HMS_CORE_RESERVED_TOKENS=170000 \
 *     node demo-app/api-test.mjs --only compact
 * 未告知时该组显式 skip，不会假装通过。两侧值必须一致，否则脚本算出的阈值与
 * 服务端实际使用的不同，会把「填充不足」误报成缺陷。
 */

// ── CLI ─────────────────────────────────────────────────────────────────

const GROUPS = [
  'tool', 'session', 'contract', 'permission', 'chat', 'toolcall',
  'compact', 'stream', 'lifecycle', 'concurrency', 'interactive', 'metrics',
];

const PORT = process.env.SERVER_PORT || '8088';
const BASE_URL = process.env.BASE_URL || `http://localhost:${PORT}`;

const opts = { only: [] };
for (let i = 2; i < process.argv.length; i++) {
  const a = process.argv[i];
  if (a === '--only') opts.only.push(process.argv[++i] ?? '');
  else if (a === '--list') { console.log(GROUPS.join('\n')); process.exit(0); }
  else if (a === '-h' || a === '--help') {
    console.log('用法: node demo-app/api-test.mjs [--only <组名>] [--list]');
    console.log(`默认连接 ${BASE_URL}（改 SERVER_PORT 或 BASE_URL 可换目标）`);
    console.log('组名: ' + GROUPS.join(' / '));
    process.exit(0);
  } else { console.error(`未知参数: ${a}（--help 查看用法）`); process.exit(2); }
}

// 组名打错时立刻报错。--only 走精确匹配，拼错会导致一个组都不跑却退出码 0 ——
// 那是最糟的失败形态：看起来"全部通过"，实际什么都没测。
{
  const bad = opts.only.filter((o) => !GROUPS.includes(o));
  if (bad.length > 0) {
    console.error(`未知组名: ${bad.join(', ')}`);
    console.error(`可用组名: ${GROUPS.join(' / ')}`);
    process.exit(2);
  }
}

// TokenTracker 里的压缩参数，压缩组据此推算阈值（见文件头）。
//
// 两者现已可经 application.yml 的 hms-core.context-window /
// hms-core.reserved-tokens 配置。脚本不读被测实例的配置文件（它可能在另一台机器
// 上），因此靠同名环境变量告知；未告知时按 core 的默认值算。
// 注意这些变量只是让脚本知道被测实例配了什么，本身不改变服务端行为。
const RESERVED_TOKENS = Number(process.env.HMS_CORE_RESERVED_TOKENS || 0) || 20_000;
const AUTO_COMPACT_PCT = 0.93;

/**
 * hms-core 注册的全局工具全集（ToolConfiguration 里 register(...) 的那批）。
 * 少一个说明注册链路漏了，多一个说明这里该补 —— 两个方向都值得报错。
 */
const EXPECTED_TOOLS = [
  'WebFetch', 'WebSearch',
  'Agent', 'SendMessage',
  'TaskCreate', 'TaskGet', 'TaskList', 'TaskUpdate', 'TaskStop', 'TaskOutput',
  'TodoWrite',
  'ListMcpResources', 'ReadMcpResource',
  'Skill', 'Config', 'Sleep',
  'AskUserQuestion', 'ToolSearch',
  'EnterPlanMode', 'ExitPlanMode',
];

// ── 输出 ────────────────────────────────────────────────────────────────

const tty = process.stdout.isTTY;
const C = tty
  ? { red: '\x1b[31m', green: '\x1b[32m', yellow: '\x1b[33m', blue: '\x1b[36m', dim: '\x1b[2m', off: '\x1b[0m' }
  : { red: '', green: '', yellow: '', blue: '', dim: '', off: '' };

const stats = { pass: 0, fail: 0, skip: 0 };
const failedNames = [];

const section = (t) => console.log(`\n${C.blue}── ${t} ${C.off}`);
const pass = (n) => { stats.pass++; console.log(`  ${C.green}✓${C.off} ${n}`); };
const fail = (n, d) => {
  stats.fail++; failedNames.push(n);
  console.log(`  ${C.red}✗${C.off} ${n}`);
  if (d) console.log(`      ${C.dim}${String(d).slice(0, 300)}${C.off}`);
};
const skip = (n, why) => { stats.skip++; console.log(`  ${C.yellow}-${C.off} ${n} ${C.dim}(${why || '跳过'})${C.off}`); };
const info = (t) => console.log(`  ${C.dim}${t}${C.off}`);

/**
 * 组筛选。用精确相等而非子串包含：组名之间互为前缀（tool / toolcall、
 * session / 无），子串匹配会让 `--only tool` 把 toolcall 也拖进来 ——
 * 那一组要跑真实模型、耗时几分钟，与「只跑一个快组」的意图正好相反。
 */
const shouldRun = (g) => opts.only.length === 0 || opts.only.includes(g);
const preview = (v, n = 160) => {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return (s ?? '').slice(0, n);
};

// ── 断言 ────────────────────────────────────────────────────────────────

const assertEq = (n, actual, expected) =>
  actual === expected ? pass(n) : fail(n, `预期 [${preview(expected)}]，实际 [${preview(actual)}]`);

const assertGt = (n, actual, floor) =>
  Number.isFinite(Number(actual)) && Number(actual) > floor
    ? pass(n) : fail(n, `预期 > ${floor}，实际 [${preview(actual)}]`);

const assertGe = (n, actual, floor) =>
  Number.isFinite(Number(actual)) && Number(actual) >= floor
    ? pass(n) : fail(n, `预期 >= ${floor}，实际 [${preview(actual)}]`);

const assertContains = (n, hay, needle) =>
  String(hay ?? '').includes(needle) ? pass(n) : fail(n, `未找到 [${needle}]，实际：${preview(hay)}`);

const assertNotContains = (n, hay, needle) =>
  String(hay ?? '').includes(needle) ? fail(n, `不该出现 [${needle}]，实际：${preview(hay)}`) : pass(n);

/** 字段存在且是有限数字 —— 用于「没接上就恒缺失」的契约断言（0 是合法值） */
const assertNumber = (n, v) =>
  v !== undefined && v !== null && Number.isFinite(Number(v))
    ? pass(n) : fail(n, `字段缺失或不是数字：[${preview(v)}]`);

/** 对象自身含某个 key（区别于 assertContains 的字符串包含） */
const assertHasKey = (n, obj, key) =>
  obj && typeof obj === 'object' && key in obj
    ? pass(n) : fail(n, `缺少字段 [${key}]，实际字段：${obj ? Object.keys(obj).join(',') : preview(obj)}`);

// ── HTTP ────────────────────────────────────────────────────────────────

/**
 * 发一个请求，返回 { status, body, json, ok }。
 *
 * body 用 JSON.stringify + fetch 直接发 UTF-8，不再需要临时文件绕 GBK。
 * 网络层异常（连接被拒等）不抛，归一成 status:0，让断言去判 —— 测试脚本
 * 不该因为一个端点挂了就整体崩掉。
 */
async function call(method, path, body) {
  const init = { method, signal: AbortSignal.timeout(300_000) };
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json; charset=utf-8' };
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
  }
  try {
    const res = await fetch(`${BASE_URL}${path}`, init);
    const text = await res.text();
    let json;
    try { json = JSON.parse(text); } catch { /* 非 JSON（如 Tomcat 的 400 HTML）*/ }
    return { status: res.status, body: text, json, ok: res.ok };
  } catch (e) {
    return { status: 0, body: `请求异常: ${e.message}`, json: undefined, ok: false };
  }
}

/** HTTP 200 且 success=true */
function expectOk(name, r) {
  if (r.status !== 200) { fail(name, `HTTP ${r.status}: ${preview(r.body, 200)}`); return false; }
  if (r.json?.success !== true) { fail(name, `success!=true: ${preview(r.body, 200)}`); return false; }
  pass(name); return true;
}

/** 业务失败（success=false）—— 不关心状态码是 200 还是 4xx */
function expectFailResponse(name, r) {
  if (r.json?.success === false) { pass(name); return true; }
  fail(name, `预期 success=false，实际：${preview(r.body, 200)}`); return false;
}

/**
 * 结构化失败：必须是 4xx + success=false。
 * 守护 ApiExceptionHandler —— 没有它这些端点会返回 500 白页（HTML 而非 JSON）。
 */
function expectClientError(name, r) {
  if (!(r.status >= 400 && r.status < 500)) {
    fail(name, `预期 4xx，实际 HTTP ${r.status}: ${preview(r.body)}`); return false;
  }
  if (r.json?.success !== false) {
    fail(name, `4xx 但响应体不是 ApiResponse 失败体：${preview(r.body)}`); return false;
  }
  pass(name); return true;
}

// ── SSE ─────────────────────────────────────────────────────────────────

/**
 * 打开 SSE 流，收集事件直到 complete/error 到达或超时。
 *
 * 返回 { status, events: [{event, data}], raw }。
 *
 * 记录 status 很关键：消息走 URL 查询串，中文经百分号编码后体积膨胀约 3 倍，
 * 很容易超过 Tomcat 默认 8KB 请求头上限 —— 此时 Tomcat 直接回 400 HTML，
 * 请求根本没进应用。若不看状态码，这种传输层失败和「模型没触发该事件」
 * 表现完全一样，会把 400 误读成「阈值没到」。因此长消息一律走 POST。
 */
async function sse(sessionId, message, timeoutSec = 150, onEvent) {
  const url = `${BASE_URL}/api/chat/${sessionId}/stream?message=${encodeURIComponent(message)}`;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutSec * 1000);
  const out = { status: 0, events: [], raw: '' };
  try {
    const res = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal: ctrl.signal });
    out.status = res.status;
    if (!res.ok) {
      out.raw = await res.text();
      info(`SSE 请求未被受理（HTTP ${res.status}）——消息过长超出请求头上限？`);
      return out;
    }
    const decoder = new TextDecoder();
    let buf = '';
    for await (const chunk of res.body) {
      buf += decoder.decode(chunk, { stream: true });
      // 按 SSE 帧（空行分隔）切分，保留未完成的尾部
      const frames = buf.split(/\r?\n\r?\n/);
      buf = frames.pop() ?? '';
      for (const frame of frames) {
        const ev = parseFrame(frame);
        if (!ev) continue;
        out.events.push(ev);
        if (onEvent) { try { await onEvent(ev, ctrl); } catch { /* 回调不影响收流 */ } }
        if (ev.event === 'complete' || ev.event === 'error') { clearTimeout(timer); ctrl.abort(); }
      }
    }
  } catch {
    // AbortError（超时/主动结束）与连接中断都走这里 —— 已收到的事件仍然有效
  } finally {
    clearTimeout(timer);
  }
  out.raw = out.events.map((e) => `event:${e.event}\ndata:${e.dataRaw}`).join('\n\n');
  return out;
}

/** 解析单个 SSE 帧：取 event: 与拼接后的 data: */
function parseFrame(frame) {
  let name = null; const dataLines = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) name = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
  }
  if (!name && dataLines.length === 0) return null;
  const dataRaw = dataLines.join('\n');
  let data;
  try { data = JSON.parse(dataRaw); } catch { /* token 事件的 data 可能是裸文本 */ }
  return { event: name ?? 'message', dataRaw, data };
}

const evCount = (s, name) => s.events.filter((e) => e.event === name).length;
const evHas = (s, name) => evCount(s, name) > 0;
const evFirst = (s, name) => s.events.find((e) => e.event === name);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 模型能力探测 ────────────────────────────────────────────────────────

/**
 * 决定是否跑需要真实 AI 调用的用例。
 *
 * 不能只看 AI_API_KEY 环境变量：密钥也可以直接写在 application.yml 里（本仓库
 * 的默认配置就是这样），那种情况下环境变量为空但模型完全可用，照 env 判断会把
 * 全部模型用例静默跳过 —— 看起来"全部通过"，实际啥都没测。
 */
let hasModel = false;

async function probeModel() {
  if (process.env.AI_API_KEY) { hasModel = true; return; }
  info('未设置 AI_API_KEY，改为实测模型链路（密钥可能写在 application.yml 里）');

  const created = await call('POST', '/api/sessions', { sessionPrompt: 'probe' });
  const pid = created.json?.data?.sessionId;
  if (!pid) { info('探测会话创建失败，按「无模型」处理'); return; }

  const r = await call('POST', `/api/chat/${pid}`, { message: '只回复一个字：好' });
  if (r.json?.success === true) {
    hasModel = true;
    pass(`模型链路可用（实测发送成功，tokens=${r.json?.data?.totalTokens}）`);
  } else {
    // 区分两种「模型不可用」：应用自己的错误信封（业务层拒绝，如缺密钥、403）
    // 与裸 HTTP 5xx（未捕获异常，如 TLS 校验失败）。后者是被测实例的故障，
    // 不该和「没配密钥」混为一谈 —— 报出 status 才能一眼看出该去查服务端日志。
    const code = r.json?.data?.errorCode;
    if (code) {
      info(`模型不可用（errorCode=${code}），需真实 AI 的用例将跳过`);
    } else {
      info(`模型链路返回 HTTP ${r.status} 且无业务错误码 —— 被测实例内部异常，`
        + `请查服务端日志（TLS 校验、上游连通性等）`);
    }
    info(preview(r.body, 300));
  }
  await call('DELETE', `/api/sessions/${pid}`);
}

/** 建一个会话，失败返回 null */
async function newSession(prompt) {
  const r = await call('POST', '/api/sessions', { sessionPrompt: prompt });
  return r.json?.data?.sessionId ?? null;
}

// ══════════════════════════════════════════════════════════════════════
// 主流程
// ══════════════════════════════════════════════════════════════════════

async function main() {
  section('连接被测实例');
  const probe = await call('GET', '/api/tools');
  if (!probe.ok) {
    fail(`连接 ${BASE_URL}`,
      `无响应（HTTP ${probe.status}）—— 先启动 demo-app，或用 SERVER_PORT / BASE_URL 指定目标`);
    return;
  }
  pass(`连接 ${BASE_URL}`);

  await probeModel();

  // ── 1. 工具注册（两级工具体系）────────────────────────────────────
  if (shouldRun('tool')) {
    section('工具注册');
    const r = await call('GET', '/api/tools');
    if (expectOk('列出全局工具', r)) {
      const tools = r.json.data ?? [];
      info(`全局工具 ${tools.length} 个`);
      const missing = EXPECTED_TOOLS.filter((t) => !tools.includes(t));
      if (missing.length === 0) pass(`全部 ${EXPECTED_TOOLS.length} 个预期工具均已注册`);
      else fail(`全部 ${EXPECTED_TOOLS.length} 个预期工具均已注册`, `缺少：${missing.join(' ')}`);
      // 数量对不上说明注册表变了 —— 要么漏注册，要么该更新 EXPECTED_TOOLS
      assertEq('全局工具数与预期一致', tools.length, EXPECTED_TOOLS.length);
    }
    expectFailResponse('向不存在的会话添加工具应失败',
      await call('POST', `/api/tools/nope-${process.pid}/add/WebSearch`));
  }

  // ── 2. 会话生命周期 ───────────────────────────────────────────────
  section('会话生命周期');

  let SESSION = null;
  {
    const r = await call('POST', '/api/sessions', { sessionPrompt: '你是接口测试助手。回答尽量简短，不要寒暄。' });
    if (expectOk('创建会话（带自定义提示词）', r)) {
      SESSION = r.json.data.sessionId;
      info(`sessionId=${SESSION}`);
    }
  }
  if (!SESSION) { console.log(`\n${C.red}致命：无法创建会话，中止。${C.off}`); return; }

  // 不带 body 创建（走 createSession() 无参重载）
  {
    const r = await call('POST', '/api/sessions');
    if (expectOk('创建会话（不带 body，走无参重载）', r)) {
      const bare = r.json.data.sessionId;
      if (bare) expectOk('销毁无参创建的会话', await call('DELETE', `/api/sessions/${bare}`));
    }
  }

  {
    const r = await call('GET', '/api/sessions');
    if (expectOk('列出会话', r)) {
      assertGe('列表至少含刚建的会话', (r.json.data ?? []).length, 1);
      // 前端靠这两个字段自算总量（totalTokens() 是 record 派生方法，不进 JSON）
      assertContains('列表含 inputTokens 字段', r.body, '"inputTokens"');
      assertContains('列表含 outputTokens 字段', r.body, '"outputTokens"');
      assertNotContains('列表不含 totalTokens（派生方法不序列化）', r.body, '"totalTokens"');
    }
  }

  {
    const r = await call('GET', `/api/sessions/${SESSION}`);
    if (expectOk('查询会话详情', r)) {
      const d = r.json.data;
      assertEq('详情 sessionId 一致', d.sessionId, SESSION);
      assertEq('初始状态为 ACTIVE', d.status, 'ACTIVE');
      assertContains('会话已拿到全局工具副本', r.body, '"TodoWrite"');
      assertContains('详情回显会话提示词', r.body, '接口测试助手');
      assertNumber('详情含 idleSeconds', d.idleSeconds);
      assertNumber('详情含 messageCount', d.messageCount);
    }
  }

  expectFailResponse('查询不存在的会话应失败',
    await call('GET', `/api/sessions/nope-${process.pid}`));

  // 两级工具隔离
  if (shouldRun('tool')) {
    expectOk('移除会话工具 WebSearch',
      await call('POST', `/api/tools/${SESSION}/remove/WebSearch`));

    let r = await call('GET', `/api/tools/${SESSION}`);
    assertNotContains('移除后会话工具列表不含 WebSearch', r.body, '"WebSearch"');

    r = await call('GET', '/api/tools');
    assertContains('全局工具不受会话移除影响（两级隔离）', r.body, '"WebSearch"');

    // 另一个会话不该受影响 —— 会话级注册表必须互相独立
    const iso = await newSession('隔离验证会话');
    if (iso) {
      pass('创建第二个会话验证工具隔离');
      r = await call('GET', `/api/tools/${iso}`);
      assertContains('新会话仍有 WebSearch（会话间工具互不影响）', r.body, '"WebSearch"');
      expectOk('销毁隔离验证会话', await call('DELETE', `/api/sessions/${iso}`));
    } else fail('创建第二个会话验证工具隔离', '创建失败');

    expectOk('恢复会话工具 WebSearch',
      await call('POST', `/api/tools/${SESSION}/add/WebSearch`));
    r = await call('GET', `/api/tools/${SESSION}`);
    assertContains('恢复后会话工具列表含 WebSearch', r.body, '"WebSearch"');

    expectFailResponse('添加不存在的工具应失败',
      await call('POST', `/api/tools/${SESSION}/add/NoSuchTool${process.pid}`));
    // 移除不存在的工具是幂等操作，不该报错
    expectOk('移除不存在的工具应幂等成功',
      await call('POST', `/api/tools/${SESSION}/remove/NoSuchTool${process.pid}`));
  }

  if (shouldRun('session')) {
    expectOk('暂停会话', await call('POST', `/api/sessions/${SESSION}/pause`));
    let r = await call('GET', `/api/sessions/${SESSION}`);
    assertEq('暂停后状态为 PAUSED', r.json?.data?.status, 'PAUSED');

    // 暂停期间只读查询仍应可用（requireExistingSession 不拒 PAUSED）
    expectOk('暂停期间仍可查询 token 统计', await call('GET', `/api/sessions/${SESSION}/tokens`));
    expectOk('暂停期间仍可读取消息历史', await call('GET', `/api/sessions/${SESSION}/messages`));

    // 重复暂停 → IllegalStateException → 必须是结构化 4xx 而非 500
    expectClientError('重复暂停返回结构化错误（非 500）',
      await call('POST', `/api/sessions/${SESSION}/pause`));

    expectOk('恢复会话', await call('POST', `/api/sessions/${SESSION}/resume`));
    r = await call('GET', `/api/sessions/${SESSION}`);
    assertEq('恢复后状态为 ACTIVE', r.json?.data?.status, 'ACTIVE');

    // 重复恢复应幂等（resume 走 requireExistingSession，不校验状态）
    expectOk('重复恢复应幂等成功', await call('POST', `/api/sessions/${SESSION}/resume`));
  }

  // ── 3. 错误响应契约（守护 ApiExceptionHandler）─────────────────────
  if (shouldRun('contract')) {
    section('错误响应契约');
    const nope = `nope-${process.pid}`;

    // 这些端点把 sessionId 直接交给 SDK，会话不存在时 SDK 抛 IllegalArgumentException。
    // 没有全局异常处理器就会变成 Spring 默认 500 白页 —— 前端只能拿到一段 HTML。
    for (const p of [`/api/sessions/${nope}/tokens`, `/api/metrics/${nope}`]) {
      expectClientError(`GET ${p} 返回结构化 4xx`, await call('GET', p));
    }
    for (const p of [`/api/sessions/${nope}/pause`, `/api/sessions/${nope}/resume`]) {
      expectClientError(`POST ${p} 返回结构化 4xx`, await call('POST', p));
    }

    // 响应体必须是 JSON 而非 HTML —— 这是「500 白页」最直接的判据
    const r = await call('GET', `/api/metrics/${nope}`);
    assertNotContains('错误响应不是 HTML 白页', r.body, '<html');
    assertContains('错误响应是 ApiResponse JSON', r.body, '"success"');

    // 反例：会话工具查询对不存在的会话返回空列表（hms-core 既有设计，非 bug）
    const t = await call('GET', `/api/tools/${nope}`);
    if (expectOk('不存在会话的工具查询返回成功（空列表语义）', t)) {
      assertEq('返回空列表', (t.json.data ?? []).length, 0);
    }

    // 取消是幂等静默操作
    expectOk('取消不存在的会话应静默成功', await call('POST', `/api/sessions/${nope}/cancel`));

    // 畸形 JSON 不该 500
    const bad = await call('POST', '/api/sessions', '{"sessionPrompt":');
    if (bad.status >= 400 && bad.status < 500) pass('畸形 JSON 返回 4xx');
    else fail('畸形 JSON 返回 4xx', `实际 HTTP ${bad.status}`);
  }

  // ── 4. 权限体系 ───────────────────────────────────────────────────
  if (shouldRun('permission')) {
    section('权限体系');

    let r = await call('GET', '/api/permissions');
    if (expectOk('查询权限状态', r)) {
      info(`当前模式=${r.json.data?.mode}`);
      assertContains('响应含 rules 数组', r.body, '"rules"');
    }

    for (const mode of ['STRICT', 'SAFE', 'DEFAULT', 'TRUSTED', 'BYPASS']) {
      if (expectOk(`切换权限模式 → ${mode}`, await call('PUT', '/api/permissions/mode', { mode }))) {
        r = await call('GET', '/api/permissions');
        assertEq(`查询回显模式 ${mode}`, r.json?.data?.mode, mode);
      }
    }

    expectFailResponse('非法权限模式应被拒绝',
      await call('PUT', '/api/permissions/mode', { mode: 'NOT_A_MODE' }));
    expectOk('权限模式大小写不敏感（default → DEFAULT）',
      await call('PUT', '/api/permissions/mode', { mode: 'default' }));
    r = await call('GET', '/api/permissions');
    assertEq('小写输入被规范化为 DEFAULT', r.json?.data?.mode, 'DEFAULT');

    // 工具级规则（description=* → PermissionRule.forTool）
    expectOk('添加工具级 DENY 规则',
      await call('POST', '/api/permissions/rules', { toolName: 'WebFetch', description: '*', action: 'deny' }));
    r = await call('GET', '/api/permissions');
    {
      const rule = (r.json?.data?.rules ?? []).find((x) => x.toolName === 'WebFetch');
      assertEq('工具级规则的 commandPattern 为 *', rule?.commandPattern, '*');
      assertEq('工具级规则的 behavior 为 DENY', rule?.behavior, 'DENY');
    }

    // 命令前缀规则（description 非 * → PermissionRule.forCommand，落 "前缀:*"）
    expectOk('添加命令前缀 ALLOW 规则',
      await call('POST', '/api/permissions/rules', { toolName: 'Bash', description: 'git', action: 'allow' }));
    r = await call('GET', '/api/permissions');
    {
      const rule = (r.json?.data?.rules ?? []).find((x) => x.toolName === 'Bash');
      assertEq('命令前缀规则落为 git:*', rule?.commandPattern, 'git:*');
    }

    expectFailResponse('空 toolName 应被拒绝',
      await call('POST', '/api/permissions/rules', { toolName: '', description: '*', action: 'allow' }));
    expectFailResponse('非法 action 应被拒绝',
      await call('POST', '/api/permissions/rules', { toolName: 'WebSearch', description: '*', action: 'NOT_AN_ACTION' }));

    expectOk('清空所有规则', await call('DELETE', '/api/permissions/rules'));
    r = await call('GET', '/api/permissions');
    assertEq('清空后规则数为 0', (r.json?.data?.rules ?? []).length, 0);
  }

  // ── 5. 同步对话 + 多轮上下文 ───────────────────────────────────────
  if (shouldRun('chat')) {
    section('同步对话与多轮上下文');
    if (!hasModel) skip('同步对话', '模型链路不可用');
    else {
      let r = await call('POST', `/api/chat/${SESSION}`, { message: '只回复两个字：收到' });
      if (expectOk('发送消息并拿到回复', r)) {
        const d = r.json.data;
        info(`回复：${preview(d.content, 80)}`);
        if (d.content) pass('回复内容非空'); else fail('回复内容非空', `content=[${d.content}]`);
        // totalTokens 恒 0 说明 TokenTracker 未接上
        assertGt('token 用量已记账', d.totalTokens, 0);
        assertEq('未被中断', d.interrupted, false);
        assertNumber('响应含 toolCallsCount', d.toolCallsCount);
      }

      r = await call('POST', `/api/chat/${SESSION}`, { message: '我刚让你回复的是哪两个字？只答那两个字。' });
      if (expectOk('第二轮对话', r)) {
        assertContains('模型记得上一轮（多轮上下文生效）', r.json.data?.content, '收到');
      }

      r = await call('GET', `/api/sessions/${SESSION}/messages`);
      if (expectOk('查询消息历史', r)) {
        const msgs = r.json.data ?? [];
        info(`历史 ${msgs.length} 条`);
        assertGt('历史含两轮问答', msgs.length, 3);
        assertContains('历史含 user 角色', r.body, '"user"');
        assertContains('历史含 assistant 角色', r.body, '"assistant"');
      }

      // 会话详情里的 messageCount 应随对话增长
      r = await call('GET', `/api/sessions/${SESSION}`);
      assertGt('会话 messageCount 已增长', r.json?.data?.messageCount, 0);

      r = await call('GET', `/api/sessions/${SESSION}/tokens`);
      if (expectOk('查询 token 统计', r)) {
        const { inputTokens: ti, outputTokens: to, totalTokens: tt } = r.json.data;
        info(`input=${ti} output=${to} total=${tt}`);
        assertGt('会话累计 input token > 0', ti, 0);
        assertGt('会话累计 output token > 0', to, 0);
        // /tokens 是 Controller 手工组装的 Map，这里 totalTokens 反而应该存在
        assertEq('totalTokens = input + output', tt, (ti ?? 0) + (to ?? 0));
      }

      // 空消息应被 SDK 拒绝（HmsErrorCode.INVALID_INPUT）
      r = await call('POST', `/api/chat/${SESSION}`, { message: '' });
      if (r.status === 200 && r.json?.success === true) fail('空消息应被拒绝', '居然成功了');
      else pass('空消息应被拒绝');

      expectFailResponse('向不存在的会话发消息应失败',
        await call('POST', `/api/chat/nope-${process.pid}`, { message: 'hi' }));

      // 暂停中的会话必须拒绝新消息（requireSession 拒 PAUSED）
      expectOk('暂停会话以验证拒绝新消息', await call('POST', `/api/sessions/${SESSION}/pause`));
      expectFailResponse('暂停中的会话拒绝新消息',
        await call('POST', `/api/chat/${SESSION}`, { message: '这条应该被拒绝' }));
      expectOk('恢复会话', await call('POST', `/api/sessions/${SESSION}/resume`));
    }
  }

  // ── 6. 工具调用链路 ───────────────────────────────────────────────
  if (shouldRun('toolcall')) {
    section('工具调用链路');
    if (!hasModel) skip('工具调用', '模型链路不可用');
    else {
      // 6a. READ_ONLY 工具应被 headless 策略放行并真实执行。
      // TodoWrite 是 READ_ONLY（只动内存中的 todo 列表），headless 下自动放行。
      let s = await sse(SESSION, '用 TodoWrite 工具创建一条待办：写接口测试。创建完直接说完成。', 150);
      if (s.events.length > 0) {
        if (evHas(s, 'tool_use')) {
          pass('模型自主发起了工具调用（收到 tool_use 事件）');
          info(`tool_use 事件 ${evCount(s, 'tool_use')} 次`);
          const tu = evFirst(s, 'tool_use');
          info(preview(tu.dataRaw));
          assertContains('调用的是 TodoWrite', s.raw, 'TodoWrite');
          // tool_use 事件的三个字段构成对前端的契约（HmsEvent.ToolUse）
          assertHasKey('tool_use 含 toolName 字段', tu.data, 'toolName');
          assertHasKey('tool_use 含 input 字段', tu.data, 'input');
          assertHasKey('tool_use 含 result 字段', tu.data, 'result');
        } else fail('模型自主发起了工具调用', '整个流里没有 tool_use 事件');

        // 工具执行完后循环必须继续，最终给出回复
        if (evHas(s, 'complete')) {
          pass('工具调用后循环继续并完成（收到 complete）');
          const cc = evFirst(s, 'complete').data?.toolCallsCount;
          info(`complete.toolCallsCount=${cc}，tool_use 事件数=${evCount(s, 'tool_use')}`);
          assertGt('complete 的 toolCallsCount > 0', cc, 0);
        } else fail('工具调用后循环继续并完成', '未收到 complete 事件');
      } else fail('工具调用 SSE 流', `无任何事件（HTTP ${s.status}）`);

      // 6b. 工具调用计入 toolCallsCount
      let r = await call('POST', `/api/chat/${SESSION}`, { message: '用 TodoWrite 把那条待办标记为已完成，然后说好了。' });
      if (expectOk('同步模式下的工具调用', r)) {
        info(`toolCallsCount=${r.json.data?.toolCallsCount}`);
        assertGt('工具调用次数被记账', r.json.data?.toolCallsCount, 0);
      }

      // 6c. MEDIUM 风险工具在 headless（无回调）下必须被拒绝。
      // 这是权限的核心断言：TaskCreate 是 MEDIUM，同步 API 无从询问用户，
      // 必须拒绝而非静默放行。拒绝文本会作为工具结果回传给模型。
      //
      // 用独立会话：权限拒绝的那一轮不会往历史里写 assistant 记录，于是历史
      // 出现连续两条 user，上游 API 拒绝这种序列，之后每轮都 500。隔离掉，
      // 别让它污染后续用例。（这是已知产品缺陷，脚本绕开不代表已修。）
      //
      // 另注：toolCallsCount 统计的是「模型请求的工具调用数」，在权限检查之前
      // 累加，被拒绝的调用同样计入。不能用它判断工具是否真的执行了。可靠判据
      // 是拒绝文本有没有作为工具结果回传给模型。模型措辞自由，故匹配同义词组。
      const deniedWords = ['拒绝', 'denied', 'Denied', '权限', 'permission', 'Permission', '不允许'];
      const looksDenied = (t) => t === '' || t == null || deniedWords.some((w) => String(t).includes(w));

      expectOk('清空权限规则（隔离前序用例的残留）', await call('DELETE', '/api/permissions/rules'));
      const strictSession = await newSession('你是权限边界验证助手。');
      if (strictSession) {
        pass('为 STRICT 用例创建独立会话');
        expectOk('切到 STRICT 模式（仅放行 READ_ONLY）',
          await call('PUT', '/api/permissions/mode', { mode: 'STRICT' }));
        r = await call('POST', `/api/chat/${strictSession}`,
          { message: '用 TaskCreate 工具创建一个任务，主题随便。做完告诉我工具返回了什么原文。' });
        if (expectOk('MEDIUM 工具调用（STRICT 模式，无人可询问）', r)) {
          const resp = r.json.data?.content;
          info(`toolCallsCount=${r.json.data?.toolCallsCount} 回复：${preview(resp, 150)}`);
          if (looksDenied(resp)) pass('MEDIUM 工具在 STRICT 下被拒绝（拒绝原文回传或无成功输出）');
          else fail('MEDIUM 工具在 STRICT 下被拒绝',
            `回复既未提及拒绝也非空，可能被静默放行：${preview(resp, 200)}`);
        }
        expectOk('销毁 STRICT 用例会话', await call('DELETE', `/api/sessions/${strictSession}`));
      }

      // 6d. BYPASS 模式下同一工具应放行（反向验证权限确实在起作用）。
      // 必须先清空规则：权限规则是全局状态，残留的 DENY 会让「应放行」假失败
      // —— 规则优先级高于模式，DENY 在 BYPASS 下一样生效（见 6e）。
      expectOk('清空权限规则（隔离前序用例的残留）', await call('DELETE', '/api/permissions/rules'));
      const passSession = await newSession('你是权限验证助手。回答尽量简短。');
      if (passSession) {
        pass('为权限用例创建独立会话');
        expectOk('切到 BYPASS 模式', await call('PUT', '/api/permissions/mode', { mode: 'BYPASS' }));
        r = await call('POST', `/api/chat/${passSession}`,
          { message: '用 TaskCreate 建一个任务，主题叫 bypass-check。完成后只说 ok。' });
        if (expectOk('BYPASS 模式下的 MEDIUM 工具调用', r)) {
          info(`toolCallsCount=${r.json.data?.toolCallsCount} 回复：${preview(r.json.data?.content, 80)}`);
          assertGt('BYPASS 下工具确实执行了', r.json.data?.toolCallsCount, 0);
        }

        // 6e. DENY 规则优先于 BYPASS 模式（规则优先级）。
        // BYPASS 放行一切，但显式 DENY 必须仍生效，否则规则体系形同虚设。
        expectOk('在 BYPASS 下添加 TaskCreate 的 DENY 规则',
          await call('POST', '/api/permissions/rules', { toolName: 'TaskCreate', description: '*', action: 'deny' }));
        const denySession = await newSession('你是规则优先级验证助手。');
        if (denySession) {
          pass('为 DENY 用例创建独立会话');
          r = await call('POST', `/api/chat/${denySession}`,
            { message: '用 TaskCreate 建一个任务叫 deny-check。做完告诉我工具返回了什么原文。' });
          if (expectOk('BYPASS + DENY 规则下的工具调用', r)) {
            const resp = r.json.data?.content;
            info(`回复：${preview(resp, 120)}`);
            if (looksDenied(resp)) pass('DENY 规则优先于 BYPASS 模式（拒绝原文回传或无成功输出）');
            else fail('DENY 规则优先于 BYPASS 模式',
              `BYPASS 下 DENY 规则似未生效：${preview(resp, 200)}`);
          }
          expectOk('销毁 DENY 用例会话', await call('DELETE', `/api/sessions/${denySession}`));
        }
        expectOk('销毁权限用例会话', await call('DELETE', `/api/sessions/${passSession}`));
      }

      expectOk('清除 DENY 规则', await call('DELETE', '/api/permissions/rules'));
      expectOk('切回 DEFAULT 模式', await call('PUT', '/api/permissions/mode', { mode: 'DEFAULT' }));

      // 6f. 多工具串联：一轮里模型应能连续调用多次
      s = await sse(SESSION, '先用 TodoWrite 建三条待办（a、b、c），再用 TodoWrite 把 a 标记完成。全部做完说搞定。', 180);
      if (s.events.length > 0) {
        const n = evCount(s, 'tool_use');
        info(`本轮 tool_use ${n} 次`);
        assertGt('同一轮内多次工具调用（Agent 循环多迭代）', n, 1);
      } else fail('多工具串联 SSE 流', `无任何事件（HTTP ${s.status}）`);

      // 6g. 被移除的工具不该能被调用（会话级注册表真的生效）
      expectOk('从会话移除 TodoWrite', await call('POST', `/api/tools/${SESSION}/remove/TodoWrite`));
      r = await call('POST', `/api/chat/${SESSION}`,
        { message: '用 TodoWrite 建一条待办叫 removed-check。如果没有这个工具就直接说：工具不可用。' });
      if (expectOk('调用已移除的工具', r)) {
        info(`回复：${preview(r.json.data?.content, 150)}`);
        // 工具不在会话注册表里，模型看不到它，不该产生成功的 TodoWrite 调用
        assertEq('已移除的工具未被调用', r.json.data?.toolCallsCount, 0);
      }
      expectOk('恢复会话工具 TodoWrite', await call('POST', `/api/tools/${SESSION}/add/TodoWrite`));
    }
  }

  // ── 7. 上下文压缩 ─────────────────────────────────────────────────
  //
  // 本脚本只打接口、不启停应用，因此窗口取决于被测实例自己的配置。现已可写在
  // application.yml 里（hms-core.context-window / hms-core.reserved-tokens），
  // 环境变量 HMS_CORE_CONTEXT_WINDOW 仍作为回退：
  //   hms-core:
  //     context-window: 200000    # 与模型真实窗口一致，不要为降阈值调小它
  //     reserved-tokens: 170000   # 降阈值靠调大这个：有效窗口 30000、阈值 27900
  // 脚本读不到被测实例的配置文件（它可能在别的机器上），所以要用同名环境变量
  // 把同一组值告知脚本，它才能算出阈值、并据此决定填多少。
  //
  // 为什么不是调小 context-window：它声明的是「模型能吃多少」。配得比模型真实
  // 窗口小，core 会在远未超限时就判超载（日志出现 >100% 占用率）并反复压缩，
  // 而压缩自身要把这段历史发给模型摘要 —— 白压或失败，实测卡到请求超时。
  //
  // 触发条件读自 core 源码（TokenTracker），不是猜的：
  //   effectiveWindow = context-window - reserved-tokens
  //   threshold       = effectiveWindow * 0.93
  //   判据            = lastPromptTokens >= threshold
  // 判据是「最近一次 prompt 的大小」，不是累计用量 —— 累计几十万也不触发。
  //
  // ── 为什么填充走 POST 而不是 SSE ──
  // SSE 端点把 message 放在 URL 查询串里，受 Tomcat 请求头上限（默认 8KB）约束。
  // 要把 prompt 顶到几万 token 需要十万量级的字符，编码后远超该上限，第一个
  // 请求就会被 Tomcat 以 400 挡掉 —— 而且那个 400 长得像「阈值没到」，极易误判。
  // 实测：仅 1595 字符的填充，encodeURIComponent 后已达 12401 字节。
  // 所以填充轮用 POST /api/chat（请求体无此限制），只在最后一轮用 SSE 观测
  // compaction 事件。
  if (shouldRun('compact')) {
    section('上下文压缩');
    if (!hasModel) skip('上下文压缩', '模型链路不可用');
    else {
      const envWin = Number(process.env.HMS_CORE_CONTEXT_WINDOW || 0);
      const thresholdOf = (w) => Math.floor((w - RESERVED_TOKENS) * AUTO_COMPACT_PCT);
      if (!envWin) {
        info('未设 HMS_CORE_CONTEXT_WINDOW，按被测实例的默认窗口 200K 计');
        info(`阈值约 ${thresholdOf(200_000)} token，正常对话难以触及`);
        skip('上下文压缩', '需以小窗口配置被测应用（见本组上方注释）');
      } else if (envWin <= RESERVED_TOKENS) {
        // 有效窗口归零，getUsagePercentage() 恒返回 0，压缩不可能触发
        skip('上下文压缩', `窗口 ${envWin} <= 预留 ${RESERVED_TOKENS}，有效窗口归零，压缩不可能触发`);
      } else {
        const threshold = thresholdOf(envWin);
        info(`窗口 ${envWin}，预留 ${RESERVED_TOKENS}，有效窗口 ${envWin - RESERVED_TOKENS}，`
          + `压缩阈值约 ${threshold} token`);

        const cs = await newSession('你是压缩测试助手。收到资料只需回复其编号，不要复述内容。');
        if (!cs) fail('创建压缩测试会话', '创建失败');
        else {
          pass('创建压缩测试会话');
          let compacted = false;
          let compactEvent = null;
          // 上一轮的历史体积，用于检测「体积骤降 = 压缩已执行」
          let prevHistChars = 0;
          // 压缩是靠体积骤降发现的（而非 compaction 事件）—— 此时没有事件可校验字段
          let compactedByShrink = false;

          /*
           * 每轮填充的目标大小：按阈值的 1/6 估算（字符数）。
           *
           * ── 关于「字符数 ≈ token 数」这个假设 ──
           * 只对**内容多样**的文本近似成立。此前 padOf 生成的是同一句话反复
           * 重复，BPE 对重复串的压缩率高达 4~5 字符/token —— 实测 31539 字符的
           * 历史只折合约 7885 token，始终越不过 13950 的阈值，14 轮跑满仍不触发，
           * 却看起来像 core 不压缩。现在 padOf 用递增编号 + 轮换词表制造多样性，
           * 把密度拉回 1 token/字附近；estimateRatio 是留出的安全系数。
           *
           * 取 1/6 而非 1/4 是为了控制单次请求的耗时：历史每轮都整段重发，
           * prompt 越大模型越慢，而 call() 的超时是固定 300s。实测按 1/4
           * （3487 字符/轮）时第 3 轮就超时 —— 那不是 core 的问题，是单轮塞太多。
           * 上限 20000 字符 —— 再大既拖慢单轮，也可能被上游按 413 拒绝。
           */
          const perRoundChars = Math.min(20_000, Math.max(1_500, Math.floor(threshold / 6)));
          info(`每轮填充约 ${perRoundChars} 字符（内容多样化，≈同量级 token），`
            + `预计 6~8 轮越过阈值`);

          /*
           * 生成指定长度的填充文本。
           *
           * 刻意避免重复：每个片段带全局递增编号，并从词表里轮换取词，让
           * tokenizer 无法把长串压成少量 token。内容依然无意义，但编号可辨识
           * 轮次与位置，排查时能看出历史被截到了哪里。
           */
          let padSeq = 0;
          const PAD_WORDS = ['技术', '流程', '记录', '编号', '批次', '样本',
            '条目', '数据', '章节', '附注', '索引', '摘要'];
          const padOf = (round, chars) => {
            const parts = [];
            let len = 0;
            while (len < chars) {
              const w = PAD_WORDS[padSeq % PAD_WORDS.length];
              const seg = `${w}${round}-${padSeq}：值${(padSeq * 7919) % 100000}。`;
              parts.push(seg);
              len += seg.length;
              padSeq++;
            }
            return parts.join('').slice(0, chars);
          };

          // 轮数上限 14：单轮填充减到阈值的 1/6 后需要更多轮才能攒够历史。
          // 命中阈值即 break，正常情况下 6~8 轮就结束，不会跑满。
          for (let round = 1; round <= 14 && !compacted; round++) {
            const msg = `${padOf(round, perRoundChars)}\n以上是第${round}段资料，`
              + `请用 TodoWrite 记一条待办：读完第${round}段。然后只回复"${round}"。`;

            // 填充轮走 POST。这里不能用 sse()：见本组上方说明。
            // call() 的超时固定 300s，对几万 token 的单轮 prompt 够用。
            const r = await call('POST', `/api/chat/${cs}`, { message: msg });
            if (!r.ok || r.json?.success !== true) {
              fail(`第 ${round} 轮填充对话`,
                `HTTP ${r.status}：${preview(r.body, 200)}`);
              break;
            }

            /*
             * 判断何时该观测，必须估算「下一次请求的 prompt 大小」，不能用累计
             * totalTokens —— 那是所有轮次 input 的总和，每轮都把整段历史重发一遍，
             * 于是累计值远高于真实的单轮 prompt。此前用累计值判断，第 1 轮
             * （累计 19262 > 阈值 13950，而真实单轮 prompt 仅约 9000）就开始观测，
             * 白等好几轮，还把「填充其实没到阈值」误报成 core 缺陷。
             *
             * 改为按历史消息的实际体积估算：下一次 prompt ≈ 系统提示词 + 全部历史。
             * 用字符数近似 token 数 —— 这要求填充内容多样，见 padOf 的说明：
             * 重复文本会被 BPE 压到 1/4~1/5，字符数会严重高估实际 token。
             */
            const hist = await call('GET', `/api/sessions/${cs}/messages`);
            const histChars = (hist.json?.data ?? [])
              .reduce((n, m) => n + String(m?.content ?? '').length, 0);
            const tk = await call('GET', `/api/sessions/${cs}/tokens`);
            const cum = tk.json?.data?.totalTokens ?? 0;

            /*
             * 历史体积骤降 = 压缩已执行。
             *
             * 必须独立于 SSE 事件来判断：压缩发生在「哪一次 API 响应之后」由
             * token 用量决定，而填充轮走的是 POST（无 SSE 连接），此时压缩事件
             * 没有接收端。实测第 4→5 轮历史从 9927 骤降到 1842 字符 —— 压缩确实
             * 成功了，但只监听 SSE 观测轮的脚本完全没看到，误报成「压缩未触发」。
             *
             * 每轮都在追加填充，体积只可能单调增长；一旦回落，唯一的解释就是
             * 历史被压缩替换了。这是压缩生效的直接证据，比事件更可靠。
             */
            if (prevHistChars > 0 && histChars < prevHistChars) {
              pass(`第 ${round} 轮检测到历史被压缩（${prevHistChars} → ${histChars} 字符）`);
              info('压缩发生在 POST 填充轮 —— 该轮无 SSE 连接，故没有 compaction 事件接收端');
              compacted = true;
              compactedByShrink = true;
            }
            prevHistChars = histChars;

            info(`第 ${round} 轮完成（POST 填充 ${perRoundChars} 字符），`
              + `历史 ${histChars} 字符≈同量级 token（这才是下轮 prompt 的量级，`
              + `阈值 ${threshold}）；累计 token=${cum} 仅供参考`);

            /*
             * 历史体积到了阈值量级后，用 SSE 发一轮小消息观测 compaction 事件 ——
             * 此时 prompt 主要由历史构成，已越阈值，无需再塞填充，因此这条消息
             * 很短，不受 URL 长度限制（见本组上方对 8KB 请求头上限的说明）。
             */
            if (histChars >= threshold) {
              info(`历史体积 ${histChars} 已达阈值量级，改用 SSE 观测本轮是否触发压缩`);
              const s = await sse(cs,
                `请用 TodoWrite 记一条待办：汇总。然后只回复"汇总完成"。`, 240);
              if (s.events.length === 0) {
                fail('压缩观测轮（SSE）', `SSE 无任何事件（HTTP ${s.status}）`);
                break;
              }
              info(`观测轮收到 ${s.events.length} 个事件：`
                + `${[...new Set(s.events.map((e) => e.event))].join(' ')}`);
              if (evHas(s, 'compaction')) {
                compacted = true;
                compactEvent = evFirst(s, 'compaction');
              }
            }
          }

          if (compactedByShrink) {
            /*
             * 压缩是靠历史体积骤降发现的 —— 压缩发生在 POST 填充轮，那一轮没有
             * SSE 连接，因此没有 compaction 事件可供校验字段契约。压缩能力本身
             * 已被证实（体积回落只可能来自历史被替换），事件契约留给能拿到事件
             * 的路径去验：手动压缩组走 REST 返回同样的三个字段。
             */
            pass('上下文越过阈值后触发压缩（历史体积回落）');
            skip('compaction 事件字段契约', '本轮压缩发生在 POST 轮，无 SSE 接收端；'
              + '字段契约由手动压缩组覆盖');
          } else if (compacted) {
            pass('上下文越过阈值后触发压缩（收到 compaction 事件）');
            const c = compactEvent;
            info(preview(c.dataRaw, 200));
            // layer 必须是四层之一（含手动触发的 MANUAL）
            const layer = c.data?.layer;
            if (['MICRO', 'SESSION_MEMORY', 'FULL', 'MANUAL'].includes(layer)) {
              pass(`压缩层级合法（${layer}）`);
            } else fail('压缩层级合法', `layer 异常：${layer}`);
            // compaction 事件的字段契约（HmsEvent.Compaction）
            assertHasKey('compaction 含 messagesBefore', c.data, 'messagesBefore');
            assertHasKey('compaction 含 messagesAfter', c.data, 'messagesAfter');
            assertHasKey('compaction 含 reason', c.data, 'reason');

            /*
             * compaction 事件有成功与失败两种形态，断言必须分流。
             *
             * CompactionResult.failure(layer, reason) 把 messagesBefore /
             * messagesAfter 固定填 0（见 CompactionResult:47）—— 失败时本就没有
             * 「压缩前后条数」可言。此前不加区分地断言「条数必须减少」，会在收到
             * 熔断或失败事件时报出 "0 → 0，没有减少" 并指向一个早已修好的 core
             * 缺陷，把「压缩失败」误诊成「计数缺陷」。
             *
             * 靠 reason 判别：成功路径的 reason 由 succeed() 传入（"Auto session
             * memory compact" / "Auto full compact (fallback)"），失败路径则是
             * "Circuit breaker: ..." 或 "All compaction strategies failed"。
             */
            const mb = c.data?.messagesBefore, ma = c.data?.messagesAfter;
            const reason = String(c.data?.reason ?? '');
            const isFailure = /circuit breaker|failed/i.test(reason);

            if (isFailure) {
              info(`消息数 ${mb} → ${ma}（失败事件，该两字段固定为 0）`);
              fail('压缩成功完成（而非失败/熔断）',
                `收到的是失败事件：${reason}\n`
                + `      有效窗口 ${envWin - RESERVED_TOKENS} 可能过小 —— 摘要后的历史`
                + `（系统提示词 + 摘要 + 保留段）仍越阈值，压缩注定失败并最终熔断。`
                + `减小 reserved-tokens 留出更大的有效窗口，或查服务端 error 日志里的 `
                + `"Full compact failed ... last failure:" 看真实原因`);
            } else {
              /*
               * 压缩成功就必须真的减少了消息数。这条断言曾暴露 core 的一个缺陷：
               * AutoCompactManager.succeed 先替换历史再读 before.size()，而两者
               * 是同一个 list 实例（就地 clear()+addAll()），于是恒等 —— 事件里
               * 出现 "4 → 4"，压缩成功却报成没压。
               */
              info(`消息数 ${mb} → ${ma}`);
              if (Number(ma) < Number(mb)) pass(`压缩后消息数确实减少（${mb} → ${ma}）`);
              else fail('压缩后消息数确实减少',
                `${mb} → ${ma}，没有减少。若两数恒等，检查 AutoCompactManager.succeed `
                + '是否在 historyReplacer 就地改写历史之后才读 before.size()');
            }
          } else {
            fail('上下文越过阈值后触发压缩',
              `14 轮填充后仍未收到 compaction 事件。阈值 ${threshold}`
              + `（窗口 ${envWin} - 预留 ${RESERVED_TOKENS}，再 ×${AUTO_COMPACT_PCT}），`
              + '判据为单轮 prompt 大小。注意本组同时用「历史体积骤降」检测压缩，'
              + '因此走到这里意味着两种信号都没出现。按可能性排查：'
              + '①上面每轮打印的「历史 N 字符」始终低于阈值 → 填充量不足，'
              + '减小 reserved-tokens 或加大填充；'
              + '②yml 与环境变量的窗口/预留值不一致 → 脚本算的阈值不是服务端用的；'
              + '③体积已越阈值却始终不回落 → 查服务端日志：有 "Auto-compact triggered" '
              + '则是压缩执行失败（看 "last failure:" 与 "no usable text in any of N '
              + 'generation(s)"），没有则是压缩检查点没走到');
          }

          // 压缩后会话必须仍可用 —— 压坏配对会让下一次请求被服务端 400
          expectOk('压缩后会话仍可正常对话（tool_use/tool_result 配对未被破坏）',
            await call('POST', `/api/chat/${cs}`, { message: '只回复一个字：好' }));

          const msgs = await call('GET', `/api/sessions/${cs}/messages`);
          if (expectOk('压缩后仍能读取消息历史', msgs)) {
            info(`压缩后历史 ${(msgs.json.data ?? []).length} 条`);
          }

          // 压缩后 token 统计不该被清零（累计器语义）
          const tk2 = await call('GET', `/api/sessions/${cs}/tokens`);
          if (expectOk('压缩后 token 统计仍可查询', tk2)) {
            assertGt('压缩后累计 token 未被清零', tk2.json.data?.totalTokens, 0);
          }
          expectOk('销毁压缩测试会话', await call('DELETE', `/api/sessions/${cs}`));
        }
      }
    }

    // ── 手动压缩（POST /{id}/compact）
    //
    // 与上面的自动压缩不同，这里不需要模型：新建会话的历史只有系统提示词，
    // 走的是「没什么可压」分支，压根不会调 AI。
    section('手动压缩');

    const mc = await call('POST', '/api/sessions', { sessionPrompt: '你是手动压缩测试助手。' });
    if (expectOk('创建手动压缩测试会话', mc)) {
      const ms = mc.json.data.sessionId;

      const r = await call('POST', `/api/sessions/${ms}/compact`);
      if (expectOk('新会话手动压缩返回成功响应', r)) {
        // 「历史太短压不动」是正常结果而非错误 —— 必须 200 + compacted:false
        assertEq('历史过短时报告未压缩', r.json.data?.compacted, false);
        // layer 恒为 MANUAL：手动压缩直接走全量层，不经 MICRO/SESSION_MEMORY
        assertEq('压缩层级为 MANUAL', r.json.data?.layer, 'MANUAL');
        assertHasKey('响应含 messagesBefore', r.json.data, 'messagesBefore');
        assertHasKey('响应含 messagesAfter', r.json.data, 'messagesAfter');
        assertHasKey('响应含 reason', r.json.data, 'reason');
        info(`reason: ${r.json.data?.reason}`);
      }

      expectOk('销毁手动压缩测试会话', await call('DELETE', `/api/sessions/${ms}`));
    }

    // 会话不存在 → SDK 抛 IllegalArgumentException → ApiExceptionHandler 转 4xx
    expectClientError('压缩不存在的会话返回结构化失败',
      await call('POST', '/api/sessions/no-such-session/compact'));
  }

  // ── 8. SSE 事件面 ─────────────────────────────────────────────────
  if (shouldRun('stream')) {
    section('SSE 流式对话');
    if (!hasModel) skip('流式对话', '模型链路不可用');
    else {
      const s = await sse(SESSION, '从1数到5，每个数字之间加空格，只输出数字。', 120);
      if (s.events.length > 0) {
        pass('SSE 连接建立并收到数据');
        const kinds = [...new Set(s.events.map((e) => e.event))].sort();
        info(`收到 ${s.events.length} 个事件，类型：${kinds.join(' ')}`);
        if (evHas(s, 'token')) pass('收到 token 事件（逐 token 流式输出）');
        else fail('收到 token 事件', '流中无 token 事件');
        assertGt('token 事件多于 1 个（确实是流式而非一次性）', evCount(s, 'token'), 1);

        if (evHas(s, 'complete')) {
          pass('收到 complete 事件');
          // complete 事件的四字段契约（HmsEvent.Complete，前端 chat-panel.js 依赖）
          const d = evFirst(s, 'complete').data;
          assertHasKey('complete 含 content', d, 'content');
          assertHasKey('complete 含 totalTokens', d, 'totalTokens');
          assertHasKey('complete 含 toolCallsCount', d, 'toolCallsCount');
          assertHasKey('complete 含 interrupted', d, 'interrupted');
          // 正常完成的轮次 interrupted 必须为 false
          assertEq('正常完成时 interrupted=false', d?.interrupted, false);
          if (d?.content) pass('complete.content 为聚合后的完整回复');
          else fail('complete.content 为聚合后的完整回复', 'content 为空');
        } else fail('收到 complete 事件', '流中无 complete');

        // 每个事件都应带可解析的 data —— token 事件的 data 是 JSON 对象 {token:...}
        const badFrames = s.events.filter((e) => e.data === undefined);
        assertEq('所有事件的 data 均可解析为 JSON', badFrames.length, 0);
      } else fail('SSE 连接建立并收到数据', `无任何事件（HTTP ${s.status}）`);

      // ── 运行时活动状态与工具阶段
      //
      // 用一条必然触发工具调用的消息，才能同时覆盖 USING_TOOL 与 tool_use 的
      // START/END 两阶段。
      const act = await sse(SESSION,
          '用 TodoWrite 记一条待办：状态验证。然后只回复"好"。', 120);
      if (evHas(act, 'activity')) {
        pass('收到 activity 事件（运行时活动状态）');
        const states = act.events.filter((e) => e.event === 'activity')
            .map((e) => e.data?.activity);
        info(`状态序列：${states.join(' → ')}`);

        // 每个 activity 事件的三字段契约（HmsEvent.Activity）
        const first = evFirst(act, 'activity').data;
        assertHasKey('activity 含 activity（枚举名）', first, 'activity');
        assertHasKey('activity 含 label（中文文案）', first, 'label');
        assertHasKey('activity 含 detail', first, 'detail');

        // 等待模型响应的那一态必须出现 —— 没有它，未开 extended thinking 时
        // 整段等待期在界面上是空白的
        assertContains('状态序列含 CALLING_MODEL', states.join(','), 'CALLING_MODEL');
        assertContains('状态序列含 RESPONDING', states.join(','), 'RESPONDING');

        // 工具调用态应带上工具名，界面才能显示「调用工具 · TodoWrite」
        const usingTool = act.events.find(
            (e) => e.event === 'activity' && e.data?.activity === 'USING_TOOL');
        if (usingTool) {
          pass('工具执行期间报告 USING_TOOL');
          assertEq('USING_TOOL 的 detail 为工具名', usingTool.data?.detail, 'TodoWrite');
        } else {
          skip('工具执行期间报告 USING_TOOL', '本轮模型未调用工具');
        }
      } else fail('收到 activity 事件', `流中无 activity 事件（HTTP ${act.status}）`);

      // tool_use 分阶段推送 —— 消费方按 phase 分流，否则同一次调用会被当成多次
      if (evHas(act, 'tool_use')) {
        const phases = act.events.filter((e) => e.event === 'tool_use')
            .map((e) => e.data?.phase);
        info(`工具阶段：${phases.join(' → ')}`);
        assertHasKey('tool_use 含 phase', evFirst(act, 'tool_use').data, 'phase');
        const legal = phases.every((p) => ['START', 'PROGRESS', 'END'].includes(p));
        if (legal) pass('tool_use 的 phase 取值合法（START/PROGRESS/END）');
        else fail('tool_use 的 phase 取值合法', `实际：${phases.join(',')}`);
        assertEq('同一次工具调用只有一个 START', phases.filter((p) => p === 'START').length, 1);
        assertEq('同一次工具调用只有一个 END', phases.filter((p) => p === 'END').length, 1);
      } else {
        skip('tool_use 分阶段推送', '本轮模型未调用工具');
      }

      // 不存在的会话：SSE 不该抛异常断连，而应推一条 error 事件后正常收尾
      const es = await sse(`nope-${process.pid}`, 'hi', 20);
      if (evHas(es, 'error')) {
        pass('对不存在的会话推 error 事件（而非断连）');
        const d = evFirst(es, 'error').data;
        info(preview(evFirst(es, 'error').dataRaw));
        assertHasKey('error 事件含 message', d, 'message');
        // 结构化错误码：前端据此分支处理，不必解析 message 文本
        assertHasKey('error 事件含 code', d, 'code');
        assertEq('会话不存在的错误码为 2001', d?.code, 2001);
      } else fail('对不存在的会话推 error 事件', `流中无 error 事件（HTTP ${es.status}）`);
    }

    expectOk('取消执行（空闲时调用也应安全）', await call('POST', `/api/sessions/${SESSION}/cancel`));
  }

  // ── 9. 取消语义 ───────────────────────────────────────────────────
  if (shouldRun('lifecycle')) {
    section('取消与中断语义');
    if (!hasModel) skip('取消语义', '模型链路不可用');
    else {
      // 起一个长任务，收到首个 token 后取消 —— core 的 cancel() 只翻转 volatile
      // 标志，runStreaming 正常返回，complete 事件必须带 interrupted=true
      // 并保留已产生的用量。
      let cancelSent = false;
      const s = await sse(SESSION, '请从1数到300，每个数字单独一行，不要省略。', 120,
        async (ev) => {
          // 等首个 token 到达再取消，确保确实打断了「进行中」的生成
          if (ev.event === 'token' && !cancelSent) {
            cancelSent = true;
            pass('长任务已开始输出（收到首个 token）');
            expectOk('取消进行中的执行', await call('POST', `/api/sessions/${SESSION}/cancel`));
          }
        });

      if (!cancelSent) {
        skip('取消进行中的执行', '长任务未及时产出 token，无法可靠验证中断点');
      } else if (evHas(s, 'complete')) {
        pass('取消后仍收到 complete 事件（而非静默断流）');
        const d = evFirst(s, 'complete').data;
        info(preview(evFirst(s, 'complete').dataRaw, 200));
        assertEq('complete 标记 interrupted=true', d?.interrupted, true);
        // totalTokens 字段必须在（HmsResponse.interrupted 保留用量的重载），
        // 但不断言 > 0 —— 取消可能发生在 usage 回报之前，此时为 0 是合理的。
        assertHasKey('中断的 complete 仍带 totalTokens 字段', d, 'totalTokens');
        info(`中断轮次用量 totalTokens=${d?.totalTokens}`);
      } else {
        fail('取消后仍收到 complete 事件', '流中无 complete —— 中断前的内容丢失了');
      }

      // 取消只影响当轮，会话本身必须仍可用
      const r = await call('GET', `/api/sessions/${SESSION}`);
      if (expectOk('取消后会话仍存在', r)) {
        assertEq('取消后状态回到 ACTIVE', r.json.data?.status, 'ACTIVE');
      }
      expectOk('取消后会话仍可正常对话',
        await call('POST', `/api/chat/${SESSION}`, { message: '只回复一个字：好' }));
    }

    // ── 会话提示词热更新（GET / PUT /{id}/prompt）
    //
    // 不需要模型：只读写系统提示词，不触发任何 AI 调用。
    section('会话提示词热更新');

    const pc = await call('POST', '/api/sessions', { sessionPrompt: '你是提示词测试助手。' });
    if (expectOk('创建提示词测试会话', pc)) {
      const ps = pc.json.data.sessionId;

      const got = await call('GET', `/api/sessions/${ps}/prompt`);
      if (expectOk('读取会话提示词', got)) {
        assertEq('读到创建时设定的提示词', got.json.data?.sessionPrompt, '你是提示词测试助手。');
        // 全局提示词恒非 null，一并返回便于前端展示完整生效内容
        assertHasKey('响应含 globalPrompt', got.json.data, 'globalPrompt');
      }

      expectOk('热更新会话提示词', await call('PUT', `/api/sessions/${ps}/prompt`,
        { sessionPrompt: '你是一名严谨的代码审查员。' }));

      const back = await call('GET', `/api/sessions/${ps}/prompt`);
      if (expectOk('更新后重新读取', back)) {
        assertEq('读回更新后的提示词', back.json.data?.sessionPrompt, '你是一名严谨的代码审查员。');
      }

      // 空白/缺失提示词是无意义输入，应在 HTTP 层就地挡下（200 + success:false）
      expectFailResponse('空白提示词被拒绝',
        await call('PUT', `/api/sessions/${ps}/prompt`, { sessionPrompt: '   ' }));
      expectFailResponse('缺失 sessionPrompt 字段被拒绝',
        await call('PUT', `/api/sessions/${ps}/prompt`, {}));

      // PAUSED 会话应允许改提示词 —— 「暂停 → 调整 → 恢复」是最自然的用法。
      // 钉住实现走的是 requireExistingSession 而非 requireSession。
      if (expectOk('暂停提示词测试会话', await call('POST', `/api/sessions/${ps}/pause`))) {
        expectOk('已暂停的会话仍可更新提示词',
          await call('PUT', `/api/sessions/${ps}/prompt`, { sessionPrompt: '暂停期间也能改。' }));
        expectOk('恢复提示词测试会话', await call('POST', `/api/sessions/${ps}/resume`));
      }

      expectOk('销毁提示词测试会话', await call('DELETE', `/api/sessions/${ps}`));
    }

    // 读取走前置 sessionExists 检查（200 + 失败体），更新则由 SDK 抛异常转 4xx
    expectFailResponse('读取不存在会话的提示词被拒绝',
      await call('GET', '/api/sessions/no-such-session/prompt'));
    expectClientError('更新不存在会话的提示词返回结构化失败',
      await call('PUT', '/api/sessions/no-such-session/prompt', { sessionPrompt: 'x' }));
  }

  // ── 10. 并发与连接管理 ────────────────────────────────────────────
  if (shouldRun('concurrency')) {
    section('并发与连接管理');

    // 多会话并发创建 —— 会话表是 ConcurrentHashMap，不该丢失或串号。
    // Promise.all 只等这几个请求（bash 版的裸 wait 会连被测应用一起等，
    // 而应用永不退出，于是整个脚本在这里永久挂起）。
    const created = await Promise.all(
      Array.from({ length: 5 }, (_, i) =>
        call('POST', '/api/sessions', { sessionPrompt: `并发会话${i + 1}` })));
    const ids = created.map((r) => r.json?.data?.sessionId).filter(Boolean);
    assertEq('并发创建 5 个会话全部成功', ids.length, 5);
    // 会话 ID 必须互不相同
    assertEq('并发创建的会话 ID 互不重复', new Set(ids).size, ids.length);

    const list = await call('GET', '/api/sessions');
    assertGe('列表包含全部并发创建的会话', (list.json?.data ?? []).length, ids.length);

    // 并发销毁
    await Promise.all(ids.map((id) => call('DELETE', `/api/sessions/${id}`)));
    pass(`并发销毁 ${ids.length} 个会话`);

    const checks = await Promise.all(ids.map((id) => call('GET', `/api/sessions/${id}`)));
    if (checks.every((r) => r.json?.success === false)) pass('全部并发会话确认已销毁');
    else fail('全部并发会话确认已销毁', '仍有会话可查询');

    if (hasModel) {
      // 同一会话的第二条 SSE 连接会顶掉第一条（HmsSseBridge.register 的语义）
      const first = sse(SESSION, '从1数到100，每个数字一行。', 60);
      await sleep(3000);
      const second = await sse(SESSION, '只回复：第二条', 60);
      const firstResult = await first;
      if (second.events.length > 0) {
        pass('同一会话的第二条 SSE 连接可建立');
        info(`第一条 ${firstResult.events.length} 个事件，第二条 ${second.events.length} 个事件`);
      } else fail('同一会话的第二条 SSE 连接可建立', `第二条无事件（HTTP ${second.status}）`);

      // 会话在两条连接抢占后必须仍可用
      expectOk('连接抢占后会话仍健康', await call('GET', `/api/sessions/${SESSION}`));

      // 抢占会留下一个客户端已断开、但服务端仍在跑的轮次。它会占着这个会话，
      // 让后续组对同一会话的请求排队甚至超时 —— 那是测试互相干扰而非产品缺陷。
      // 显式取消并留出收尾时间，把干扰关在本组内。
      await call('POST', `/api/sessions/${SESSION}/cancel`);
      await sleep(2000);
    }
  }

  // ── 11. 交互式能力 ────────────────────────────────────────────────
  if (shouldRun('interactive')) {
    section('交互式能力');
    if (!hasModel) skip('交互式能力', '模型链路不可用');
    else {
      // 用独立会话：本组要等模型主动发问再回传，对会话独占性敏感。主会话可能
      // 还挂着 concurrency 组抢占留下的未收尾轮次，复用会让本组随机超时。
      const is = await newSession('你是交互能力验证助手。') ?? SESSION;

      // AskUserQuestion：模型提问 → SSE 推 ask_user → 脚本回传答案 → 循环继续
      let answered = false;
      const s = await sse(is,
        '用 AskUserQuestion 工具问我喜欢猫还是狗，拿到答案后复述我的选择。', 150,
        async (ev) => {
          if (ev.event === 'ask_user' && !answered) {
            answered = true;
            pass('收到 ask_user 事件（模型主动提问）');
            info(preview(ev.dataRaw));
            // HmsEvent.AskUser 的字段契约
            assertHasKey('ask_user 含 question 字段', ev.data, 'question');
            assertHasKey('ask_user 含 options 字段', ev.data, 'options');
            expectOk('回传用户答案',
              await call('POST', `/api/chat/${is}/ask-response`, { response: '猫' }));
          }
        });

      if (!answered) {
        // 模型可能选择不调用该工具 —— 这是模型行为而非 core 缺陷
        skip('AskUserQuestion 交互', '模型本轮未发起提问（模型行为，非 core 问题）');
      } else if (evHas(s, 'complete')) {
        pass('回传答案后循环继续并完成');
        const content = evFirst(s, 'complete').data?.content ?? '';
        if (content.includes('猫')) pass('模型收到了回传的答案（回复里提及「猫」）');
        else info(`回复未直接出现「猫」（模型措辞自由，不算失败）：${preview(content, 120)}`);
      } else fail('回传答案后循环继续并完成', '未收到 complete 事件');

      // 尽力交付语义：无待答请求时回传也返回成功，前端无需处理这种竞态
      expectOk('无待答请求时回传答案也成功（尽力交付语义）',
        await call('POST', `/api/chat/${is}/ask-response`, { response: '迟到的答案' }));
      expectOk('权限确认回传接口可达（无待确认时也应成功）',
        await call('POST', `/api/chat/${is}/permission-response`, { response: 'allow' }));
      // 不存在的会话回传答案同样是尽力交付，不该 500
      expectOk('向不存在的会话回传答案不报错（尽力交付）',
        await call('POST', `/api/chat/nope-${process.pid}/ask-response`, { response: 'x' }));

      if (is !== SESSION) expectOk('销毁交互验证会话', await call('DELETE', `/api/sessions/${is}`));
    }
  }

  // ── 12. 指标 ──────────────────────────────────────────────────────
  if (shouldRun('metrics')) {
    section('指标采集');

    let r = await call('GET', '/api/metrics/overview');
    if (expectOk('查询全局概览', r)) {
      // 字段名以 MetricsController.getOverview 的实际组装为准
      const d = r.json.data;
      info(`活跃会话=${d?.activeSessionCount} 总会话=${d?.totalSessions} 总token=${d?.totalTokens}`);
      for (const k of ['activeSessionCount', 'totalSessions', 'totalInputTokens', 'totalOutputTokens', 'totalTokens']) {
        assertHasKey(`概览含 ${k}`, d, k);
      }
      assertNumber('activeSessionCount 是数字', d?.activeSessionCount);
    }

    // 字段契约在主会话上验（只读，不受其状态影响）
    r = await call('GET', `/api/metrics/${SESSION}`);
    if (expectOk('查询会话指标', r)) {
      // metricsMap 的 key 以 MetricsCollector.toMap() 为准
      const m = r.json.data?.metricsMap ?? {};
      for (const k of ['api_calls', 'user_messages', 'assistant_messages', 'tool_usage',
        'input_tokens', 'output_tokens', 'errors', 'duration_seconds', 'session_id', 'start_time']) {
        assertHasKey(`指标含 ${k}`, m, k);
      }
      assertHasKey('响应含 metricsSummary', r.json.data, 'metricsSummary');
    }

    // 「指标非零」只有在本轮确实对话过时才成立。用 --only metrics 单跑时 chat
    // 组被跳过，会话一句话都没说，此时 api_calls=0 是正确结果而非缺陷 ——
    // 所以这里补一次对话，让断言自带前提，不再隐式依赖组的执行顺序。
    //
    // 必须用独立会话，不能复用主会话：concurrency 组的连接抢占用例会留下一个
    // 被掐断但服务端仍在跑的轮次，主会话可能仍被那一轮占着，补对话会超时 ——
    // 那是测试互相干扰，跟指标能力无关。
    if (!hasModel) skip('指标非零断言', '无可用模型，会话未产生任何调用');
    else {
      const ms = await newSession('你是指标验证助手。回答尽量简短。');
      if (!ms) fail('为指标断言创建独立会话', '创建失败');
      else if (expectOk('为指标断言补一轮对话',
        await call('POST', `/api/chat/${ms}`, { message: '只回复一个字：好' }))) {
        r = await call('GET', `/api/metrics/${ms}`);
        if (expectOk('对话后重新查询会话指标', r)) {
          // 这几项恒 0 说明 MetricsCollector 没接上对应调用点
          const mm = r.json.data?.metricsMap ?? {};
          assertGt('api_calls > 0', mm.api_calls, 0);
          assertGt('user_messages > 0', mm.user_messages, 0);
          assertGt('assistant_messages > 0', mm.assistant_messages, 0);
          assertGt('input_tokens > 0', mm.input_tokens, 0);
          info(`tool_usage=${mm.tool_usage}`);

          // 指标里的 token 数应与 /tokens 端点一致（同一个 TokenTracker）
          const fromMetrics = r.json.data?.inputTokens;
          const tk = await call('GET', `/api/sessions/${ms}/tokens`);
          assertEq('指标与 /tokens 端点的 inputTokens 一致', tk.json?.data?.inputTokens, fromMetrics);
        }
      }
      if (ms) expectOk('销毁指标验证会话', await call('DELETE', `/api/sessions/${ms}`));
    }
  }

  // ── 13. 未覆盖能力（显式标注，避免「没测」被误读为「测过了」）──────
  section('未覆盖能力（无对应 REST 端点）');
  skip('getSessionHooks Hook 扩展点', 'PreToolUse / PostToolUse 仅 Java API 可用');
  skip('getSessionDenials 拒绝审计', 'DenialTracker 仅 Java API 可用');
  skip('sendStreaming(Consumer) 直连流式', 'SSE 走 send + HmsCallbacks，该重载无端点');
  skip('max-sessions 超限拒绝', '需以极小 max-sessions 重启，按约定不纳入');
  skip('max-iterations 截断', '需以极小 max-iterations 重启，按约定不纳入');
  skip('MCP 资源工具实际连接', 'ListMcpResources / ReadMcpResource 需外部 MCP server');
  info('以上能力有 hms-core 侧的单元测试覆盖（见 hms-core/src/test）');

  // ── 14. 清理 ──────────────────────────────────────────────────────
  section('清理');
  expectOk('销毁会话', await call('DELETE', `/api/sessions/${SESSION}`));
  expectFailResponse('销毁后查询应失败', await call('GET', `/api/sessions/${SESSION}`));
  expectFailResponse('销毁不存在的会话应失败',
    await call('DELETE', `/api/sessions/nope-${process.pid}`));

  let r = await call('POST', '/api/sessions/cleanup?idleSeconds=99999');
  if (expectOk('批量清理空闲会话（阈值极大，应清理 0 个）', r)) info(`清理了 ${r.json.data?.cleaned} 个`);
  r = await call('POST', '/api/sessions/cleanup?idleSeconds=0');
  if (expectOk('批量清理空闲会话（阈值 0，清理全部空闲）', r)) info(`清理了 ${r.json.data?.cleaned} 个`);

  expectOk('清理后仍能列出会话', await call('GET', '/api/sessions'));
}

// ── 入口 ────────────────────────────────────────────────────────────────

try {
  await main();
} catch (e) {
  fail('脚本异常终止', e?.stack || String(e));
}

console.log(`\n${C.blue}────────────────────────────────${C.off}`);
console.log(`通过 ${C.green}${stats.pass}${C.off}  失败 ${C.red}${stats.fail}${C.off}  跳过 ${C.yellow}${stats.skip}${C.off}`);

if (stats.fail > 0) {
  console.log(`\n${C.red}失败用例：${C.off}`);
  for (const n of failedNames) console.log(`  · ${n}`);
  process.exit(Math.min(stats.fail, 125));
}

/*
 * 「零失败」不等于「验证通过」。模型链路不通时，chat / toolcall / stream /
 * compact / interactive 这些组会整组跳过 —— 那正是 hms-core 的主干能力（agent
 * 循环、工具调用、流式、压缩）。此时打印"全部通过"并退出 0 是误报：CI 会绿，
 * 而实际上一次模型调用都没发生过。
 *
 * 所以核心组被跳过时退出码取 1，措辞也改成「未完成验证」。跑不需要模型的组
 * （如 --only tool）属于显式意图，不算未完成 —— 用 shouldRun 判定，只在本次
 * 确实打算跑核心组时才追究。
 */
const CORE_GROUPS = ['chat', 'toolcall', 'stream', 'compact', 'interactive'];
const intendedCore = CORE_GROUPS.filter(shouldRun);

if (intendedCore.length > 0 && !hasModel) {
  console.log(`\n${C.yellow}未完成验证：${stats.fail === 0 ? '已跑的用例全部通过，但' : ''}`
    + `核心能力组（${intendedCore.join(' / ')}）因模型链路不可用整组跳过。${C.off}`);
  console.log(`${C.yellow}agent 循环、工具调用、流式、上下文压缩本次均未被验证。${C.off}`);
  process.exit(1);
}

console.log(`\n${C.green}全部通过${C.off}`);
process.exit(0);
