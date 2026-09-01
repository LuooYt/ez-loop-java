/**
 * 权限确认 & AI 提问弹窗组件。
 */
const PermissionModal = {
    activeSessionId: null,

    init() {
        // 权限确认弹窗
        const permModal = document.getElementById('permission-modal');
        permModal.querySelectorAll('.modal-actions button').forEach(btn => {
            btn.addEventListener('click', () => {
                const action = btn.dataset.action;
                this.resolve(action);
                permModal.style.display = 'none';
            });
        });

        // AI 提问弹窗
        const askModal = document.getElementById('ask-user-modal');
        document.getElementById('btn-ask-submit').addEventListener('click', () => {
            const input = document.getElementById('ask-user-input');
            const selected = askModal.querySelector('.ask-option.selected');
            const answer = selected ? selected.dataset.value : input.value.trim();
            this.resolveAsk(answer || 'skip');
            askModal.style.display = 'none';
        });
        document.getElementById('btn-ask-skip').addEventListener('click', () => {
            this.resolveAsk('skip');
            askModal.style.display = 'none';
        });
    },

    /**
     * 显示权限确认弹窗。
     */
    showPermission(toolName, description, sessionId) {
        this.activeSessionId = sessionId;

        const modal = document.getElementById('permission-modal');
        modal.querySelector('.perm-tool-name').textContent = '🛠 ' + toolName;
        modal.querySelector('.perm-description').textContent = description;
        modal.style.display = 'flex';
    },

    /**
     * 显示 AI 提问弹窗。
     */
    showAskUser(question, options, sessionId) {
        this.activeSessionId = sessionId;

        const modal = document.getElementById('ask-user-modal');
        modal.querySelector('.ask-question').textContent = question;

        const optionsContainer = modal.querySelector('.ask-options');
        if (options && options.length > 0) {
            optionsContainer.innerHTML = options.map(o =>
                `<span class="ask-option" data-value="${Format.escapeHtml(o)}">${Format.escapeHtml(o)}</span>`
            ).join('');
            optionsContainer.querySelectorAll('.ask-option').forEach(el => {
                el.addEventListener('click', () => {
                    optionsContainer.querySelectorAll('.ask-option').forEach(e => e.classList.remove('selected'));
                    el.classList.add('selected');
                });
            });
            optionsContainer.style.display = 'flex';
            document.getElementById('ask-user-input').style.display = 'none';
        } else {
            optionsContainer.style.display = 'none';
            document.getElementById('ask-user-input').style.display = 'block';
            document.getElementById('ask-user-input').value = '';
            document.getElementById('ask-user-input').focus();
        }

        modal.style.display = 'flex';
    },

    /**
     * 权限确认回调。
     */
    resolve(action) {
        if (!this.activeSessionId) return;

        // 映射 action 到后端期望的值
        let response;
        switch (action) {
            case 'allow':       response = 'allow'; break;
            case 'allow_always': response = 'allow'; break; // TODO: 支持始终允许规则
            case 'deny_once':   response = 'deny'; break;
            case 'deny':        response = 'deny'; break;
            default:            response = 'deny';
        }

        API.chat.permissionResponse(this.activeSessionId, response).catch(e =>
            console.error('Permission response error:', e)
        );
    },

    /**
     * AI 提问回调。
     */
    resolveAsk(answer) {
        if (!this.activeSessionId) return;

        API.chat.askResponse(this.activeSessionId, answer).catch(e =>
            console.error('Ask response error:', e)
        );
    }
};
