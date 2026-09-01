package com.inspirationi.loop.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolRegistry} 的并发安全。
 * <p>
 * 全局 ToolRegistry 是单例 Bean，同时被两类线程访问：
 * <ul>
 *   <li>请求线程 —— 经 {@code ToolManager.registerGlobalTool/removeGlobalTool} 增删</li>
 *   <li>会话创建线程 —— {@code copyGlobalTools()} 遍历 {@code getTools()}</li>
 *   <li>Agent 执行线程 —— {@code toCallbacks()} 遍历构建回调列表</li>
 * </ul>
 * 底层若是无同步的 LinkedHashMap，遍历中发生结构性修改会抛
 * {@link java.util.ConcurrentModificationException}，表现为会话创建随机失败。
 */
class ToolRegistryConcurrencyTest {

    /** 最小工具实现 —— 只需有名字。 */
    private static Tool namedTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\"}";
            }

            @Override
            public String execute(Map<String, Object> input, ToolContext context) {
                return "ok";
            }
        };
    }

    @Test
    void concurrentRegisterAndIterateDoesNotThrow() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        for (int i = 0; i < 40; i++) {
            registry.register(namedTool("seed-" + i));
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        // 写线程：持续增删（模拟 registerGlobalTool / removeGlobalTool）
        Thread.ofVirtual().start(() -> {
            try {
                start.await();
                for (int i = 0; i < 3_000; i++) {
                    registry.register(namedTool("churn-" + i));
                    registry.remove("churn-" + i);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        // 读线程：持续遍历（模拟 copyGlobalTools / toCallbacks）
        Thread.ofVirtual().start(() -> {
            try {
                start.await();
                ToolContext context = ToolContext.defaultContext();
                for (int i = 0; i < 3_000; i++) {
                    registry.getTools();
                    registry.getToolNames();
                    registry.toCallbacks(context);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发读写应在限时内完成");

        assertNull(failure.get(),
                "并发增删与遍历不应抛异常（全局 ToolRegistry 会被请求线程与会话"
                        + "创建线程同时访问），实际：" + failure.get());
    }

    @Test
    void registrationOrderIsPreserved() {
        // 顺序对提示词中的工具清单稳定性有意义，换成 ConcurrentHashMap 会丢失
        ToolRegistry registry = new ToolRegistry();
        registry.register(namedTool("alpha"));
        registry.register(namedTool("beta"));
        registry.register(namedTool("gamma"));

        assertEquals(java.util.List.of("alpha", "beta", "gamma"),
                registry.getTools().stream().map(Tool::name).toList(),
                "注册顺序应被保留");
    }

    @Test
    void snapshotIsIsolatedFromLaterMutation() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(namedTool("a"));

        var snapshot = registry.getTools();
        registry.register(namedTool("b"));

        assertEquals(1, snapshot.size(),
                "getTools() 返回的快照不应被后续注册影响");
        assertEquals(2, registry.size());
    }

    @Test
    void removeAndReRegisterKeepsRegistryConsistent() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(namedTool("x"));
        assertTrue(registry.remove("x"));
        assertTrue(registry.findByName("x").isEmpty());

        registry.register(namedTool("x"));
        assertTrue(registry.findByName("x").isPresent());
        assertEquals(1, registry.size());
    }
}
