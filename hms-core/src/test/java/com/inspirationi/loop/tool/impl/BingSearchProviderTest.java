package com.inspirationi.loop.tool.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bing HTML 结果解析。
 * <p>
 * 默认搜索后端 DuckDuckGo 在国内网络不可达（TLS 握手被中断），WebSearch 只能报错。
 * Bing 作为回落后端，解析逻辑必须对真实页面结构成立：标题 / 链接在
 * {@code <h2><a href>}，摘要在 {@code <p class="b_lineclamp...">}，且标题里常含
 * {@code <strong>} 高亮标签。
 */
class BingSearchProviderTest {

    private static final String HTML = """
            <li class="b_algo" data-id iid=SERP.1><div class="b_tpcn"><a class="tilk"
              href="https://example.com/a" h="ID=SERP,1"><div class="tpic"></div></a></div>
              <h2><a target="_blank" href="https://example.com/a" h="ID=SERP,2">Example <strong>A</strong></a></h2>
              <div class="b_caption"><p class="b_lineclamp2">Snippet A &amp; more</p></div></li>
            <li class="b_algo" data-id iid=SERP.2><h2><a target="_blank"
              href="https://example.com/b" h="ID=SERP,3">Example B</a></h2>
              <div class="b_caption"><p class="b_lineclamp4">Snippet B</p></div></li>
            """;

    /** 每条 b_algo 结果都解析成 title/url/snippet，且去掉 HTML 标签、解码实体。 */
    @Test
    void parsesTitleUrlAndSnippet() {
        List<Map<String, String>> results = new BingSearchProvider().parseResults(HTML, 8);

        assertEquals(2, results.size());
        assertEquals("Example A", results.get(0).get("title"));
        assertEquals("https://example.com/a", results.get(0).get("url"));
        assertEquals("Snippet A & more", results.get(0).get("snippet"));
        assertEquals("Example B", results.get(1).get("title"));
    }

    /** maxResults 是硬上限 —— 多余结果必须截断。 */
    @Test
    void respectsMaxResults() {
        List<Map<String, String>> results = new BingSearchProvider().parseResults(HTML, 1);

        assertEquals(1, results.size());
        assertEquals("https://example.com/a", results.get(0).get("url"));
    }

    /** 页面结构变化（没有任何 b_algo）时返回空列表，而不是抛异常。 */
    @Test
    void returnsEmptyWhenNoResults() {
        assertTrue(new BingSearchProvider().parseResults("<html><body>no results</body></html>", 8).isEmpty());
    }

    /**
     * 摘要里的分隔符实体要解码。
     * <p>
     * 实测结果页的摘要普遍写成 {@code A&ensp;&#0183;&ensp;B}，不解析就会把一串 {@code &xxx;}
     * 原样喂给模型，既占 token 又难读。
     */
    @Test
    void decodesEntitiesInSnippet() {
        String html = """
                <li class="b_algo"><h2><a href="https://example.com/c">C</a></h2>
                <p>A&ensp;&#0183;&ensp;B</p></li>
                """;

        List<Map<String, String>> results = new BingSearchProvider().parseResults(html, 8);

        assertEquals("A · B", results.get(0).get("snippet"));
    }

    /** 相对链接补上 Bing 的域名 —— 否则模型拿到的 URL 无法直接访问。 */
    @Test
    void resolvesRelativeUrls() {
        String html = """
                <li class="b_algo"><h2><a href="/docs/page">Docs</a></h2>
                <p>Snippet</p></li>
                """;

        List<Map<String, String>> results = new BingSearchProvider().parseResults(html, 8);

        assertEquals("https://cn.bing.com/docs/page", results.get(0).get("url"));
    }
}
