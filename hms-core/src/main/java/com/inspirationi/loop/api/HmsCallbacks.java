package com.inspirationi.loop.api;

import java.util.concurrent.CompletableFuture;

/**
 * HMS Core API 回调集合 —— 覆盖感兴趣的方法来监听 Agent 执行过程。
 * <p>
 * 所有方法均为 default（空实现），调用方只覆写需要的回调。
 * <p>
 * 支持两种回调模式：
 * <ul>
 *   <li><b>同步阻塞模式</b>（默认）—— {@link #onAskUser} 在调用线程等待返回值，适合非 Web 场景</li>
 *   <li><b>异步模式</b>（推荐）—— 覆写 {@link #onAskUserAsync} 返回 {@link CompletableFuture}，
 *       适合 HTTP 请求/响应、消息队列等异步场景</li>
 * </ul>
 * <p>
 * <b>使用示例（同步阻塞）：</b>
 * <pre>{@code
 * HmsCallbacks callbacks = new HmsCallbacks() {
 *     @Override public void onToken(String token) { System.out.print(token); }
 *     @Override public String onAskUser(String question, List<String> options) {
 *         return myUi.askUserSync(question, options);  // 线程阻塞等待 UI 返回
 *     }
 * };
 * sessionManager.send(sessionId, msg, callbacks);
 * }</pre>
 * <p>
 * <b>使用示例（异步）：</b>
 * <pre>{@code
 * HmsCallbacks callbacks = new HmsCallbacks() {
 *     @Override public void onToken(String token) { sseEmitter.send(token); }
 *     @Override public CompletableFuture<String> onAskUserAsync(String question, List<String> options) {
 *         // 通过 WebSocket / SSE / 消息队列 等异步方式获取用户输入
 *         return asyncUiService.askUser(question, options);
 *     }
 * };
 * sessionManager.send(sessionId, msg, callbacks);
 * }</pre>
 */
public interface HmsCallbacks {

    /** 流式 Token 回调 —— 每个输出 token 调用一次。 */
    default void onToken(String token) {}

    /**
     * 工具调用通知 —— <b>同一次调用会触发多次</b>，按 {@code phase} 区分。
     * <p>
     * 阶段序列为 {@code START} →（0 到多次 {@code PROGRESS}）→ {@code END}。
     * 统计工具用量时只应在 {@code END} 计数 —— 三个阶段各计一次会让用量翻几倍。
     *
     * @param toolName 工具名
     * @param phase    {@code "START"} / {@code "PROGRESS"} / {@code "END"}
     * @param input    工具入参（JSON 文本）
     * @param result   执行结果；{@code START} 阶段为 null，{@code PROGRESS} 阶段是进度行
     */
    default void onToolUse(String toolName, String phase, String input, String result) {}

    /** AI Thinking 内容回调 */
    default void onThinking(String thinking) {}

    /**
     * 运行时活动状态变化 —— 「此刻正在做什么」发生了改变。
     * <p>
     * 仅在状态<b>真正切换</b>时触发（同态不重复通知）。一次请求必然以
     * {@link SessionActivity#IDLE} 收尾，无论正常结束、异常、取消还是撞迭代上限。
     *
     * @param activity 新状态
     * @param detail   补充信息，如 {@code USING_TOOL} 时的工具名；无则为 null
     */
    default void onActivity(SessionActivity activity, String detail) {}

    /**
     * 上下文压缩通知 —— 消息历史被自动压缩后触发。
     * <p>
     * 压缩是静默发生的：旧消息会被摘要或裁剪掉，而调用方看到的只是后续回答
     * 「忘了」早前的细节。覆写此回调即可提示用户「上下文已压缩」，或统计压缩
     * 频次与层级分布 —— 频繁触发 {@code FULL} 层意味着单轮上下文用量该调小了。
     *
     * @param result 压缩结果（层级、压缩前后消息数、原因）
     */
    default void onCompaction(com.inspirationi.loop.core.compact.CompactionResult result) {}

