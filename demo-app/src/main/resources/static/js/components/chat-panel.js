/**
 * 聊天面板组件。
 * 管理消息显示、流式渲染和消息发送。
 */
const ChatPanel = {
    sseClient: new SSEClient(),
    currentAssistantBubble: null,
    currentStreamContent: '',
    isStreaming: false,
    activeSessionId: null,

    /**
     * 初始化。
     */
    init() {
        const input = document.getElementById('chat-input');
        const sendBtn = document.getElementById('btn-send');
        const cancelBtn = document.getElementById('btn-cancel');
        const clearBtn = document.getElementById('btn-clear-chat');

        input.addEventListener('keydown', (e) => {
            // 补全浮层优先消费按键（↑↓ Tab Enter Esc），返回 true 表示已处理。
            // 这里是浮层与输入框之间唯一的优先级仲裁点。
            if (typeof CommandPalette !== 'undefined' && CommandPalette.handleKeydown(e)) return;

            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
        });

        input.addEventListener('input', () => {
            if (typeof CommandPalette !== 'undefined') CommandPalette.onInput(input.value);
        });
        input.addEventListener('blur', () => {
            if (typeof CommandPalette !== 'undefined') CommandPalette.hide();
        });

        sendBtn.addEventListener('click', () => this.sendMessage());
        cancelBtn.addEventListener('click', () => this.cancelStream());
        clearBtn.addEventListener('click', () => this.clearMessages());

        this.setupSSE();
    },

    /**
     * 设置 SSE 事件处理。
     */
    setupSSE() {
        this.sseClient.on('token', (data) => {
            this.currentStreamContent += data.token || '';
            if (this.currentAssistantBubble) {
                const contentEl = this.currentAssistantBubble.querySelector('.message-content');
                contentEl.innerHTML = Markdown.render(this.currentStreamContent);
                contentEl.classList.add('streaming-cursor');
            }
            // 滚动到底部
            this.scrollToBottom();
        });

        this.sseClient.on('tool_use', (data) => {
            this.appendToolCall(data);
            ToolLog.addEntry(data.toolName, data.result || '', false);
            Dashboard.incrementTool(data.toolName);
        });

        this.sseClient.on('thinking', (data) => {
            this.appendThinking(data.thinking);
        });

        this.sseClient.on('ask_user', (data) => {
            PermissionModal.showAskUser(data.question, data.options || [], this.activeSessionId);
        });

        this.sseClient.on('permission', (data) => {
            PermissionModal.showPermission(data.toolName, data.description, this.activeSessionId);
        });

        // 上下文被自动压缩：历史消息已被摘要或裁剪，提示用户以解释「历史变短了」。
        // MICRO 层就地截断超长工具结果、不改变消息条数，此时两数相等 —— 报
        // 「N → N 条」会读成「压了却没压」，改用后端给出的 reason 描述裁剪量。
        this.sseClient.on('compaction', (data) => {
            const changed = data.messagesBefore !== data.messagesAfter;
            const detail = changed
                ? `${data.messagesBefore} → ${data.messagesAfter} 条`
                : (data.reason || '已裁剪冗余内容');
            this.appendSystemMessage(`🗜 上下文已压缩（${data.layer}）：${detail}`);
        });

        this.sseClient.on('complete', (data) => {
            this.onStreamComplete(data);
        });

        this.sseClient.on('error', (data) => {
            this.appendSystemMessage('❌ ' + this.describeError(data));
            this.onStreamEnd();
        });
    },

    /**
     * 把结构化错误码翻译成可操作的提示。
     * <p>
     * 上游 SDK 的原始 message 常是一串 JSON（如 {@code 403: {"error":{...}}}），
     * 既不便展示也无法稳定匹配，所以优先按 code 分支，只在无法识别时回落到原文。
     * 错误码定义见 hms-core 的 {@code HmsErrorCode}。
     */
    describeError(data) {
        const raw = data.message || '未知错误';
        switch (data.code) {
            case 1003: return '已取消本次执行';
            case 2001: return '会话不存在，可能已被销毁或清理 —— 请新建会话';
            case 2002: return '会话已暂停 —— 请先恢复会话';
            case 3001: return `操作被权限规则拒绝：${raw}`;
            case 5004: return '已达单轮迭代上限 —— 可调大 hms-core.max-iterations，或把任务拆小';
            case 6003: return 'AI 认证失败 —— 请检查 API Key 配置';
            case 6004: return 'AI 配额超限或触发限流 —— 请稍后重试';
            case 7002: return 'AI 调用超时 —— 请稍后重试';
            default:   return data.code ? `错误 ${data.code}: ${raw}` : `错误: ${raw}`;
        }
    },

    /**
     * 发送消息，或分发 slash 命令。
     * <p>
     * 命令分派插在两条守卫之间：空输入照旧直接返回，而 {@code isStreaming} 的拦截
     * 只对普通消息生效 —— 否则 /cancel 永远没有机会执行。普通消息路径的语义与
     * 改造前逐字一致，只是把原先的复合条件拆成了两条。
     */
    sendMessage() {
        const input = document.getElementById('chat-input');
        const message = input.value.trim();
        if (!message) return;

        // 命令不需要会话检查前置到这里 —— /help、/new 在无会话时同样该可用，
        // 具体要求由注册表的 needSession 字段逐条声明。
        const cmd = Commands.parse(message);
        if (cmd) {
            input.value = '';
            if (typeof CommandPalette !== 'undefined') CommandPalette.hide();
            this.runCommand(cmd);
            return;
        }

        if (!SessionList.currentSessionId) {
            alert('请先选择一个会话');
            return;
        }
        if (this.isStreaming) return;

        // 清空输入
        input.value = '';
        this.isStreaming = true;
        this.setButtonsDisabled(true);

        // 显示用户消息
        this.appendUserMessage(message);

        // 创建 AI 回复气泡
        this.currentAssistantBubble = this.appendAssistantBubble();
        this.currentStreamContent = '';

        // 连接 SSE
        this.activeSessionId = SessionList.currentSessionId;
        this.sseClient.connect(this.activeSessionId, message);
    },

    /**
     * 执行一条已解析的 slash 命令 —— 命令的唯一执行入口。
     * <p>
     * 全部前置校验与异常兜底收口在此，各命令的 {@code run} 只管自己的业务：
     * {@code api.js} 仅在 HTTP 非 2xx 时抛，因此后端命令的 4xx 会统一落到这里的
     * catch，而「200 + success:false」的业务失败由各 run 自行分支处理。
     */
    async runCommand({ name, args }) {
        const def = Commands.find(name);
        if (!def) {
            this.appendSystemMessage(`❓ 未知命令 /${name} —— 输入 /help 查看全部命令`);
            return;
        }
        if (!def.duringStream && this.isStreaming) {
            this.appendSystemMessage(`⏳ /${name} 在执行过程中不可用 —— 可先用 /cancel 取消`);
            return;
        }
        if (def.needSession && !SessionList.currentSessionId) {
            this.appendSystemMessage(`⚠️ /${name} 需要先选择一个会话`);
            return;
        }

        // 回显命令本身，让操作在对话流里留下可追溯的痕迹
        this.appendSystemMessage(`/${name}${args ? ' ' + args : ''}`);
        try {
            await def.run({ args, sessionId: SessionList.currentSessionId, panel: this });
        } catch (e) {
            console.error(`Command /${name} failed:`, e);
            this.appendSystemMessage(`❌ /${name} 执行失败: ${e.message}`);
        }
    },

    /**
     * 取消流式输出。
     * <p>
     * 不主动 disconnect —— SSE 一断，后端 {@code send()} 发现 emitter 已移除就静默
     * 丢弃后续事件，其中包括 {@code complete} 里带 {@code interrupted=true} 的
     * 聚合内容，用户会丢掉中断前已生成的那部分回复。正确做法是只发取消请求，
     * 让 Agent 自行收尾并推完 {@code complete}，由服务端关闭连接。
     * <p>
     * 兜底：取消请求失败（网络断了、会话已销毁）时后端不会再推 complete，
     * 此时必须自己断开并复位，否则界面会永久卡在流式状态。
     */
    async cancelStream() {
        if (!this.activeSessionId) return;

        // 立即禁用按钮，避免重复点击；但保留 isStreaming，等 complete 到达再复位
        document.getElementById('btn-cancel').disabled = true;

        try {
            // 取消成功的提示留给 complete 事件（带 interrupted=true）统一给出
            await API.sessions.cancel(this.activeSessionId);
        } catch (e) {
            console.error('Cancel error:', e);
            this.appendSystemMessage('⏹ 取消请求失败，已断开连接');
            this.sseClient.disconnect();
            this.onStreamEnd();
        }
    },

    /**
     * 流式输出完成。
     */
    onStreamComplete(data) {
        if (this.currentAssistantBubble) {
            const contentEl = this.currentAssistantBubble.querySelector('.message-content');
            contentEl.classList.remove('streaming-cursor');
        }
        // 中断的轮次给出明确收尾，否则用户分不清「取消成功」和「还在跑」
        if (data && data.interrupted) {
            this.appendSystemMessage('⏹ 已取消当前执行');
        }
        this.onStreamEnd();
    },

    /**
     * 清理流式状态。
     */
    onStreamEnd() {
        this.isStreaming = false;
        this.setButtonsDisabled(false);
        this.currentAssistantBubble = null;
        this.currentStreamContent = '';

        // 禁用取消按钮
        document.getElementById('btn-cancel').disabled = true;

        // 刷新仪表盘
        if (this.activeSessionId) {
            Dashboard.refresh(this.activeSessionId);
        }
    },

    /**
     * 切换流式状态下的按钮可用性。
     * <p>
     * 刻意**不禁用输入框** —— 流式进行中用户仍需能敲 /cancel。普通消息由
     * {@link #sendMessage} 里的 isStreaming 守卫拦下，非流式命令由
     * {@link #runCommand} 按注册表的 duringStream 字段拒绝，所以放开输入框不会
     * 让消息漏发出去。（会话未选中时的禁用另由 SessionList 负责。）
     */
    setButtonsDisabled(streaming) {
        document.getElementById('btn-send').disabled = streaming;
        document.getElementById('btn-cancel').disabled = !streaming;
    },

    // ===== 消息显示 =====

    /**
     * 追加用户消息。
     */
    appendUserMessage(text) {
        const el = this.createMessageEl('user', text);
        document.getElementById('chat-messages').appendChild(el);
        this.scrollToBottom();
    },

    /**
     * 创建 AI 回复气泡（空，等待流式填充）。
     */
    appendAssistantBubble() {
        const el = this.createMessageEl('assistant', '');
        document.getElementById('chat-messages').appendChild(el);
        this.scrollToBottom();
        return el;
    },

    /**
     * 追加工具调用。
     */
    appendToolCall(data) {
        const toolEl = document.createElement('div');
        toolEl.className = 'tool-call expanded';
        toolEl.innerHTML = `
            <div class="tool-call-header" onclick="this.parentElement.classList.toggle('expanded')">
                <span>🛠 ${Format.escapeHtml(data.toolName)}</span>
                <span style="font-size:10px;margin-left:auto;">▼</span>
            </div>
            <div class="tool-call-body">${Format.escapeHtml(data.result || '(执行中...)')}</div>
        `;

        if (this.currentAssistantBubble) {
            const contentEl = this.currentAssistantBubble.querySelector('.message-content');
            contentEl.appendChild(toolEl);
        } else {
            // 历史回放场景：无正在流式输出的气泡，直接挂到消息容器
            document.getElementById('chat-messages').appendChild(toolEl);
        }
        this.scrollToBottom();
    },

    /**
     * 追加思考内容。
     */
    appendThinking(text) {
        if (!text) return;
        const thinkEl = document.createElement('div');
        thinkEl.className = 'thinking-block';
        thinkEl.textContent = '💭 ' + text;

        if (this.currentAssistantBubble) {
            const contentEl = this.currentAssistantBubble.querySelector('.message-content');
            contentEl.appendChild(thinkEl);
        }
        this.scrollToBottom();
    },

    /**
     * 追加系统消息。
     */
    appendSystemMessage(text) {
        const el = this.createMessageEl('system', text);
        document.getElementById('chat-messages').appendChild(el);
        this.scrollToBottom();
    },

    /**
     * 创建消息元素。
     */
    createMessageEl(role, text) {
        const div = document.createElement('div');
        div.className = `message ${role}`;

        let avatar = '';
        if (role === 'user') avatar = '👤';
        else if (role === 'assistant') avatar = '🤖';
        else avatar = '';

        const contentHtml = role === 'user' ? Format.escapeHtml(text) : (text ? Markdown.render(text) : '');
        div.innerHTML = `
            ${avatar ? `<div class="message-avatar">${avatar}</div>` : ''}
            <div class="message-content">${contentHtml || '<span class="loading-dots">思考中</span>'}</div>
        `;
        return div;
    },

    /**
     * 清空消息区域。
     */
    clearMessages() {
        document.getElementById('chat-messages').innerHTML = '';
    },

    /**
     * 清空所有（切换会话时调用）。
     */
    clear() {
        this.clearMessages();
        this.sseClient.disconnect();
        this.isStreaming = false;
        this.activeSessionId = null;
        this.currentAssistantBubble = null;
        this.currentStreamContent = '';
        this.setButtonsDisabled(false);
    },

    /**
     * 会话被选中时回调：清空当前消息并加载该会话的历史对话。
     */
    onSessionSelected(sessionId) {
        this.clear();
        this.activeSessionId = sessionId;
        this.loadHistory(sessionId);
    },

    /**
     * 加载指定会话的历史消息并回显。
     */
    async loadHistory(sessionId) {
        try {
            const result = await API.sessions.messages(sessionId);
            // 快速切换会话时，丢弃过期响应
            if (this.activeSessionId !== sessionId) return;
            this.renderHistory(result.data || []);
        } catch (e) {
            console.error('Failed to load history:', e);
            if (this.activeSessionId === sessionId) {
                this.appendSystemMessage('⚠️ 历史记录加载失败');
            }
        }
    },

    /**
     * 将历史消息渲染为消息气泡（system 记录不渲染）。
     */
    renderHistory(messages) {
        this.clearMessages();
        for (const m of messages) {
            if (m.role === 'user') {
                this.appendUserMessage(m.content || '');
            } else if (m.role === 'assistant') {
                this.appendAssistantMessage(m.content || '');
            } else if (m.role === 'tool') {
                // 结果为空时退回显示入参，避免"执行中"空态
                this.appendToolCall({ toolName: m.toolName, result: m.toolResult || m.toolArguments || '' });
            }
            // role === 'system' → 不渲染（避免显示系统提示词全文）
        }
        this.scrollToBottom();
    },

    /**
     * 追加已填充内容的助手消息（历史回放场景）。
     */
    appendAssistantMessage(text) {
        if (!text) return;
        const el = this.createMessageEl('assistant', text);
        document.getElementById('chat-messages').appendChild(el);
        this.scrollToBottom();
    },

    /**
     * 滚动到底部。
     */
    scrollToBottom() {
        const container = document.getElementById('chat-messages');
        container.scrollTop = container.scrollHeight;
    }
};
