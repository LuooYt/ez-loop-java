package com.inspirationi.loop.core;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.permission.DenialTracker;
import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionTypes.PermissionChoice;
import com.inspirationi.loop.permission.PermissionTypes.PermissionDecision;
import com.inspirationi.loop.tool.ToolCallbackAdapter;
import com.inspirationi.loop.tool.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 工具执行器 —— 从 AgentLoop 拆分出的工具调用执行逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>解析工具参数</li>
 *   <li>PreToolUse / PostToolUse Hook 执行</li>
 *   <li>权限检查（规则引擎 + 传统回调）</li>
 *   <li>工具调用执行与结果收集</li>
 * </ul>
 */
public class AgentToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);
    /** JSON 解析器 —— 用于解析工具参数 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具被用户取消的默认结果文本（中文，经 {@link PromptI18n} 按系统语言取用）。 */
    public static final String DEFAULT_TOOL_CANCELLED = "用户已取消";
    /** 工具被 Hook 中止的默认结果文本（中文，经 {@link PromptI18n} 按系统语言取用）。 */
    public static final String DEFAULT_TOOL_ABORTED = "已由 Hook 中止";
    /** 未知工具错误模板（中文，含 %s 工具名占位符，经 {@link PromptI18n} 按系统语言取用）。 */
    public static final String DEFAULT_UNKNOWN_TOOL = "错误：未知工具 '%s'";
    /** 工具权限被拒绝的默认结果文本（中文，经 {@link PromptI18n} 按系统语言取用）。 */
    public static final String DEFAULT_PERMISSION_DENIED = "权限被拒绝：用户已拒绝此操作";

    /** Hook 管理器 —— 执行 PreToolUse / PostToolUse 拦截钩子 */
    private final HookManager hookManager;
    /** 工具执行上下文 —— 提供进度回调等运行时环境 */
    private final ToolContext toolContext;
    /** 拒绝追踪器 —— 记录连续拒绝次数，用于过激拒绝时的熔断 */
    private final DenialTracker denialTracker;

    /** 权限规则引擎（可为 null，此时回退到传统回调权限确认） */
    private PermissionRuleEngine permissionEngine;
    // 请求级回调（每次 executeToolCalls 前由 AgentLoop 设置，请求结束后不再持有）
    private volatile AgentLoop.RequestCallbacks requestCallbacks;

    /**
     * 构造工具执行器。
     *
     * @param hookManager   Hook 管理器
     * @param toolContext   工具执行上下文
     * @param denialTracker 拒绝追踪器
     */
    public AgentToolExecutor(HookManager hookManager, ToolContext toolContext, DenialTracker denialTracker) {
        this.hookManager = hookManager;
        this.toolContext = toolContext;
        this.denialTracker = denialTracker;
    }

    /** 设置权限规则引擎（规则引擎优先于传统回调） */
    public void setPermissionEngine(PermissionRuleEngine engine) {
        this.permissionEngine = engine;
    }

    /** 设置请求级回调（覆盖旧值，请求结束后自动过时） */
    public void setRequestCallbacks(AgentLoop.RequestCallbacks callbacks) {
        this.requestCallbacks = callbacks;
    }

    /**
     * 执行工具调用列表并返回 ToolResponseMessage 加入消息历史。
     *
     * @param cancelled 取消状态的<b>实时</b>查询入口。必须是 supplier 而非布尔值 ——
     *                  一批工具调用可能耗时很久，传快照会让期间到达的 cancel()
     *                  直到下一轮迭代边界才被感知。
     */
    @SuppressWarnings("unchecked")
    public ToolResponseMessage executeToolCalls(List<AssistantMessage.ToolCall> toolCalls,
                                                 List<ToolCallback> springCallbacks,
                                                 BooleanSupplier cancelled) {
        // 捕获请求级回调的本地引用（线程安全）
        Consumer<AgentLoop.ToolEvent> toolEventCb = requestCallbacks != null ? requestCallbacks.onToolEvent() : null;
        Function<AgentLoop.PermissionRequest, PermissionChoice> permCb = requestCallbacks != null ? requestCallbacks.onPermissionRequest() : null;

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
        Map<String, ToolCallbackAdapter> adapters = indexByName(springCallbacks);

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            if (cancelled.getAsBoolean()) {
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(),
                        PromptI18n.t(PromptI18n.KEY_TOOL_CANCELLED, DEFAULT_TOOL_CANCELLED)));
                continue;
            }

            String toolName = toolCall.name();
            String toolArgs = toolCall.arguments();
            String callId = toolCall.id();

            Map<String, Object> parsedArgs = parseArguments(toolName, toolArgs);
            if (parsedArgs == null) {
                // 参数是坏 JSON —— 不执行，把原因回传给模型让它重发。用空参数
                // 硬跑等于让工具在缺输入的情况下产出一个看似正常的结果。
                toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName,
                        "Error: Invalid JSON input for tool '" + toolName + "': " + toolArgs));
                continue;
            }

            // PreToolUse Hook
            var preHookCtx = new HookManager.HookContext(toolName, parsedArgs);
            if (hookManager.execute(HookManager.HookType.PRE_TOOL_USE, preHookCtx) == HookManager.HookResult.ABORT) {
                log.info("[{}] PreToolUse Hook aborted execution", toolName);
                toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName,
                        PromptI18n.t(PromptI18n.KEY_TOOL_ABORTED_BY_HOOK, DEFAULT_TOOL_ABORTED)));
                continue;
            }

            if (toolEventCb != null) {
                toolEventCb.accept(new AgentLoop.ToolEvent(toolName, AgentLoop.ToolEvent.Phase.START, toolArgs, null));
            }

            String result = executeOneTool(toolName, toolArgs, parsedArgs, adapters, permCb, toolEventCb);

            // PostToolUse Hook
            var postHookCtx = new HookManager.HookContext(toolName, parsedArgs);
            postHookCtx.setResult(result);
            hookManager.execute(HookManager.HookType.POST_TOOL_USE, postHookCtx);
            if (postHookCtx.getResult() != null) {
                result = postHookCtx.getResult();
            }

            if (toolEventCb != null) {
                toolEventCb.accept(new AgentLoop.ToolEvent(toolName, AgentLoop.ToolEvent.Phase.END, toolArgs, result));
            }

            toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName, result));
        }

        return ToolResponseMessage.builder().responses(toolResponses).build();
    }

    /**
     * 执行单个工具调用（含权限检查）。
     */
    private String executeOneTool(String toolName, String toolArgs,
                                  Map<String, Object> parsedArgs,
                                  Map<String, ToolCallbackAdapter> adapters,
                                  Function<AgentLoop.PermissionRequest, PermissionChoice> permCb,
                                  Consumer<AgentLoop.ToolEvent> toolEventCb) {
        log.info("[EXEC] executeOneTool: name={}, args={}", toolName, toolArgs);
        ToolCallbackAdapter adapter = adapters.get(toolName);
        if (adapter == null) {
            log.warn("Unknown tool: {}", toolName);
            return unknownToolError(toolName);
        }

        boolean permitted = checkPermission(toolName, toolArgs, parsedArgs, adapter, permCb);
        if (!permitted) {
            log.info("[EXEC] Tool {} denied by permission", toolName);
            return PromptI18n.t(PromptI18n.KEY_TOOL_PERMISSION_DENIED, DEFAULT_PERMISSION_DENIED);
        }

        // 设置进度回调
        if (toolEventCb != null) {
            toolContext.setProgressCallback(line ->
                    toolEventCb.accept(new AgentLoop.ToolEvent(
                            toolName, AgentLoop.ToolEvent.Phase.PROGRESS, toolArgs, line)));
        }
        try {
            long start = System.currentTimeMillis();
            // 传已解析的参数：权限评估阶段（parseArguments）已经解过一次，
            // 走 call(String) 会让同一份 JSON 被解析两次，而工具参数里可能带着
            // 整个文件内容。
            String result = adapter.call(toolArgs, parsedArgs);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[EXEC] Tool {} completed in {}ms, result length={}",
                    toolName, elapsed, result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("[EXEC] Tool {} threw exception: {}", toolName, e.getMessage(), e);
            throw e;
        } finally {
            toolContext.setProgressCallback(null);
        }
    }

    /**
     * 权限检查：规则引擎优先，回退到请求级回调。
     */
    private boolean checkPermission(String toolName, String toolArgs,
                                    Map<String, Object> parsedArgs,
                                    ToolCallbackAdapter adapter,
                                    Function<AgentLoop.PermissionRequest, PermissionChoice> permCb) {
        if (permissionEngine != null) {
            PermissionDecision decision = permissionEngine.evaluate(
                    toolName, parsedArgs, adapter.getTool().riskLevel());

            if (decision.isAllowed()) {
                denialTracker.recordSuccess();
                return true;
            } else if (decision.isDenied()) {
                denialTracker.recordDenial();
                log.info("[{}] Denied by rule: {}", toolName, decision.reason());
                return false;
            } else if (decision.needsAsk() && permCb != null) {
                if (denialTracker.shouldFallbackToPrompting()) {
                    log.warn("[{}] Denial threshold reached, auto-denying due to excessive denials", toolName);
                    return false;
                }
                String activity = adapter.getTool().activityDescription(parsedArgs);
                // 带上真实风险等级与已解析参数：回调只需「询问」，不必也不应重新评估。
                // 少传任一项都会迫使回调自行猜测风险等级，猜低即绕过用户确认。
                AgentLoop.PermissionRequest req = new AgentLoop.PermissionRequest(
                        toolName, toolArgs, parsedArgs, activity,
                        adapter.getTool().riskLevel(), decision);
                PermissionChoice choice = permCb.apply(req);
                boolean allowed = (choice == PermissionChoice.ALLOW_ONCE || choice == PermissionChoice.ALWAYS_ALLOW);
                if (allowed) denialTracker.recordSuccess(); else denialTracker.recordDenial();
                // 从参数中正确提取命令前缀用于 applyChoice
                String commandPrefix = permissionEngine.extractCommandPrefixForTool(toolName, parsedArgs);
                permissionEngine.applyChoice(choice, toolName, commandPrefix);
                return allowed;
            } else {
                denialTracker.recordDenial();
                return false;
            }
        }

        // 传统回调模式（无规则引擎时的权限确认）
        if (!adapter.getTool().isReadOnly() && permCb != null) {
            String activity = adapter.getTool().activityDescription(parsedArgs);
            AgentLoop.PermissionRequest req = new AgentLoop.PermissionRequest(toolName, toolArgs, activity);
            PermissionChoice choice = permCb.apply(req);
            boolean allowed = (choice == PermissionChoice.ALLOW_ONCE || choice == PermissionChoice.ALWAYS_ALLOW);
            if (allowed) {
                denialTracker.recordSuccess();
            } else {
                denialTracker.recordDenial();
            }
            return allowed;
        }
        return true;
    }

    /**
     * 解析工具参数字符串为 Map。
     * <p>
     * <b>解析失败返回 {@code null} 而非空 Map</b>：两者必须可区分。{@code "{}"} 是
     * 合法的「无参数」，而坏 JSON 意味着模型输出有问题 —— 若都归为空 Map，一个参数
     * 残缺的调用会被当作无参调用照常执行，工具拿不到该拿的输入却又不报错。
     *
     * @return 解析结果；JSON 非法时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String toolName, String toolArgs) {
        try {
            return MAPPER.readValue(toolArgs, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments for {}: {}", toolName, e.getMessage());
            return null;
        }
    }

    /**
     * 按工具名建立适配器索引 —— 每批工具调用建一次，替代逐个调用做线性扫描。
     * <p>
     * 传进来的是 {@code List}（Spring AI 的 {@code ChatOptions} 只收 List），而一批
     * 里可能有多个工具调用，逐个 O(n) 扫描 + {@code instanceof} 是把本可以 O(1) 的
     * 查找退化了。
     */
    private static Map<String, ToolCallbackAdapter> indexByName(List<ToolCallback> callbacks) {
        Map<String, ToolCallbackAdapter> index = new HashMap<>(callbacks.size());
        for (ToolCallback cb : callbacks) {
            if (cb instanceof ToolCallbackAdapter adapter) {
                index.put(adapter.getTool().name(), adapter);
            }
        }
        return index;
    }

    /** 构造未知工具错误文本（翻译改写掉 %s 占位符时回退中文默认模板）。 */
    private static String unknownToolError(String toolName) {
        String template = PromptI18n.t(PromptI18n.KEY_TOOL_UNKNOWN, DEFAULT_UNKNOWN_TOOL);
        try {
            return String.format(template, toolName);
        } catch (IllegalFormatException e) {
            return String.format(DEFAULT_UNKNOWN_TOOL, toolName);
        }
    }
}