    /**
     * 用户提问回调（同步阻塞模式） —— AI 在执行过程中向用户提问，**同步阻塞**等待返回答案。
     * <p>
     * 当 AI 调用 {@code AskUserQuestion} 工具时触发此回调。
     * 调用方在此处可以弹出 UI 对话框、等待 HTTP 响应、或从消息队列获取输入。
     * <p>
     * ⚠ 注意：此方法在调用线程中**同步阻塞**。对于 Web 应用（HTTP 请求/响应模型），
     * 建议改用 {@link #onAskUserAsync}。仅当调用方可以同步等待（如桌面 GUI、控制台应用）时使用。
     * <p>
     * 如果此回调返回 {@code null} 或空字符串（默认实现），
     * 则:
     * <ol>
     *   <li>优先回退到 {@link #onAskUserAsync}（异步模式）</li>
     *   <li>最终回退到 ToolContext 中注册的 {@code ASK_USER_STRUCTURED_CALLBACK}</li>
     * </ol>
     *
     * @param question AI 提出的问题
     * @param options  可选答案列表（可能为 null 或空列表，表示自由文本回答）
     * @return 用户的回答文本，返回 null 或空字符串会触发回退
     */
    default String onAskUser(String question, java.util.List<String> options) {
        return null;  // 默认不回应该提问，触发回退链
    }

    /**
     * 用户提问回调（异步模式，推荐 Web 应用使用） ✨
     * <p>
     * 返回一个 {@link CompletableFuture}，在异步获取到用户输入后 complete。
     * 适合 HTTP 请求/响应、WebSocket、消息队列等异步通信场景。
     * <p>
     * <b>优先级：</b>同步回调 {@link #onAskUser} → 异步回调 {@link #onAskUserAsync} → ToolContext 回退。
     * <p>
     * <b>典型用法：</b>
     * <pre>{@code
     * @Override
     * public CompletableFuture<String> onAskUserAsync(String question, List<String> options) {
     *     CompletableFuture<String> future = new CompletableFuture<>();
     *     // 通过 WebSocket 发送问题给客户端，等待回复
     *     webSocketSession.sendMessage(Map.of("type", "ask", "question", question));
     *     pendingQuestions.put(correlationId, future);
     *     return future;
     * }
     * }</pre>
     *
     * @param question AI 提出的问题
     * @param options  可选答案列表（可能为 null 或空列表，表示自由文本回答）
     * @return 异步等待用户回答的 Future
     */
    default CompletableFuture<String> onAskUserAsync(String question, java.util.List<String> options) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 权限请求回调（同步阻塞模式） —— 当工具需要用户确认时触发。
     * <p>
     * 返回值：{@code "allow"} 允许 / {@code "deny"} 拒绝。
     * <p>
     * 返回 {@code null} 或空字符串表示**弃权**，会回退到 {@link #onPermissionRequestAsync}；
     * 若异步回调也未给出 {@code "allow"}，最终按拒绝处理（fail-safe）。
     * <p>
     * ⚠ 只覆写异步版时不要在此返回 {@code "deny"} —— 那会让异步回调永远不被调用。
     * 默认实现即为弃权，通常无需覆写。
     *
     * @param toolName    请求的工具名称
     * @param description 操作描述
     * @return {@code "allow"} / {@code "deny"}，或 {@code null} 表示弃权并回退到异步回调
     */
    default String onPermissionRequest(String toolName, String description) {
        return null;  // 默认弃权，回退到异步回调；两者都未允许时按拒绝处理
    }

    /**
     * 权限请求回调（异步模式，推荐 Web 应用使用） ✨
     * <p>
     * 返回 {@link CompletableFuture}，异步等待权限确认。
     * <p>
     * <b>优先级：</b>同步回调 {@link #onPermissionRequest} → 本异步回调 → 拒绝。
     *
     * @param toolName    请求的工具名称
     * @param description 操作描述
     * @return 异步等待 {@code "allow"} 或 {@code "deny"} 的 Future
     */
    default CompletableFuture<String> onPermissionRequestAsync(String toolName, String description) {
        return CompletableFuture.completedFuture("deny");
    }

    /** 请求完成回调 */
    default void onComplete(HmsResponse response) {}

    /**
     * 错误回调 —— 执行过程中发生异常时触发。
     * <p>
     * 返回值：{@code "retry"} 重试 / {@code "abort"} 中止。
     */
    default String onError(Throwable error) {
        return "abort";
    }
}
