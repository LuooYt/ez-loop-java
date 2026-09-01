package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用户回答超时的竞态。
 * <p>
 * {@link PendingResponses} 的超时兜底与 {@code DefaultHmsSessionManager} 中
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

    private static ChatModel stubChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("stub"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };
    }

    /** 反射调用私有的 resolvePermission，以便直接观察超时分支的返回值。 */
    private static PermissionChoice resolvePermission(
            DefaultHmsSessionManager manager, HmsCallbacks callbacks) throws Exception {
        Method m = DefaultHmsSessionManager.class.getDeclaredMethod(
                "resolvePermission", HmsCallbacks.class, AgentLoop.PermissionRequest.class);
        m.setAccessible(true);
        return (PermissionChoice) m.invoke(manager,
                callbacks, new AgentLoop.PermissionRequest("SomeTool", "{}", "doing something"));
    }

    /**
     * PendingResponses 的权限默认值与 resolvePermission 的超时兜底必须都是「拒绝」。
     * 二者不一致会让同一次超时因调度差异得出相反结论 —— 而其中一种结论是放行。
     */
    @Test
    void permissionTimeoutIsFailSafeOnBothPaths() throws Exception {
        // 路径一：PendingResponses 的超时兜底值
        assertEquals("deny", PendingResponses.DEFAULT_PERMISSION_CHOICE,
                "PendingResponses 权限超时必须默认拒绝");

        // 路径二：resolvePermission 中 get() 抛 TimeoutException 后的兜底
        // 用 1 秒超时的管理器 + 永不完成的 Future，强制走 catch 分支
        try (DefaultHmsSessionManager manager = new DefaultHmsSessionManager(
                stubChatModel(), new ToolRegistry(), null,
                new DefaultPromptManager(null, "g"),
                3600, 3600, 1)) {

            HmsCallbacks neverAnswers = new HmsCallbacks() {
                @Override
                public String onPermissionRequest(String toolName, String description) {
                    return null;   // 弃权，转异步
                }

                @Override
                public java.util.concurrent.CompletableFuture<String> onPermissionRequestAsync(
                        String toolName, String description) {
                    return new java.util.concurrent.CompletableFuture<>();  // 永不完成
                }
            };

            assertEquals(PermissionChoice.DENY_ONCE, resolvePermission(manager, neverAnswers),
                    "get() 超时后必须拒绝（fail-safe），不得放行");
        }
    }

    /**
     * 异步回调返回 "deny" 之外的任意值都不应被当作放行。
     */
    @Test
    void onlyExplicitAllowGrantsPermission() throws Exception {
        try (DefaultHmsSessionManager manager = new DefaultHmsSessionManager(
                stubChatModel(), new ToolRegistry(), null,
                new DefaultPromptManager(null, "g"),
                3600, 3600, 5)) {

            for (String response : new String[]{"deny", "skip", "", "ALLOWED", "yes", "1"}) {
                HmsCallbacks callbacks = new HmsCallbacks() {
                    @Override
                    public String onPermissionRequest(String toolName, String description) {
                        return null;
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<String> onPermissionRequestAsync(
                            String toolName, String description) {
                        return java.util.concurrent.CompletableFuture.completedFuture(response);
                    }
                };
                assertEquals(PermissionChoice.DENY_ONCE, resolvePermission(manager, callbacks),
                        "非 \"allow\" 的回答 [" + response + "] 不应被视为放行");
            }
        }
    }

    /** 明确的 "allow" 应当放行 —— 确认上一条测试不是因为整条链路都返回拒绝。 */
    @Test
    void explicitAllowIsHonored() throws Exception {
        try (DefaultHmsSessionManager manager = new DefaultHmsSessionManager(
                stubChatModel(), new ToolRegistry(), null,
                new DefaultPromptManager(null, "g"),
                3600, 3600, 5)) {

            HmsCallbacks allows = new HmsCallbacks() {
                @Override
                public String onPermissionRequest(String toolName, String description) {
                    return null;
                }

                @Override
                public java.util.concurrent.CompletableFuture<String> onPermissionRequestAsync(
                        String toolName, String description) {
                    return java.util.concurrent.CompletableFuture.completedFuture("allow");
                }
            };
            assertEquals(PermissionChoice.ALLOW_ONCE, resolvePermission(manager, allows));
        }
    }
}
