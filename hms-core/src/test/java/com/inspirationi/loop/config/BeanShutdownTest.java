package com.inspirationi.loop.config;

import com.inspirationi.loop.core.TaskManager;
import com.inspirationi.loop.mcp.McpManager;
import com.inspirationi.loop.web.HmsSseBridge;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护「持有线程/进程资源的 Bean 必须能被容器自动关闭」这一约束。
 * <p>
 * Spring 对 {@code @Bean} 的 {@code destroyMethod} 默认取 {@code (inferred)}：
 * 只会自动识别 <b>public 无参的 {@code close()} 或 {@code shutdown()}</b>
 * （或实现 {@link AutoCloseable}）。命名不符的清理方法会被静默忽略 —— 资源泄漏
 * 且无任何报错，是最难发现的一类问题。
 * <p>
 * 新增持有 ExecutorService、子进程或长连接的 Bean 时，必须在此登记。
 */
class BeanShutdownTest {

    /** 由 AppConfig / WebBridgeAutoConfiguration 注册、且持有需释放资源的类型。 */
    private static final List<Class<?>> RESOURCE_HOLDING_BEANS = List.of(
            TaskManager.class,      // 持有 virtual thread executor
            McpManager.class,       // 持有 MCP 子进程 / HTTP 连接
            HmsSseBridge.class      // 持有 virtual thread executor
    );

    @Test
    void everyResourceHoldingBeanExposesAnInferrableDestroyMethod() {
        for (Class<?> type : RESOURCE_HOLDING_BEANS) {
            assertTrue(hasInferrableDestroyMethod(type),
                    type.getSimpleName() + " 持有需释放的资源，但没有可被 Spring 推断的"
                            + "销毁方法（public 无参 close() / shutdown()，或实现 AutoCloseable）"
                            + " —— 容器关闭时资源会泄漏且无任何报错");
        }
    }

    private static boolean hasInferrableDestroyMethod(Class<?> type) {
        if (AutoCloseable.class.isAssignableFrom(type)) {
            return true;
        }
        for (String name : new String[]{"close", "shutdown"}) {
            try {
                Method m = type.getMethod(name);
                if (m.getParameterCount() == 0) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // 继续尝试下一个候选名
            }
        }
        return false;
    }
}
