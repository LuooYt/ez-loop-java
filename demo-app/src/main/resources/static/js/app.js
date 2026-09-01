/**
 * HMS Web Demo — 应用主入口。
 * 初始化所有组件并绑定全局事件。
 */
(function () {
    'use strict';

    /**
     * 应用初始化。
     */
    function init() {
        // 初始化各组件
        SessionList.init();
        ChatPanel.init();
        ToolLog.init();
        PermissionModal.init();
        Dashboard.init();

        // 应用权限模式按钮
        document.getElementById('btn-apply-mode').addEventListener('click', async () => {
            const mode = document.getElementById('permission-mode').value;
            try {
                await API.permissions.setMode(mode);
                ChatPanel.appendSystemMessage('⚙️ 权限模式已切换为: ' + mode);
            } catch (e) {
                console.error('Failed to set permission mode:', e);
                alert('切换权限模式失败: ' + e.message);
            }
        });

        // 添加权限规则按钮
        document.getElementById('btn-add-rule').addEventListener('click', async () => {
            const tool = document.getElementById('rule-tool').value.trim();
            const pattern = document.getElementById('rule-pattern').value.trim() || '*';
            const action = document.getElementById('rule-action').value;

            if (!tool) {
                alert('请输入工具名');
                return;
            }

            try {
                await API.permissions.addRule(tool, pattern, action);
                document.getElementById('rule-tool').value = '';
                document.getElementById('rule-pattern').value = '';
                await Dashboard.loadPermissionState();
                ChatPanel.appendSystemMessage('✅ 规则已添加: ' + tool + '(' + pattern + ') → ' + action);
            } catch (e) {
                console.error('Failed to add rule:', e);
                alert('添加规则失败: ' + e.message);
            }
        });

        // 定时刷新会话列表（每 30 秒）
        setInterval(() => {
            SessionList.refresh();
        }, 30000);

        console.log('HMS Web Demo initialized');
    }
    // === 启动 ===
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
