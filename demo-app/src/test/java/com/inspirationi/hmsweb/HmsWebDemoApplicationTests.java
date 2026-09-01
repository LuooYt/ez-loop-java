package com.inspirationi.hmsweb;

import com.inspirationi.loop.api.HmsSessionManager;
import com.inspirationi.loop.api.PendingResponses;
import com.inspirationi.loop.api.PromptManager;
import com.inspirationi.loop.api.ToolManager;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.web.HmsSseBridge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文加载冒烟测试 —— 同时守护 hms-core 的自动装配。
 * <p>
 * 本应用<b>不</b>声明 {@code scanBasePackages}：hms-core 的 Bean 全部经
 * {@code META-INF/spring/...AutoConfiguration.imports} 自动装配引入。因此这个
 * 测试同时验证了 SDK 对外部集成方可用 —— 若 imports 文件缺失或配置类漏标
 * {@code @AutoConfiguration}，这里会直接失败。
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 不发起真实 API 调用，占位密钥足够完成 Bean 装配
        "spring.ai.anthropic.api-key=sk-test-placeholder",
        "spring.ai.openai.api-key=sk-test-placeholder",
        // 关闭启动时的提示词翻译，避免测试依赖网络
        "hms-core.i18n.enabled=false"
})
class HmsWebDemoApplicationTests {

    @Autowired
    private HmsSessionManager sessionManager;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private PromptManager promptManager;

    @Autowired
    private ToolManager toolManager;

    @Autowired
    private HmsSseBridge sseBridge;

    @Autowired
    private PendingResponses pendingResponses;

    @Test
    void contextLoadsWithAutoConfiguredSdkBeans() {
        assertNotNull(sessionManager, "HmsSessionManager 应由 hms-core 自动装配");
        assertNotNull(promptManager);
        assertNotNull(toolManager);
        assertNotNull(sseBridge, "Web 集成方应拿到 HmsSseBridge");
        assertNotNull(pendingResponses);
    }

    @Test
    void globalToolRegistryIsPopulated() {
        assertNotNull(toolRegistry);
        assertTrue(toolRegistry.size() > 0,
                "全局工具注册表不应为空，实际 size=" + toolRegistry.size());
    }

    @Test
    void sessionLifecycleWorksEndToEnd() {
        String sessionId = sessionManager.createSession("smoke test");
        try {
            assertTrue(sessionManager.sessionExists(sessionId));
            assertNotNull(sessionManager.getSessionInfo(sessionId));

            // 会话应拿到全局工具的独立副本
            assertTrue(sessionManager.getSessionToolRegistry(sessionId).size() > 0,
                    "会话工具副本不应为空");
        } finally {
            sessionManager.destroySession(sessionId);
        }
        assertTrue(!sessionManager.sessionExists(sessionId));
    }
}
