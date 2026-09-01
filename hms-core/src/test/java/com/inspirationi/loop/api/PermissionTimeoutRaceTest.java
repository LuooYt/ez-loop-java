package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用户回答超时的竞态。
 * <p>
 * {@link PendingResponses} 的超时兜底与 {@link CallbackResolver} 中
 * {@code Future.get(timeout)} 的等待上限，默认都来自同一个配置键
 * {@code hms-core.user-response-timeout-seconds}，因此两者<b>必然相等</b>。
 * 两个同时到期的超时谁先触发是不确定的：
 * <ul>
 *   <li>PendingResponses 先到 → Future 以 "allow"/"deny"/"skip" 正常完成 → 语义正确</li>
 *   <li>{@code get()} 先到 → 抛 TimeoutException → 走 catch 分支</li>
 * </ul>
 * 因此 catch 分支的兜底值必须与 PendingResponses 的默认值语义一致，否则同一次
 * 超时会因线程调度差异得出不同结论。权限场景两者都必须是「拒绝」。
 */
class PermissionTimeoutRaceTest {

    private static final AgentLoop.PermissionRequest REQUEST =
            new AgentLoop.PermissionRequest("SomeTool", "{}", "doing something");

    /** 只覆写异步回调的 HmsCallbacks —— 同步版弃权，迫使解析器走异步分支。 */
    private static HmsCallbacks asyncOnly(CompletableFuture<String> asyncChoice) {
        return new HmsCallbacks() {
            @Override
            public String onPermissionRequest(String toolName, String description) {
                return null;   // 弃权，转异步
            }

            @Override
            public CompletableFuture<String> onPermissionRequestAsync(
                    String toolName, String description) {
                return asyncChoice;
            }
        };
    }

    /**
     * PendingResponses 的权限默认值与 resolvePermission 的超时兜底必须都是「拒绝」。
     * 二者不一致会让同一次超时因调度差异得出相反结论 —— 而其中一种结论是放行。
     */
    @Test
    void permissionTimeoutIsFailSafeOnBothPaths() {
        // 路径一：PendingResponses 的超时兜底值
        assertEquals("deny", PendingResponses.DEFAULT_PERMISSION_CHOICE,
                "PendingResponses 权限超时必须默认拒绝");

        // 路径二：get() 抛 TimeoutException 后的兜底
        // 1 秒超时 + 永不完成的 Future，强制走 catch 分支
        CallbackResolver resolver = new CallbackResolver(1, "[TEST]");
        assertEquals(PermissionChoice.DENY_ONCE,
                resolver.resolvePermission(asyncOnly(new CompletableFuture<>()), REQUEST),
                "get() 超时后必须拒绝（fail-safe），不得放行");
    }

    /**
     * 异步回调返回 "deny" 之外的任意值都不应被当作放行。
     */
    @Test
    void onlyExplicitAllowGrantsPermission() {
        CallbackResolver resolver = new CallbackResolver(5, "[TEST]");
        for (String response : new String[]{"deny", "skip", "", "ALLOWED", "yes", "1"}) {
            assertEquals(PermissionChoice.DENY_ONCE,
                    resolver.resolvePermission(
                            asyncOnly(CompletableFuture.completedFuture(response)), REQUEST),
                    "非 \"allow\" 的回答 [" + response + "] 不应被视为放行");
        }
    }

    /** 明确的 "allow" 应当放行 —— 确认上一条测试不是因为整条链路都返回拒绝。 */
    @Test
    void explicitAllowIsHonored() {
        CallbackResolver resolver = new CallbackResolver(5, "[TEST]");
        assertEquals(PermissionChoice.ALLOW_ONCE,
                resolver.resolvePermission(
                        asyncOnly(CompletableFuture.completedFuture("allow")), REQUEST));
    }
}
