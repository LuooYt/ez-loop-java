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
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
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

        this.sseClient.on('complete', (data) => {
            this.onStreamComplete(data);
        });

        this.sseClient.on('error', (data) => {
            this.appendSystemMessage('❌ 错误: ' + (data.message || '未知错误'));
            this.onStreamEnd();
        });
    },

    /**
     * 发送消息。
     */
    sendMessage() {
        if (!SessionList.currentSessionId) {
            alert('请先选择一个会话');
            return;
        }

        const input = document.getElementById('chat-input');
        const message = input.value.trim();
        if (!message || this.isStreaming) return;

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
     * 取消流式输出。
     */
    async cancelStream() {
        if (!this.activeSessionId) return;

        this.sseClient.disconnect();
        try {
            await API.sessions.cancel(this.activeSessionId);
            this.appendSystemMessage('⏹ 已取消当前执行');
        } catch (e) {
            console.error('Cancel error:', e);
        }
        this.onStreamEnd();
    },

    /**
     * 流式输出完成。
     */
    onStreamComplete(data) {
        if (this.currentAssistantBubble) {
            const contentEl = this.currentAssistantBubble.querySelector('.message-content');
            contentEl.classList.remove('streaming-cursor');
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

    setButtonsDisabled(streaming) {
        document.getElementById('btn-send').disabled = streaming;
        document.getElementById('btn-cancel').disabled = !streaming;
        document.getElementById('chat-input').disabled = streaming;
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
