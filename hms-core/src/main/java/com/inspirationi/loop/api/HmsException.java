package com.inspirationi.loop.api;

/**
 * HMS Core SDK 结构化异常 —— 替代裸 {@link RuntimeException}。
 * <p>
 * 携带结构化错误码 {@link HmsErrorCode} 和请求追踪信息，
 * 供 SDK 集成方进行细粒度的错误处理和重试决策。
 * <p>
 * <b>判断是否可重试：</b>
 * <ul>
 *   <li>客户端错误（4xxx）—— 通常不可重试（除非参数修正后）</li>
 *   <li>服务端/超时错误（5xxx/7xxx）—— 可重试</li>
 * </ul>
 */
public class HmsException extends RuntimeException {

    /** 结构化错误码。 */
    private final HmsErrorCode errorCode;
    /** 请求追踪 ID（用于日志关联与分布式追踪，可为 null）。 */
    private final String requestId;

    /** 构造异常（无请求追踪 ID）。 */
    public HmsException(HmsErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.requestId = null;
    }

    /** 构造异常（携带原因，无请求追踪 ID）。 */
    public HmsException(HmsErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.requestId = null;
    }

    /** 构造异常（携带请求追踪 ID）。 */
    public HmsException(HmsErrorCode errorCode, String message, String requestId) {
        super(message);
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    /** 构造异常（携带请求追踪 ID 与原因）。 */
    public HmsException(HmsErrorCode errorCode, String message, String requestId, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    /** 获取结构化错误码。 */
    public HmsErrorCode getErrorCode() { return errorCode; }
    /** 获取请求追踪 ID（可为 null）。 */
    public String getRequestId() { return requestId; }
    /** 获取错误码的数字值。 */
    public int getCode() { return errorCode.code(); }

    /** 是否能通过重试恢复 */
    public boolean isRetryable() {
        return errorCode.code() >= 5000;
    }

    /** 是否为客户端错误 */
    public boolean isClientError() {
        return errorCode.code() >= 1000 && errorCode.code() < 5000;
    }

    /** 是否为超时 */
    public boolean isTimeout() {
        return errorCode.code() >= 7000;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("HmsException[");
        sb.append("code=").append(errorCode.code());
        sb.append(", type=").append(errorCode.name());
        if (requestId != null) sb.append(", requestId=").append(requestId);
        sb.append(", message=").append(getMessage());
        sb.append("]");
        return sb.toString();
    }
}
