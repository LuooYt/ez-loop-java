/**
 * 权限确认 & AI 提问弹窗组件。
 */
const PermissionModal = {
    activeSessionId: null,
    /** 当前待确认的工具名 —— 「始终允许 / 始终拒绝」据此落持久规则 */
    activeToolName: null,

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
        this.activeToolName = toolName;

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
     * <p>
     * hms-core 的权限交付只认 allow / deny 两个值（本次生效）。「始终」语义靠
     * 额外落一条持久规则实现：下次同名工具的调用会在权限检查阶段直接命中规则，
     * 不再触发确认弹窗。
     * <p>
     * 规则先落再交付 —— 反过来的话 Agent 可能在规则写入前就走到了下一次同类调用，
     * 于是又弹一次窗，「始终」就失效了。
     */
    async resolve(action) {
        if (!this.activeSessionId) return;

        const always = action === 'allow_always' || action === 'deny_always';
        const allow = action === 'allow' || action === 'allow_always';

        if (always && this.activeToolName) {
            try {
                // description 传 '*' → 后端落工具级规则（权限事件不带可匹配的命令前缀）
                await API.permissions.addRule(this.activeToolName, '*', allow ? 'ALLOW' : 'DENY');
                await Dashboard.loadPermissionState();
            } catch (e) {
                // 规则没落成不影响本次放行，只是下次还会再问
                console.error('Failed to persist permission rule:', e);
            }
        }

        try {
            await API.chat.permissionResponse(this.activeSessionId, allow ? 'allow' : 'deny');
        } catch (e) {
            console.error('Permission response error:', e);
        }
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
