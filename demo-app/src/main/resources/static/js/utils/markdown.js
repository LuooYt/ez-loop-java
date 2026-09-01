/**
 * 简易 Markdown 渲染器。
 * 支持：标题、列表、粗体、斜体、行内代码、代码块、链接。
 */
const Markdown = {
    /**
     * 将 Markdown 文本渲染为 HTML。
     */
    render(text) {
        if (!text) return '';

        // 先处理代码块（保护它们不被后续规则影响）
        const codeBlocks = [];
        let processed = text.replace(/```(\w*)\n?([\s\S]*?)```/g, (_, lang, code) => {
            const idx = codeBlocks.length;
            codeBlocks.push({ lang, code: this.escape(code.trimEnd()) });
            return `%%CODEBLOCK_${idx}%%`;
        });

        // 行内代码
        processed = processed.replace(/`([^`]+)`/g, (_, code) =>
            `<code>${this.escape(code)}</code>`
        );

        // 标题
        processed = processed.replace(/^#### (.+)$/gm, '<h4>$1</h4>');
        processed = processed.replace(/^### (.+)$/gm, '<h3>$1</h3>');
        processed = processed.replace(/^## (.+)$/gm, '<h2>$1</h2>');
        processed = processed.replace(/^# (.+)$/gm, '<h1>$1</h1>');

        // 粗体 + 斜体
        processed = processed.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
        processed = processed.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        processed = processed.replace(/\*(.+?)\*/g, '<em>$1</em>');

        // 链接
        processed = processed.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');

        // 无序列表
        processed = processed.replace(/^(\s*)[-*] (.+)$/gm, (_, indent, item) => {
            const level = Math.floor(indent.length / 2);
            return '<li style="margin-left:' + (level * 20) + 'px">' + item + '</li>';
        });
        // 有序列表
        processed = processed.replace(/^(\s*)\d+\. (.+)$/gm, (_, indent, item) => {
            const level = Math.floor(indent.length / 2);
            return '<li style="margin-left:' + (level * 20) + 'px">' + item + '</li>';
        });

        // 包裹连续的 <li> 到 <ul>
        processed = processed.replace(/((?:<li[^>]*>.*<\/li>\n?)+)/g, '<ul>$1</ul>');

        // 段落：双换行分隔
        processed = processed.replace(/\n\n+/g, '</p><p>');

        // 还原代码块
        processed = processed.replace(/%%CODEBLOCK_(\d+)%%/g, (_, idx) => {
            const block = codeBlocks[parseInt(idx)];
            const langAttr = block.lang ? ` class="language-${block.lang}"` : '';
            return `<pre><code${langAttr}>${block.code}</code></pre>`;
        });

        // 单换行 → <br>
        processed = processed.replace(/\n/g, '<br>');

        return '<p>' + processed + '</p>';
    },

    /**
     * HTML 转义。
     */
    escape(text) {
        return text.replace(/&/g, '&amp;')
                   .replace(/</g, '&lt;')
                   .replace(/>/g, '&gt;');
    }
};
