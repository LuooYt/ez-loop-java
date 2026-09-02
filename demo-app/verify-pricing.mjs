#!/usr/bin/env node
/**
 * Token 计费抽象的端到端验证。
 *
 * 单元测试已覆盖定价逻辑本身（BuiltinModelPricingTest / PricingWiringTest），
 * 本脚本只验证测试证明不了的部分：真实 HTTP 响应里的 JSON 契约。这类问题只在
 * 序列化层暴露 —— BigDecimal 会不会变成字符串、null 会不会让 Map.of 抛 500、
 * record 的派生方法会不会意外进 JSON。
 *
 * 用法：node verify-pricing.mjs [baseUrl] [--rate=input,output,cacheRead]
 *   baseUrl 默认 http://localhost:8088
 *   --rate  用于交叉核对的费率（每百万 token 美元），须与运行实例生效的
 *           hms-core.pricing.models.* 一致；默认取内置 opus 费率 15/75/1.5
 *
 * 例：dev profile 里把 opus 配成 10/65/1.2 时
 *   node verify-pricing.mjs http://localhost:8088 --rate=10,65,1.2
 */

const args = process.argv.slice(2);
const BASE = args.find((a) => !a.startsWith('--')) || 'http://localhost:8088';

/**
 * 交叉核对用的费率。
 *
 * 刻意做成参数而非常量：整个抽象的目的就是让 yml 覆盖内置价目表，硬编码默认值
 * 会把「配置成功生效」误报成失败 —— 实测确实发生过（dev 配了 10/65/1.2，
 * 脚本按内置 15/75/1.5 手算，于是报不一致，而服务端其实是对的）。
 */
const RATE = (() => {
  const arg = args.find((a) => a.startsWith('--rate='));
  if (!arg) return { input: 15, output: 75, cacheRead: 1.5, source: '内置默认 opus' };
  const [input, output, cacheRead] = arg.slice('--rate='.length).split(',').map(Number);
  if (![input, output, cacheRead].every(Number.isFinite)) {
    console.error('--rate 格式应为 --rate=input,output,cacheRead，例如 --rate=10,65,1.2');
    process.exit(2);
  }
  return { input, output, cacheRead, source: '命令行指定' };
})();

const rateLabel = () =>
  `${RATE.input}/${RATE.output}/${RATE.cacheRead}（${RATE.source}）`;

let passed = 0;
let failed = 0;

function pass(name, detail = '') {
  passed++;
  console.log(`  \x1b[32m✓\x1b[0m ${name}${detail ? ` — ${detail}` : ''}`);
}

function fail(name, detail) {
  failed++;
  console.log(`  \x1b[31m✗\x1b[0m ${name}\n      ${detail}`);
}

function check(name, cond, detail) {
  cond ? pass(name, typeof cond === 'string' ? cond : '') : fail(name, detail);
}

async function call(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 响应，保留原文 */ }
  return { status: res.status, text, json };
}

