/**
 * 工具调用日志组件（底部抽屉）。
 */
const ToolLog = {
    entries: [],

    init() {
        const header = document.getElementById('btn-toggle-tool-log');
        const drawer = document.getElementById('tool-log-drawer');

        header.addEventListener('click', () => {
            const body = document.getElementById('tool-log-body');
            const icon = header.querySelector('.toggle-icon');
            if (body.style.display === 'none') {
                body.style.display = 'block';
                icon.textContent = '▲';
                drawer.style.display = 'block';
            } else {
                body.style.display = 'none';
                icon.textContent = '▼';
            }
        });

        // 默认显示
        document.getElementById('tool-log-drawer').style.display = 'block';
        document.getElementById('tool-log-body').style.display = 'block';
    },

    /**
     * 添加工具日志条目。
     */
    addEntry(toolName, result, isError) {
        this.entries.push({
            time: Format.time(),
            toolName,
            result: Format.truncate(result, 80),
            isError
        });

        // 限制最多 200 条
        if (this.entries.length > 200) {
            this.entries.shift();
        }

        this.render();
    },

    /**
     * 渲染日志。
     */
    render() {
        const body = document.getElementById('tool-log-body');
        const countEl = document.getElementById('tool-log-count');

        countEl.textContent = this.entries.length;

        body.innerHTML = this.entries
            .slice(-50) // 只显示最近 50 条
            .reverse()
            .map(e => `
                <div class="tool-log-entry ${e.isError ? 'has-error' : ''}">
                    <span class="tool-log-time">${e.time}</span>
                    <span class="tool-log-name">${Format.escapeHtml(e.toolName)}</span>
                    <span class="tool-log-result">${Format.escapeHtml(e.result)}</span>
                </div>
            `)
            .join('');
    }
};
