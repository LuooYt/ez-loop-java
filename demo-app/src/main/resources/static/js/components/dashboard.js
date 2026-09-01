/**
 * 仪表盘组件。
 * 管理 Token 用量、工具统计、会话指标、可用工具列表。
 */
const Dashboard = {
    toolCounts: {},

    init() {
        this.loadGlobalTools();
        this.loadPermissionState();
    },

    /**
     * 选中会话时刷新仪表盘。
     */
    onSessionSelected(sessionId) {
        this.refresh(sessionId);
    },

    /**
     * 刷新仪表盘数据。
     */
    async refresh(sessionId) {
        try {
            // Token 统计
            const tokenResult = await API.sessions.tokens(sessionId);
            if (tokenResult.success) {
                document.getElementById('stat-input').textContent = Format.number(tokenResult.data.inputTokens || 0);
                document.getElementById('stat-output').textContent = Format.number(tokenResult.data.outputTokens || 0);
                document.getElementById('stat-total').textContent = Format.number(tokenResult.data.totalTokens || 0);
            }

            // 会话指标
            const metricsResult = await API.metrics.session(sessionId);
            if (metricsResult.success) {
                const d = metricsResult.data;
                document.getElementById('metric-status').textContent = d.status || '--';
                document.getElementById('metric-messages').textContent = d.messageCount || 0;
                document.getElementById('metric-idle').textContent = Format.duration(d.idleSeconds || 0);

                // 从 metricsMap 中获取 API 调用次数
                const apiCalls = d.metricsMap ? d.metricsMap.api_calls : 0;
                document.getElementById('metric-api-calls').textContent = apiCalls || 0;
            }

        } catch (e) {
            console.error('Dashboard refresh error:', e);
        }
    },

    /**
     * 加载全局可用工具列表。
     */
    async loadGlobalTools() {
        try {
            const result = await API.tools.global();
            if (result.success) {
                const container = document.getElementById('available-tools');
                const tools = result.data || [];
                container.innerHTML = tools.map(t =>
                    `<span class="tool-tag" title="${Format.escapeHtml(t)}">${Format.escapeHtml(Format.truncate(t, 18))}</span>`
                ).join('');
            }
        } catch (e) {
            console.error('Failed to load global tools:', e);
        }
    },

    /**
     * 增加工具使用计数。
     */
    incrementTool(toolName) {
        this.toolCounts[toolName] = (this.toolCounts[toolName] || 0) + 1;
        this.renderToolUsage();
    },

    /**
     * 渲染工具使用统计。
     */
    renderToolUsage() {
        const container = document.getElementById('tool-usage');
        const entries = Object.entries(this.toolCounts).sort((a, b) => b[1] - a[1]);

        if (entries.length === 0) {
            container.innerHTML = '<div class="empty-state">暂无工具调用</div>';
            return;
        }

        container.innerHTML = entries.map(([name, count]) => `
            <div class="tool-usage-item">
                <span class="tool-usage-name">${Format.escapeHtml(name)}</span>
                <span class="tool-usage-count">${count}</span>
            </div>
        `).join('');
    },

    /**
     * 加载权限状态。
     */
    async loadPermissionState() {
        try {
            const result = await API.permissions.state();
            if (result.success) {
                const d = result.data;
                document.getElementById('permission-mode').value = d.mode || 'DEFAULT';

                // 渲染规则列表
                const rules = d.rules || [];
                const rulesContainer = document.getElementById('rules-list');
                if (rules.length === 0) {
                    rulesContainer.innerHTML = '<div style="color:var(--text-muted);font-size:11px;">暂无规则</div>';
                } else {
                    rulesContainer.innerHTML = rules.map(r => `
                        <div class="rule-item">
                            <span class="rule-desc">${Format.escapeHtml(r.toolName)}(${Format.escapeHtml(r.commandPattern || '*')})</span>
                            <span class="rule-action ${r.behavior.toLowerCase()}">${r.behavior}</span>
                        </div>
                    `).join('');
                }
            }
        } catch (e) {
            console.error('Failed to load permission state:', e);
        }
    }
};