async function main() {
  console.log(`\n验证目标：${BASE}\n`);

  // ── 前置：确认实例可达且是带计费的新版本 ──
  console.log('前置检查');
  const probe = await call('POST', '/api/sessions', {});
  if (probe.status !== 200 || !probe.json?.data?.sessionId) {
    console.log(`\n\x1b[31m无法创建会话（HTTP ${probe.status}）\x1b[0m\n${probe.text.slice(0, 200)}`);
    process.exit(1);
  }
  const sid = probe.json.data.sessionId;
  pass('创建会话', sid.slice(0, 8));

  const t0 = await call('GET', `/api/sessions/${sid}/tokens`);
  if (!('cost' in (t0.json?.data ?? {}))) {
    console.log('\n\x1b[31m/tokens 响应里没有 cost 字段 —— 服务端跑的还是旧版本，'
      + '请重新 mvn install hms-core 并重启\x1b[0m');
    process.exit(1);
  }
  pass('服务端已是带计费的版本');

  // ── 1. 零用量：定价已知时 cost 必须是 0，而不是 null ──
  console.log('\n1. 零用量的语义（0 与「未知」必须可区分）');
  {
    const d = t0.json.data;
    check('cost 为 0 而非 null', d.cost === 0,
      `实际 cost=${JSON.stringify(d.cost)} —— 定价已知且没花钱，应当是 0；`
      + 'null 专门用来表达「该模型定价未知」，两者混用会让调用方无从区分');
    check('pricingModel 已注明', typeof d.pricingModel === 'string' && d.pricingModel.length > 0,
      `实际 pricingModel=${JSON.stringify(d.pricingModel)} —— 金额没有依据无法核对`);
    if (d.pricingModel) pass('算费所用模型', d.pricingModel);
  }

  // ── 2. 四类 token 分列 ──
  console.log('\n2. 四类 token 分开呈现（缓存单价差约 10 倍，混算会失真）');
  {
    const d = t0.json.data;
    for (const f of ['inputTokens', 'outputTokens', 'cacheReadTokens', 'cacheCreationTokens']) {
      check(`含 ${f}`, typeof d[f] === 'number', `缺失或类型不对：${JSON.stringify(d[f])}`);
    }
  }

  // ── 3. BigDecimal 的序列化形态 ──
  console.log('\n3. cost 序列化为 JSON 数字而非字符串');
  {
    // Jackson 默认把 BigDecimal 写成数字；若被配成 WRITE_BIGDECIMAL_AS_PLAIN
    // 之类的字符串形态，前端的 Number(cost) 仍能工作，但契约变了值得知道
    const m = t0.text.match(/"cost":\s*("?)([^,}"]*)\1/);
    check('cost 是裸数字（无引号）', m && m[1] === '',
      `原始 JSON 片段：${m ? m[0] : '未匹配到 cost'} —— 若是字符串，`
      + '前端需显式转换，且不同集成方容易处理不一致');
  }

  // ── 4. 会话列表的序列化契约 ──
  console.log('\n4. 会话列表契约（record 派生方法不得进 JSON）');
  {
    const r = await call('GET', '/api/sessions');
    check('列表返回 200', r.status === 200, `HTTP ${r.status}`);
    check('列表含 cost', r.text.includes('"cost"'),
      'SessionInfo 的 cost 应当序列化 —— 它是真实组件而非派生方法');
    check('列表含 pricingModel', r.text.includes('"pricingModel"'), '缺 pricingModel');
    // totalTokens() 是 record 的派生方法，不是组件，因此不该出现在 JSON 里。
    // api-test.mjs 也断言了这一点，此处一并守住，避免加字段时破坏它。
    check('列表不含 totalTokens（派生方法）', !r.text.includes('"totalTokens"'),
      'totalTokens() 是派生方法，出现在 JSON 里说明它被误改成了 record 组件');
  }

  // ── 5. metrics 端点 ──
  console.log('\n5. metrics 端点同步暴露费用');
  {
    const r = await call('GET', `/api/metrics/${sid}`);
    check('metrics 返回 200', r.status === 200, `HTTP ${r.status}`);
    const d = r.json?.data ?? {};
    check('metrics 含 cost', 'cost' in d, `字段列表：${Object.keys(d).join(', ')}`);
    check('metrics 含 pricingModel', 'pricingModel' in d, '缺 pricingModel');
  }

  // ── 6. 真实对话后费用应大于 0 且与用量成比例 ──
  console.log('\n6. 真实对话后的费用（需要可用的模型链路，约 10-30s）');
  {
    const chat = await call('POST', `/api/chat/${sid}`, { message: '只回复两个字：收到' });
    if (chat.status !== 200 || !chat.json?.success) {
      fail('发送对话', `HTTP ${chat.status} — ${chat.text.slice(0, 200)}\n`
        + '      模型链路不可用时本组无法验证，但上面的 JSON 契约已经通过');
    } else {
      pass('对话完成');
      const r = await call('GET', `/api/sessions/${sid}/tokens`);
      const d = r.json.data;
      console.log(`      用量：入 ${d.inputTokens} / 出 ${d.outputTokens}`
        + ` / 缓存读 ${d.cacheReadTokens} / 缓存写 ${d.cacheCreationTokens}`);
      console.log(`      费用：${d.cost}（按 ${d.pricingModel}）`);

      check('已记账 token', d.inputTokens > 0,
        `inputTokens=${d.inputTokens} —— 恒 0 说明 TokenTracker 没接上`);
      check('费用大于 0', typeof d.cost === 'number' && d.cost > 0,
        `cost=${JSON.stringify(d.cost)} —— 有用量却零费用，说明定价没生效`);

      // 交叉核对：按 --rate 给出的费率手算一遍，确认服务端用的就是这套价目表。
      //
      // 费率不能硬编码成内置默认值：整个抽象的目的就是让 yml 能覆盖它。写死
      // 15/75/1.5 会让「配置生效」这件正确的事被报成失败 —— 实测就发生过一次。
      const expected = (d.inputTokens * RATE.input + d.outputTokens * RATE.output
        + d.cacheReadTokens * RATE.cacheRead) / 1e6;
      const drift = Math.abs(d.cost - expected);
      check(`费用与费率 ${rateLabel()} 一致`, drift < 1e-9,
        `服务端 ${d.cost}，手算 ${expected}（差 ${drift}）——\n`
        + '      要么服务端匹配到了别的费率，要么本脚本的 --rate 与 '
        + 'hms-core.pricing.models.* 的实际配置不符。\n'
        + '      对照 application-<profile>.yml 里生效的费率，或用 '
        + '--rate=input,output,cacheRead 显式指定');

      // 缓存写入不该计费（内置实现的已知口径），若被计费则总额会偏高
      if (d.cacheCreationTokens > 0) {
        const ifWriteCharged = expected + d.cacheCreationTokens * RATE.input / 1e6;
        check('缓存写入未被计费', Math.abs(d.cost - ifWriteCharged) > 1e-12
          || d.cacheCreationTokens === 0,
          '费用等于「缓存写入按输入价」的结果 —— 与 BuiltinModelPricing 的既有口径不符');
      }

      // 缓存读取若真的发生过，验证它没被按普通输入价计费
      if (d.cacheReadTokens > 0) {
        const ifChargedAsInput = (d.inputTokens * RATE.input + d.outputTokens * RATE.output
          + d.cacheReadTokens * RATE.input) / 1e6;
        check('缓存读取按其自身单价计费', Math.abs(d.cost - ifChargedAsInput) > 1e-12,
          '费用等于「缓存读按输入价」的结果 —— 缓存读被当成普通输入了，会高估约 10 倍');
      } else {
        console.log('      （本轮无缓存读取，跳过缓存计价校验）');
      }
    }
  }

  // ── 7. 未知模型 → 定价未知（用一个不存在的会话模型无法造，故只做说明）──
  console.log('\n7. 说明');
  console.log('      「未知模型 → cost 为 null」由单元测试覆盖'
    + '（BuiltinModelPricingTest.unknownModelReturnsEmptyInsteadOfGuessing），');
  console.log('      此处无法构造 —— 运行实例的模型名由 ChatModel 配置决定。');

  // ── 汇总 ──
  console.log(`\n${'─'.repeat(60)}`);
  console.log(`通过 ${passed} 项，失败 ${failed} 项`);
  if (failed > 0) {
    console.log('\x1b[31m验证未通过\x1b[0m\n');
    process.exit(1);
  }
  console.log('\x1b[32m全部通过\x1b[0m\n');
}

main().catch((e) => {
  console.error(`\n\x1b[31m脚本异常：${e.message}\x1b[0m`);
  console.error(`（服务端是否在 ${BASE} 运行？）\n`);
  process.exit(1);
});
