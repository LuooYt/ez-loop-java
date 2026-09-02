/**
 * Slash 命令补全浮层。
 * <p>
 * 输入 `/` 时浮出候选列表，支持前缀过滤、↑↓ 选择、Tab 补全、Enter 执行、Esc 关闭。
 * 只读 {@link Commands} 的注册表，不持有任何命令语义 —— 新增命令无需改本文件。
 */
const CommandPalette = {
    el: null,
    /** 当前过滤结果（Commands 注册表的子集） */
    items: [],
    activeIndex: 0,
    visible: false,

    /**
     * 初始化：创建浮层容器并挂到输入区。
     * <p>
     * 挂在 .chat-input-area 内而非 body 下，这样浮层随输入区定位（该容器已设
     * position:relative），无需在窗口缩放/滚动时重算坐标。
     */
    init() {
        const area = document.querySelector('.chat-input-area');
        if (!area) return;

        this.el = document.createElement('div');
        this.el.className = 'command-palette';
        this.el.hidden = true;

        // 用 mousedown 而非 click：输入框的 blur 先于 click 触发，会在点击生效前
        // 隐藏浮层导致选择失效。mousedown 早于 blur，无需 setTimeout 绕时序。
        this.el.addEventListener('mousedown', (e) => {
            const item = e.target.closest('.cmd-item');
            if (!item) return;
            e.preventDefault();               // 阻止输入框失焦
            this.activeIndex = Number(item.dataset.index);
            this.accept(true);
        });

        area.appendChild(this.el);
    },

    /**
     * 输入内容变化时刷新候选。
     * <p>
     * 仅在「以 / 开头且不含空白」时显示 —— 一旦敲了空格就说明用户在输入参数
     * （如 `/prompt 你是…`），此时浮层应当让位。
     */
    onInput(value) {
        const text = (value || '').trim();
        if (!/^\/\S*$/.test(text)) {
            this.hide();
            return;
        }

        this.items = Commands.match(text.slice(1).toLowerCase());
        if (this.items.length === 0) {
            // 无匹配时直接隐藏，比显示「无结果」更省事，也不会挡住聊天内容
            this.hide();
            return;
        }

        this.activeIndex = 0;
        this.visible = true;
        this.render();
    },

    /**
     * 处理按键。
     *
     * @return {boolean} true 表示该按键已被浮层消费，调用方不应再处理
     */
    handleKeydown(e) {
        if (!this.visible) return false;

        switch (e.key) {
            case 'ArrowDown':
                e.preventDefault();
                this.move(1);
                return true;
            case 'ArrowUp':
                e.preventDefault();
                this.move(-1);
                return true;
            case 'Tab':
                // 只补全不执行 —— 有参命令需要接着敲参数
                e.preventDefault();
                this.accept(false);
                return true;
            case 'Enter':
                if (e.shiftKey) return false;     // Shift+Enter 仍是换行
                e.preventDefault();
                this.accept(true);
                return true;
            case 'Escape':
                e.preventDefault();
                this.hide();
                return true;
            default:
                return false;
        }
    },

    /** 循环移动选中项。 */
    move(delta) {
        const n = this.items.length;
        this.activeIndex = (this.activeIndex + delta + n) % n;
        this.render();
    },

    /**
     * 采用当前选中项。
     *
     * @param execute true 则立即执行，false 只把命令填进输入框
     */
    accept(execute) {
        const def = this.items[this.activeIndex];
        if (!def) return;

        const input = document.getElementById('chat-input');
        // 补全后带一个空格，便于直接接着敲参数
        input.value = '/' + def.name + (execute ? '' : ' ');
        this.hide();

        if (execute) {
            // 走 sendMessage 而不直接调 runCommand —— 保证「浮层选中执行」与
            // 「手打回车执行」是同一条代码路径，杜绝两种入口行为分叉。
            ChatPanel.sendMessage();
        } else {
            input.focus();
        }
    },

    /** 隐藏浮层。 */
    hide() {
        this.visible = false;
        this.items = [];
        if (this.el) this.el.hidden = true;
    },

    /** 整体重绘 —— 候选最多十余条，无需做差量更新。 */
    render() {
        if (!this.el) return;

        this.el.innerHTML = this.items.map((c, i) => `
            <div class="cmd-item${i === this.activeIndex ? ' active' : ''}" data-index="${i}">
                <span class="cmd-name">/${Format.escapeHtml(c.name)}</span>
                ${c.args ? `<span class="cmd-args">${Format.escapeHtml(c.args)}</span>` : ''}
                <span class="cmd-desc">${Format.escapeHtml(c.desc)}</span>
            </div>
        `).join('');
        this.el.hidden = false;

        // 键盘移动时把选中项滚进可视区
        const active = this.el.querySelector('.cmd-item.active');
        if (active) active.scrollIntoView({ block: 'nearest' });
    }
};
