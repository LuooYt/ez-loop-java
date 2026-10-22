package com.inspirationi.loop.tool.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索后端回落链。
 * <p>
 * 单一后端（DuckDuckGo）在部分网络下不可达，WebSearch 直接报错。回落链必须：
 * 主后端抛异常时换下一个、返回空结果时也换下一个、全部失败时把每个后端的失败原因
 * 一并抛出 —— 否则调用方只看得到最后一个错误，无从判断是网络问题还是解析问题。
 */
class FallbackSearchProviderTest {

    private static final Map<String, String> NO_OPTIONS = Map.of();

    private static List<Map<String, String>> oneResult(String title) {
        return List.of(Map.of("title", title, "url", "https://example.com", "snippet", ""));
    }

    private static SearchProvider failing(String reason) {
        return (q, n, o) -> {
            throw new java.io.IOException(reason);
        };
    }

    private static SearchProvider returning(List<Map<String, String>> results) {
        return (q, n, o) -> results;
    }

    /** 主后端失败时应回落到下一个，并返回它的结果。 */
    @Test
    void fallsBackWhenPrimaryThrows() throws Exception {
        var provider = new FallbackSearchProvider(List.of(
                failing("Remote host terminated the handshake"),
                returning(oneResult("from-bing"))));

        var results = provider.search("q", 8, NO_OPTIONS);

        assertEquals("from-bing", results.get(0).get("title"));
    }

    /** 主后端返回空结果（页面结构变化、无匹配）同样应尝试下一个。 */
    @Test
    void fallsBackWhenPrimaryReturnsEmpty() throws Exception {
        var provider = new FallbackSearchProvider(List.of(
                returning(List.of()),
                returning(oneResult("from-bing"))));

        assertEquals("from-bing", provider.search("q", 8, NO_OPTIONS).get(0).get("title"));
    }

    /** 主后端成功时不应调用后继后端 —— 回落是有代价的额外请求。 */
    @Test
    void doesNotCallSecondaryWhenPrimarySucceeds() throws Exception {
        AtomicInteger secondaryCalls = new AtomicInteger();
        SearchProvider secondary = (q, n, o) -> {
            secondaryCalls.incrementAndGet();
            return oneResult("secondary");
        };

        var provider = new FallbackSearchProvider(List.of(
                returning(oneResult("primary")), secondary));

        assertEquals("primary", provider.search("q", 8, NO_OPTIONS).get(0).get("title"));
        assertEquals(0, secondaryCalls.get(), "主后端成功却调用了后继后端");
    }

    /** 全部失败时错误信息要包含每个后端的原因，便于定位是网络还是解析问题。 */
    @Test
    void allFailuresAreReported() {
        var provider = new FallbackSearchProvider(List.of(
                failing("ddg handshake"), failing("bing HTTP 503")));

        Exception e = assertThrows(Exception.class, () -> provider.search("q", 8, NO_OPTIONS));

        assertTrue(e.getMessage().contains("ddg handshake"), "缺少主后端失败原因: " + e.getMessage());
        assertTrue(e.getMessage().contains("bing HTTP 503"), "缺少后备后端失败原因: " + e.getMessage());
    }
}
