/**
 * 简易 Markdown 渲染器。
 * 支持：标题、列表、粗体、斜体、行内代码、代码块、链接。
 *
 * <b>安全模型：先整体转义，再套格式。</b>模型的输出是不可信输入（提示词注入、
 * 或只是它引用了一段含 HTML 的文件内容），最终会被塞进 {@code innerHTML}。
 * 若先套格式再转义，转义会把已经生成的 {@code <strong>} 一并转成字面量；
 * 若干脆不转义，模型输出里的 {@code <img onerror=...>} 会被浏览器直接执行。
 * 先转义后排版时，原文中不存在任何 HTML 标签，后续替换规则只会从纯文本里
 * 识别 markdown 语法并<em>生成</em>标签，因此 HTML 只能以字面量出现。
 */
const Markdown = {
    /** 生成超链接时允许的 URL 协议 —— 其余（javascript:、data:、vbscript:…）一律不放行。 */
    ALLOWED_HREF_SCHEMES: ['http://', 'https://', 'mailto:'],

    /**
     * 将 Markdown 文本渲染为 HTML。
     */
    render(text) {
        if (!text) return '';

        // 第一步就是转义 —— 见类注释。
        let processed = this.escape(String(text));

        // 先处理代码块（保护它们不被后续规则影响）。内容已在上面转义过，不能再转一次。
        const codeBlocks = [];
        processed = processed.replace(/```(\w*)\n?([\s\S]*?)```/g, (_, lang, code) => {
            const idx = codeBlocks.length;
            codeBlocks.push({ lang, code: code.replace(/\s+$/, '') });
            return `%%CODEBLOCK_${idx}%%`;
        });

        // 行内代码
        processed = processed.replace(/`([^`]+)`/g, (_, code) => `<code>${code}</code>`);

        // 标题
        processed = processed.replace(/^#### (.+)$/gm, '<h4>$1</h4>');
        processed = processed.replace(/^### (.+)$/gm, '<h3>$1</h3>');
        processed = processed.replace(/^## (.+)$/gm, '<h2>$1</h2>');
        processed = processed.replace(/^# (.+)$/gm, '<h1>$1</h1>');

        // 粗体 + 斜体
        processed = processed.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
        processed = processed.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        processed = processed.replace(/\*(.+?)\*/g, '<em>$1</em>');

        // 链接：协议不在白名单内时按原文输出，绝不生成 <a>。
        // href 取自已转义文本，引号已是 &quot;/&#39;，不会撑破属性边界。
        processed = processed.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (match, label, href) => {
            if (!this.isSafeHref(href)) return match;
            return `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`;
        });

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
        processed = processed.replace(/%%CODEBLOCK_(\d+)%%/g, (match, idx) => {
            const block = codeBlocks[parseInt(idx, 10)];
            // 原文恰好含同样的标记时没有对应代码块 —— 原样留下，别让渲染抛异常
            if (!block) return match;
            const langAttr = block.lang ? ` class="language-${block.lang}"` : '';
            return `<pre><code${langAttr}>${block.code}</code></pre>`;
        });

        // 单换行 → <br>
        processed = processed.replace(/\n/g, '<br>');

        return '<p>' + processed + '</p>';
    },

    /**
     * URL 是否允许出现在 href 中。
     * <p>
     * 先剔除空白与控制字符再判协议：浏览器解析 URL 时会忽略内嵌的制表符与换行，
     * {@code java\tscript:alert(1)} 在它眼里就是 {@code javascript:}。
     */
    isSafeHref(href) {
        const normalized = String(href).replace(/[\u0000- \u007f]/g, '').toLowerCase();
        return this.ALLOWED_HREF_SCHEMES.some((scheme) => normalized.startsWith(scheme));
    },

    /**
     * HTML 转义。
     * <p>
     * 引号必须在转义范围内：链接的 href 直接取自转义后的文本，只处理 {@code <>&}
     * 时，一个 {@code "} 就能从属性值里跳出来，在标签内注入新属性。
     */
    escape(text) {
        return String(text).replace(/&/g, '&amp;')
                   .replace(/</g, '&lt;')
                   .replace(/>/g, '&gt;')
                   .replace(/"/g, '&quot;')
                   .replace(/'/g, '&#39;');
    }
};
