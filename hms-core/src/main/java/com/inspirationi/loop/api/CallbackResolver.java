package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 把 {@link HmsCallbacks} 的「同步优先、异步回退」协议解析为具体结果。
 * <p>
 * {@code HmsCallbacks} 对提问与权限确认各提供两个回调：同步版返回 {@code null}
 * 或空串表示<b>弃权</b>，此时回退到异步版并限时等待。集成方只覆写其中任一个都应
 * 能正常工作，这条回退链因此必须在每个调用点一致地实现 —— 一旦某处漏掉异步回退，
 * 只实现了异步回调的集成方就会静默拿到「无人应答」。
 * <p>
 * {@code DefaultHmsService}（单会话）与 {@code DefaultHmsSessionManager}（多会话）
 * 都需要它，此前各自实现了一份，除超时字段名外逐字相同。
 * <p>
 * <b>两处的失败语义有意不同</b>：提问失败返回 {@code null}，交给
 * {@code ToolContext} 上注册的回退链继续尝试；权限失败一律拒绝（fail-safe）——
 * 拿不到用户确认时放行工具，等于把确认环节当作可跳过的装饰。
 */
final class CallbackResolver {

    private static final Logger log = LoggerFactory.getLogger(CallbackResolver.class);

    /** 等待异步回调的上限（秒）。 */
    private final long timeoutSeconds;
    /** 日志前缀，用于区分调用方（单会话 / 某个 sessionId）。 */
    private final String logTag;

    CallbackResolver(long timeoutSeconds, String logTag) {
        this.timeoutSeconds = timeoutSeconds;
        this.logTag = logTag;
    }

    /**
     * 解析用户提问的回答：同步回调 → 异步回调 → {@code null}。
     *
     * @param question 问题文本
     * @param options  可选答案列表（{@code null} 表示自由文本回答）
     * @return 用户回答；无人应答时返回 {@code null}，由调用方回退到 ToolContext 链
     */
    String resolveAskUser(HmsCallbacks callbacks, String question, List<String> options) {
        String answer = callbacks.onAskUser(question, options);
        if (isAnswered(answer)) {
            return answer;
        }
        try {
            String asyncAnswer = callbacks.onAskUserAsync(question, options)
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            if (isAnswered(asyncAnswer)) {
                return asyncAnswer;
            }
        } catch (Exception e) {
            log.debug("{} async askUser timed out or failed after {}s: {}",
                    logTag, timeoutSeconds, e.getMessage());
        }
        return null;
    }

    /**
     * 解析权限确认：同步回调 → 异步回调 → 拒绝。
     * <p>
     * 只有明确的 "allow" 才放行。同步回调返回 {@code null}、空串或无法识别的值
     * 都视为弃权并回退到异步回调；异步同样未给出 "allow" 时拒绝。
     */
    PermissionChoice resolvePermission(HmsCallbacks callbacks, AgentLoop.PermissionRequest req) {
        String description = req.activityDescription() != null ? req.activityDescription() : "";
        String choice = callbacks.onPermissionRequest(req.toolName(), description);
        if (isAllow(choice)) {
            return PermissionChoice.ALLOW_ONCE;
        }
        if (isDeny(choice)) {
            return PermissionChoice.DENY_ONCE;
        }
        try {
            String asyncChoice = callbacks.onPermissionRequestAsync(req.toolName(), description)
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            if (isAllow(asyncChoice)) {
                return PermissionChoice.ALLOW_ONCE;
            }
        } catch (Exception e) {
            log.debug("{} async permission request timed out after {}s: {}",
                    logTag, timeoutSeconds, e.getMessage());
        }
        return PermissionChoice.DENY_ONCE;
    }

    /** 通知调用方发生错误；回调自身抛出的异常不得掩盖原始异常。 */
    void notifyError(HmsCallbacks callbacks, Throwable error) {
        try {
            callbacks.onError(error);
        } catch (RuntimeException callbackFailure) {
            log.warn("{} onError callback itself failed: {}", logTag, callbackFailure.getMessage());
        }
    }

    private static boolean isAnswered(String answer) {
        return answer != null && !answer.isBlank();
    }

    private static boolean isAllow(String choice) {
        return "allow".equalsIgnoreCase(choice);
    }

    private static boolean isDeny(String choice) {
        return "deny".equalsIgnoreCase(choice);
    }
}
