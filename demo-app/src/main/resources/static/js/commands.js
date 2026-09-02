/**
 * Slash 命令注册表。
 * <p>
 * 纯数据 + 纯函数，不碰 DOM —— 命令的执行效果一律通过 ctx 里的协作对象达成，
 * 便于单独推理和测试。渲染与键盘交互分别由 ChatPanel 和 CommandPalette 负责。
 * <p>
 * 命令**不写入后端 messageHistory**：它们是控制台操作而非对话内容，入历史会白白
 * 占用 token 窗口、参与压缩，还会让 AI 把「/clear」当成用户在说话。
 */
const Commands = {

    /**
     * 命令定义表 —— 新增命令只改这里。
     * <p>
     * 字段含义：
     * <ul>
     *   <li>{@code name} —— 不含 `/` 的命令名，拼接时统一补前缀</li>
     *   <li>{@code desc} —— 说明文案，/help 与补全浮层共用一份，杜绝两处漂移</li>
     *   <li>{@code args} —— 参数提示，仅用于展示</li>
     *   <li>{@code needSession} —— 是否要求已选中会话</li>
     *   <li>{@code duringStream} —— 流式输出进行中是否允许执行</li>
     *   <li>{@code run} —— {@code async (ctx) => void}，ctx 为 {args, sessionId, panel}</li>
     * </ul>
     * run 不返回文本由分发器统一渲染 —— /context 要输出多行、/export 压根不输出
     * 文本而是触发下载，统一返回值只会逼出 {text, download, html} 这类杂糅结构。
     */
    registry: [
        {
            name: 'help',
            desc: '列出全部可用命令',
            args: '',
            needSession: false,
            duringStream: false,
            run: async (ctx) => {
                const lines = Commands.registry.map(c => {
                    const usage = '/' + c.name + (c.args ? ' ' + c.args : '');
                    return `- \`${usage}\` — ${c.desc}`;
                });
                ctx.panel.appendSystemMessage(
                    `**可用命令（${Commands.registry.length} 个）**\n\n${lines.join('\n')}\n\n` +
                    '输入 `/` 可唤出补全，↑↓ 选择，Tab 补全，Enter 执行，Esc 关闭。');
            }
        },
        {
            name: 'clear',
            desc: '清空聊天显示（不影响后端历史）',
            args: '',
            needSession: false,
            duringStream: false,
            run: async (ctx) => {
                ctx.panel.clearMessages();
                ctx.panel.appendSystemMessage('🧹 已清空显示 —— 后端对话历史保持不变，AI 仍记得之前的内容');
            }
        },
        {
            name: 'new',
            desc: '新建会话并切换过去',
            args: '',
            needSession: false,
            duringStream: false,
            run: async () => {
                // createSession 自带 try/catch 与失败提示，这里不重复包
                await SessionList.createSession();
            }
        },
        {
            name: 'cancel',
            desc: '取消正在进行的执行',
            args: '',
            needSession: true,
            // 唯一允许在流式中执行的命令 —— 否则它永远没有用武之地
            duringStream: true,
            run: async (ctx) => {
                if (!ctx.panel.isStreaming) {
                    ctx.panel.appendSystemMessage('ℹ️ 当前没有正在执行的任务');
                    return;
                }
                await ctx.panel.cancelStream();
            }
        },
        {
            name: 'context',
            desc: '查看当前会话的 Token 占用',
            args: '',
            needSession: true,
            duringStream: false,
            run: async (ctx) => {
                const r = await API.sessions.tokens(ctx.sessionId);
                if (!r.success) { ctx.panel.appendSystemMessage(`⚠️ ${r.message}`); return; }
                const d = r.data;
                const lines = [
                    `- 输入：${Format.number(d.inputTokens)}`,
                    `- 输出：${Format.number(d.outputTokens)}`,
                    `- 合计：${Format.number(d.totalTokens)}`,
                ];
                // 缓存读取单价约为普通输入的 1/10，单列出来才能解释费用为何低于
                // 「合计 × 输入价」；provider 不报告缓存时恒为 0，无须展示
                if (d.cacheReadTokens > 0) {
                    lines.push(`- 缓存读取：${Format.number(d.cacheReadTokens)}`);
                }
                if (d.cacheCreationTokens > 0) {
                    lines.push(`- 缓存写入：${Format.number(d.cacheCreationTokens)}`);
                }
                lines.push(`- 预估费用：${Format.cost(d.cost, d.pricingModel)}`);
                ctx.panel.appendSystemMessage(`**Token 占用**\n\n${lines.join('\n')}`);
            }
        },
        {
            name: 'cost',
            desc: '查看本会话的用量与指标',
            args: '',
            needSession: true,
            duringStream: false,
            run: async (ctx) => {
                const r = await API.metrics.session(ctx.sessionId);
                if (!r.success) { ctx.panel.appendSystemMessage(`⚠️ ${r.message}`); return; }
                const d = r.data;
                const lines = [
                    `- 状态：${d.status}`,
                    `- 消息数：${d.messageCount}`,
                    `- Token：入 ${Format.number(d.inputTokens)} / 出 ${Format.number(d.outputTokens)} / 合计 ${Format.number(d.totalTokens)}`,
                    `- 预估费用：${Format.cost(d.cost, d.pricingModel)}`,
                    `- 空闲时长：${Format.duration(d.idleSeconds)}`,
                ];
                // metricsSummary 可能为 null（会话尚无任何指标），有才展示
                if (d.metricsSummary) lines.push(`- 指标摘要：${d.metricsSummary}`);
                ctx.panel.appendSystemMessage(`**会话用量**\n\n${lines.join('\n')}`);
            }
        },
        {
            name: 'export',
            desc: '导出当前会话为 Markdown 文件',
            args: '',
            needSession: true,
            duringStream: false,
            run: async (ctx) => {
                const r = await API.sessions.messages(ctx.sessionId);
                if (!r.success) { ctx.panel.appendSystemMessage(`⚠️ ${r.message}`); return; }
                const messages = r.data || [];
                const md = Commands.toMarkdown(ctx.sessionId, messages);

                // Blob + 临时 <a> 触发下载；用完立刻 revoke，否则 URL 会一直占着内存
                const url = URL.createObjectURL(new Blob([md], { type: 'text/markdown;charset=utf-8' }));
                const a = document.createElement('a');
                a.href = url;
                a.download = `session-${ctx.sessionId.slice(0, 8)}.md`;
                a.click();
                URL.revokeObjectURL(url);

                ctx.panel.appendSystemMessage(`💾 已导出 ${messages.length} 条消息为 ${a.download}`);
            }
        },
        {
            name: 'pause',
            desc: '暂停会话（保留上下文，拒绝新消息）',
            args: '',
            needSession: true,
            duringStream: false,
            run: async (ctx) => {
                const r = await API.sessions.pause(ctx.sessionId);
                ctx.panel.appendSystemMessage(
                    r.success ? `⏸ ${r.data}` : `⚠️ ${r.message}`);
            }
        },
        {
            name: 'resume',
            desc: '恢复已暂停的会话',
            args: '',
            needSession: true,
            duringStream: false,
            run: async (ctx) => {
                const r = await API.sessions.resume(ctx.sessionId);
                ctx.panel.appendSystemMessage(
                    r.success ? `▶️ ${r.data}` : `⚠️ ${r.message}`);
            }
        },
        {
            name: 'cleanup',
            desc: '清理空闲会话（默认 1800 秒以上）',
            args: '[空闲秒数]',
            needSession: false,
            duringStream: false,
            run: async (ctx) => {
                const idle = /^\d+$/.test(ctx.args) ? Number(ctx.args) : 1800;
                const r = await API.sessions.cleanup(idle);
                if (!r.success) { ctx.panel.appendSystemMessage(`⚠️ ${r.message}`); return; }
                ctx.panel.appendSystemMessage(
                    `🧽 已清理 ${r.data.cleaned} 个空闲超过 ${Format.duration(idle)} 的会话`);
                await SessionList.refresh();
            }
        },
        {
            name: 'tools',
            desc: '列出当前会话可用的工具',
            args: '',
            needSession: true,
            duringStream: false,
            run: async (ctx) => {
                const r = await API.tools.session(ctx.sessionId);
                if (!r.success) { ctx.panel.appendSystemMessage(`⚠️ ${r.message}`); return; }
                const names = r.data || [];
                if (names.length === 0) {
                    ctx.panel.appendSystemMessage('ℹ️ 该会话没有可用工具');
                    return;
                }
                ctx.panel.appendSystemMessage(
                    `**可用工具（${names.length} 个）**\n\n${names.map(n => '`' + n + '`').join('、')}`);
            }
        },
        {
            name: 'compact',
            desc: '手动压缩上下文（AI 摘要旧消息）',
            args: '',
            needSession: true,
            duringStream: false,
            run: async (ctx) => {
                const r = await API.sessions.compact(ctx.sessionId);
                if (!r.success) { ctx.panel.appendSystemMessage(`⚠️ ${r.message}`); return; }

                const d = r.data;
                if (!d.compacted) {
                    ctx.panel.appendSystemMessage(`ℹ️ 未执行压缩：${d.reason}`);
                    return;
                }
                ctx.panel.appendSystemMessage(
                    `🗜 已压缩（${d.layer}）：${d.messagesBefore} → ${d.messagesAfter} 条`);
                // 历史已被摘要替换，必须重载 —— 否则界面仍显示压缩前的长列表，
                // 与后端实际上下文不一致。
                await ctx.panel.loadHistory(ctx.sessionId);
            }
        },
        {
            name: 'prompt',
            desc: '查看或更新会话提示词',
            args: '[新提示词]',
            needSession: true,
            duringStream: false,
            run: async (ctx) => {
                // 无参查看，带参更新 —— 与 Claude Code 的 /model 同构
                if (!ctx.args) {
                    const r = await API.sessions.prompt(ctx.sessionId);
                    if (!r.success) { ctx.panel.appendSystemMessage(`⚠️ ${r.message}`); return; }
                    const s = r.data.sessionPrompt;
                    ctx.panel.appendSystemMessage(
                        `**当前会话提示词**\n\n${s ? s : '_（未设置，使用默认值）_'}\n\n` +
                        '用 `/prompt <新提示词>` 可热更新（仅内存，重启后回落默认值）。');
                    return;
                }

                const r = await API.sessions.updatePrompt(ctx.sessionId, ctx.args);
                ctx.panel.appendSystemMessage(
                    r.success ? `✏️ ${r.data}` : `⚠️ ${r.message}`);
            }
        }
    ],

    /**
     * 把会话历史渲染成 Markdown。
     * <p>
     * 与界面回放（ChatPanel.renderHistory）保持一致：system 消息不导出 ——
     * 那是系统提示词全文，既冗长也不属于对话内容。
     */
    toMarkdown(sessionId, messages) {
        const head = `# 会话记录 ${sessionId}\n\n导出时间：${new Date().toLocaleString('zh-CN')}\n`;
        const body = messages.map(m => {
            if (m.role === 'user') return `## 👤 用户\n\n${m.content || ''}`;
            if (m.role === 'assistant') return `## 🤖 助手\n\n${m.content || ''}`;
            if (m.role === 'tool') {
                const detail = m.toolResult || m.toolArguments || '';
                return `### 🛠 工具 \`${m.toolName || '未知'}\`\n\n\`\`\`\n${detail}\n\`\`\``;
            }
            return null;
        }).filter(Boolean);
        return head + '\n' + body.join('\n\n') + '\n';
    },

    /** 按命令名精确查找，未命中返回 undefined。 */
    find(name) {
        return this.registry.find(c => c.name === name);
    },

    /** 按前缀过滤，供补全浮层使用。空前缀返回全部。 */
    match(prefix) {
        const p = (prefix || '').toLowerCase();
        return this.registry.filter(c => c.name.startsWith(p));
    },

    /**
     * 解析一行输入。
     * <p>
     * 只认「以 / 开头 + 命令名合法」的形式，返回 {name, args}；否则返回 null
     * 表示这是普通消息。命令名限定 [a-z-] 是为了让「/tmp/foo 这个路径对吗」这类
     * 以斜杠开头的自然语言仍能作为消息发出去。
     */
    parse(text) {
        const m = /^\/([a-z][a-z-]*)(?:\s+([\s\S]*))?$/i.exec((text || '').trim());
        if (!m) return null;
        return { name: m[1].toLowerCase(), args: (m[2] || '').trim() };
    }
};
