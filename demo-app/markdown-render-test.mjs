/**
 * Markdown 渲染器的注入防护回归测试。
 *
 * 被测代码是浏览器全局脚本（demo-app/src/main/resources/static/js/utils/markdown.js），
 * 不能直接 import —— 这里用 vm 在隔离上下文里执行它，再取回 Markdown 对象。
 * 只用 Node 内置模块，不引入任何依赖。
 *
 * 运行：node demo-app/markdown-render-test.mjs
 */
import { readFileSync } from 'node:fs';
import { strict as assert } from 'node:assert';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const markdownPath = fileURLToPath(
    new URL('./src/main/resources/static/js/utils/markdown.js', import.meta.url));

const sandbox = {};
vm.runInNewContext(
    readFileSync(markdownPath, 'utf8') + '\n;globalThis.__Markdown = Markdown;',
    sandbox,
    { filename: markdownPath });
const Markdown = sandbox.__Markdown;

let passed = 0;
function check(name, fn) {
    fn();
    passed += 1;
    console.log('  ✓ ' + name);
}

console.log('Markdown 渲染器注入防护：');

check('HTML 标签被转义为字面量', () => {
    const html = Markdown.render('<img src=x onerror="alert(1)">');
    assert.ok(!html.includes('<img'), '不得输出原始 <img> 标签：' + html);
    assert.ok(html.includes('&lt;img'), '标签应作为字面量显示：' + html);
});

check('script 标签被转义', () => {
    const html = Markdown.render('<script>alert(1)</script>');
    assert.ok(!html.includes('<script'), '不得输出 <script>：' + html);
});

check('javascript: 链接不生成 <a>', () => {
    const html = Markdown.render('[点我](javascript:alert(1))');
    assert.ok(!html.includes('<a '), '危险协议不得生成链接：' + html);
    assert.ok(!html.toLowerCase().includes('href'), '不得出现 href 属性：' + html);
});

check('大小写混写的协议同样被拒', () => {
    assert.ok(!Markdown.render('[x](JaVaScRiPt:alert(1))').includes('<a '));
});

check('夹入制表符的协议被规范化后拒绝', () => {
    // 浏览器解析 URL 时会忽略内嵌空白，java\tscript: 等同于 javascript:
    assert.ok(!Markdown.render('[x](java\tscript:alert(1))').includes('<a '));
});

check('data: 与 vbscript: 协议被拒', () => {
    assert.ok(!Markdown.render('[x](data:text/html,<script>alert(1)</script>)').includes('<a '));
    assert.ok(!Markdown.render('[x](vbscript:msgbox(1))').includes('<a '));
});

check('相对链接不放行（白名单之外）', () => {
    assert.ok(!Markdown.render('[x](/api/sessions)').includes('<a '));
});

check('http/https/mailto 链接正常生成', () => {
    assert.ok(Markdown.render('[a](https://example.com)')
        .includes('<a href="https://example.com" target="_blank" rel="noopener noreferrer">'));
    assert.ok(Markdown.render('[a](http://example.com)').includes('<a href="http://'));
    assert.ok(Markdown.render('[a](mailto:x@example.com)').includes('<a href="mailto:'));
});

check('URL 中的引号无法撑破属性', () => {
    const html = Markdown.render('[x](https://example.com/"onmouseover="alert(1))');
    assert.ok(!html.includes('"onmouseover='), '引号必须被转义：' + html);
    assert.ok(html.includes('&quot;onmouseover=&quot;'), '引号应以实体形式出现：' + html);
});

check('代码块内容保持转义且不被 Markdown 规则改写', () => {
    const html = Markdown.render('```html\n<img src=x onerror=alert(1)>\n**not bold**\n```');
    assert.ok(!html.includes('<img'), '代码块内不得出现原始标签：' + html);
    assert.ok(html.includes('&lt;img'), '代码块内标签应显示为字面量：' + html);
    assert.ok(!html.includes('<strong>'), '代码块内的星号不应当作强调：' + html);
    assert.ok(html.includes('<pre><code class="language-html">'), '语言标记应保留：' + html);
});

check('行内代码不双重转义', () => {
    const html = Markdown.render('用 `a < b && c` 判断');
    assert.ok(html.includes('<code>a &lt; b &amp;&amp; c</code>'), '应恰好转义一次：' + html);
});

check('常规格式仍然生效', () => {
    assert.ok(Markdown.render('# 标题').includes('<h1>标题</h1>'));
    assert.ok(Markdown.render('**粗体**').includes('<strong>粗体</strong>'));
    assert.ok(Markdown.render('*斜体*').includes('<em>斜体</em>'));
    assert.ok(Markdown.render('- 第一项').includes('<li'));
    assert.ok(Markdown.render('1. 第一项').includes('<li'));
});

check('原文含代码块占位标记时渲染不崩溃', () => {
    assert.doesNotThrow(() => Markdown.render('正文 %%CODEBLOCK_9%% 结尾'));
});

check('& 只转义一次', () => {
    assert.ok(Markdown.render('A & B').includes('A &amp; B'));
    assert.ok(!Markdown.render('A & B').includes('&amp;amp;'));
});

console.log(`\n全部通过（${passed} 项）`);
