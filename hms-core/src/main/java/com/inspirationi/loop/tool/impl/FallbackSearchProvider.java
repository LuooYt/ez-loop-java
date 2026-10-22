package com.inspirationi.loop.tool.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按顺序尝试多个搜索后端的回落链。
 * <p>
 * 单一后端不可用时 WebSearch 直接失败：DuckDuckGo 在国内网络连 TLS 握手都完不成
 * （{@code Remote host terminated the handshake}），而 Bing 可直连。若只部署一个后端，
 * 用户看到的就是一条不明所以的搜索报错。
 * <p>
 * 回落规则：
 * <ul>
 *   <li>后端抛异常 → 记录原因，换下一个；</li>
 *   <li>后端返回空结果 → 同样换下一个（页面改版导致解析为空，效果等同于失败）；</li>
 *   <li>后端返回非空结果 → 立即返回，不再打扰后面的后端（每次回落都是一次真实网络请求）；</li>
 *   <li>全部失败 → 抛异常，消息里带上每个后端各自的失败原因，便于区分是网络不可达还是解析失效。</li>
 * </ul>
 */
public class FallbackSearchProvider implements SearchProvider {

    private final List<SearchProvider> providers;

    /**
     * @param providers 按优先级排列的后端列表，至少一个
     */
    public FallbackSearchProvider(List<SearchProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("FallbackSearchProvider 至少需要一个后端");
        }
        this.providers = List.copyOf(providers);
    }

    @Override
    public List<Map<String, String>> search(String query, int maxResults, Map<String, String> options)
            throws Exception {
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < providers.size(); i++) {
            SearchProvider provider = providers.get(i);
            String name = provider.getClass().getSimpleName();
            try {
                List<Map<String, String>> results = provider.search(query, maxResults, options);
                if (results != null && !results.isEmpty()) {
                    return results;
                }
                failures.add(name + ": 无结果");
            } catch (Exception e) {
                failures.add(name + ": " + describe(e));
            }
        }

        // 逐条列出所有后端的原因：只报最后一个会掩盖「主后端网络不通」这类关键信息。
        throw new java.io.IOException("所有搜索后端均失败 —— " + String.join("; ", failures));
    }

    /** 取异常的可读描述：优先 message，空则退回类名。 */
    private String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
