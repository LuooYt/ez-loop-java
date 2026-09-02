package com.inspirationi.loop.api;

/**
 * HMS Core SDK 结构化错误码体系。
 * <p>
 * 区分客户端错误（4xxx）和服务端错误（5xxx），
 * 供 SDK 集成方进行细粒度的错误处理和重试逻辑。
 * <p>
 * <b>错误码分组：</b>
 * <ul>
 *   <li>{@code 1xxx} — 通用客户端错误（参数、状态等）</li>
 *   <li>{@code 2xxx} — 会话管理错误</li>
 *   <li>{@code 3xxx} — 权限/安全错误</li>
 *   <li>{@code 5xxx} — 服务端/执行错误</li>
 *   <li>{@code 6xxx} — AI 模型调用错误</li>
 *   <li>{@code 7xxx} — 超时错误</li>
 * </ul>
 */
public enum HmsErrorCode {

    // ── 通用 (1xxx) ──

    /** 参数缺失或无效 */
    INVALID_ARGUMENT(1001, "Invalid argument"),
    /** 用户输入无效（null 或空） */
    INVALID_INPUT(1005, "Invalid input"),
    /** 服务正忙（已有请求在处理中） */
    SERVICE_BUSY(1002, "Service is busy"),
    /** 请求被用户取消 */
    REQUEST_CANCELLED(1003, "Request cancelled"),
    /** 不支持的操作 */
    UNSUPPORTED_OPERATION(1004, "Unsupported operation"),

    // ── 会话 (2xxx) ──

    /** 会话不存在 */
    SESSION_NOT_FOUND(2001, "Session not found"),
    /** 会话已暂停 */
    SESSION_PAUSED(2002, "Session is paused"),
    /** 会话已销毁 */
    SESSION_DESTROYED(2003, "Session has been destroyed"),
    /** 会话创建失败 */
    SESSION_CREATE_FAILED(2004, "Session creation failed"),
    /** 会话数超限 */
    SESSION_LIMIT_EXCEEDED(2005, "Session limit exceeded"),

    // ── 权限 (3xxx) ──

    /** 权限被拒绝 */
    PERMISSION_DENIED(3001, "Permission denied"),
    /** 需要用户确认但无可用回调 */
    PERMISSION_NEEDS_CONFIRMATION(3002, "Permission requires user confirmation"),
    /** 风险操作被阻断 */
    RISK_BLOCKED(3003, "Risk operation blocked"),

    // ── 执行 (5xxx) ──

    /** 执行失败（通用） */
    EXECUTION_FAILED(5001, "Execution failed"),
    /** 工具执行失败 */
    TOOL_EXECUTION_FAILED(5002, "Tool execution failed"),
    /** 工具未找到 */
    TOOL_NOT_FOUND(5003, "Tool not found"),
    /** Agent 循环达到最大迭代 */
    MAX_ITERATIONS_REACHED(5004, "Max loop iterations reached"),

    // ── AI 模型 (6xxx) ──

    /** AI 模型调用失败 */
    AI_CALL_FAILED(6001, "AI model call failed"),
    /** AI 模型返回无效响应 */
    AI_INVALID_RESPONSE(6002, "AI returned invalid response"),
    /** AI 模型认证失败 */
    AI_AUTH_FAILED(6003, "AI model authentication failed"),
    /** Token 配额不足 */
    AI_QUOTA_EXCEEDED(6004, "AI token quota exceeded"),

    // ── 超时 (7xxx) ──

    /** 请求超时 */
    REQUEST_TIMEOUT(7001, "Request timeout"),
    /** AI 模型调用超时 */
    AI_CALL_TIMEOUT(7002, "AI model call timeout"),
    /** 工具执行超时 */
    TOOL_EXECUTION_TIMEOUT(7003, "Tool execution timeout");

    /** 结构化错误码数字值。 */
    private final int code;
    /** 该错误码的默认错误信息（英文）。 */
    private final String defaultMessage;

    /**
     * 构造错误码。
     *
     * @param code           结构化错误码数字值
     * @param defaultMessage 默认错误信息
     */
    HmsErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** 获取结构化错误码数字值。 */
    public int code() { return code; }
    /** 获取默认错误信息。 */
    public String defaultMessage() { return defaultMessage; }

    /**
     * 是否属于调用方错误（参数、状态、权限），而非服务端故障。
     * <p>
     * 按类文档的错误码分组判定：{@code 1xxx} 通用客户端错误、{@code 2xxx} 会话、
     * {@code 3xxx} 权限属调用方问题；{@code 5xxx} 执行、{@code 6xxx} 模型调用、
     * {@code 7xxx} 超时属服务端或上游问题。
     * <p>
     * 判定放在枚举上而非各集成方：分组规则是本错误码体系自身的知识，散落到调用侧
     * 会各写一份、且随新增错误码而失同步。集成方据此决定 HTTP 状态码与日志级别 ——
     * 调用方错误不该打 ERROR 堆栈（那会让真实故障淹没在噪声里），例如空消息请求
     * 曾在服务端留下完整的 dispatcherServlet 异常栈。
     *
     * @return true 表示调用方错误（宜按 4xx + debug 日志处理）
     */
    public boolean isClientError() {
        int group = code / 1000;
        return group == 1 || group == 2 || group == 3;
    }

    /**
     * 把上游（provider SDK）抛出的异常归类到本枚举。
     * <p>
     * 没有这层归类时，模型调用失败只能把 SDK 的原始消息原样透给前端 —— 那是一串
     * 形如 {@code 403: {"error":{"type":"forbidden","message":"Request not allowed"}}}
     * 的 JSON：前端无法据此分支处理，也不该直接展示给终端用户。
     * {@link #AI_AUTH_FAILED} / {@link #AI_QUOTA_EXCEEDED} / {@link #AI_CALL_FAILED}
     * 这三个码此前从未被赋予过，缺的正是这一步。
     * <p>
     * 按 HTTP 状态码而非 provider 专有异常类型判断：本项目同时支持 OpenAI 与
     * Anthropic，两者异常层次不同、且都可能被 Reactor 包装，而两个 SDK 都会把状态码
     * 写进异常消息文本。
     *
     * @param error 上游异常（可为 {@code null}）
     * @return 归类结果；认不出来时为 {@link #AI_CALL_FAILED}
     */
    public static HmsErrorCode classifyUpstream(Throwable error) {
        return switch (com.inspirationi.loop.util.UpstreamErrors.findStatus(error, 401, 403, 429)) {
            case 401, 403 -> AI_AUTH_FAILED;
            case 429 -> AI_QUOTA_EXCEEDED;
            default -> AI_CALL_FAILED;
        };
    }
}
