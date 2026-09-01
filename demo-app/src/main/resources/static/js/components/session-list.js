/**
 * 会话列表组件。
 */
const SessionList = {
    currentSessionId: null,

    /**
     * 初始化：绑定事件和加载会话列表。
     */
    init() {
        document.getElementById('btn-new-session').addEventListener('click', () => this.createSession());
        this.refresh();
    },

    /**
     * 刷新会话列表。
     */
    async refresh() {
        try {
            const result = await API.sessions.list();
            const sessions = result.data || [];
            this.render(sessions);
        } catch (e) {
            console.error('Failed to load sessions:', e);
        }
    },

    /**
     * 渲染会话列表。
     */
    render(sessions) {
        const container = document.getElementById('session-list');
        if (sessions.length === 0) {
            container.innerHTML = '<div class="empty-state">暂无会话，点击 + 创建</div>';
            return;
        }

        container.innerHTML = sessions
            .filter(s => s.status !== 'DESTROYED')
            .map(s => {
                const isActive = s.sessionId === this.currentSessionId;
                const statusLower = (s.status || '').toLowerCase();
                return `
                <div class="session-item ${isActive ? 'active' : ''}" data-id="${s.sessionId}">
                    <span class="session-item-status ${statusLower}"></span>
                    <div class="session-item-info">
                        <div class="session-item-id">${Format.truncate(s.sessionId, 16)}</div>
                        <div class="session-item-meta">
                            <span>${s.messageCount || 0} 条</span>
                            <span>${Format.number(s.totalTokens ? s.totalTokens() : 0)} tok</span>
                        </div>
                    </div>
                    <div class="session-item-actions">
                        <button class="session-item-action" data-action="delete" title="删除">×</button>
                    </div>
                </div>`;
            })
            .join('');

        // 绑定事件
        container.querySelectorAll('.session-item').forEach(el => {
            el.addEventListener('click', (e) => {
                // 忽略删除按钮点击
                if (e.target.closest('[data-action="delete"]')) return;
                this.selectSession(el.dataset.id);
            });
        });

        container.querySelectorAll('[data-action="delete"]').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.deleteSession(btn.closest('.session-item').dataset.id);
            });
        });
    },

    /**
     * 选择会话。
     */
    selectSession(sessionId) {
        this.currentSessionId = sessionId;
        document.getElementById('current-session-name').textContent = '会话: ' + Format.truncate(sessionId, 12);
        document.getElementById('chat-input').disabled = false;
        document.getElementById('btn-send').disabled = false;

        // 更新高亮
        document.querySelectorAll('.session-item').forEach(el => {
            el.classList.toggle('active', el.dataset.id === sessionId);
        });

        // 通知其他组件
        ChatPanel.onSessionSelected(sessionId);
        Dashboard.onSessionSelected(sessionId);
    },

    /**
     * 创建新会话。
     */
    async createSession() {
        try {
            const result = await API.sessions.create();
            const sessionId = result.data.sessionId;
            await this.refresh();
            this.selectSession(sessionId);
        } catch (e) {
            console.error('Failed to create session:', e);
            alert('创建会话失败: ' + e.message);
        }
    },

    /**
     * 删除会话。
     */
    async deleteSession(sessionId) {
        if (!confirm('确定要销毁会话 ' + Format.truncate(sessionId, 8) + ' 吗？')) return;

        try {
            await API.sessions.destroy(sessionId);
            if (this.currentSessionId === sessionId) {
                this.currentSessionId = null;
                ChatPanel.clear();
                document.getElementById('current-session-name').textContent = '选择一个会话开始';
                document.getElementById('chat-input').disabled = true;
                document.getElementById('btn-send').disabled = true;
            }
            await this.refresh();
        } catch (e) {
            console.error('Failed to destroy session:', e);
        }
    }
};
