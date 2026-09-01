package com.inspirationi.loop.mcp;

/**
 * MCP 相关异常 —— 统一封装 MCP 通信、协议解析和工具调用中的错误。
 */
public class McpException extends Exception {

    /** JSON-RPC 错误码（若源自 JSON-RPC error 响应） */
    private final int errorCode;

    /**
     * 构造一个非 JSON-RPC 错误（错误码默认为 {@code -1}）。
     *
     * @param message 异常描述信息
     */
    public McpException(String message) {
        super(message);
        this.errorCode = -1;
    }

    /**
     * 构造一个带原因的非 JSON-RPC 错误（错误码默认为 {@code -1}）。
     *
     * @param message 异常描述信息
     * @param cause   底层异常原因
     */
    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = -1;
    }

    /**
     * 构造一个 JSON-RPC 协议级错误。
     *
     * @param message   异常描述信息
     * @param errorCode JSON-RPC 错误码
     */
    public McpException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造一个带原因的 JSON-RPC 协议级错误。
     *
     * @param message   异常描述信息
     * @param errorCode JSON-RPC 错误码
     * @param cause     底层异常原因
     */
    public McpException(String message, int errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取 JSON-RPC 错误码。
     *
     * @return 错误码，若非 JSON-RPC 错误则返回 {@code -1}
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * 是否为 JSON-RPC 协议级错误。
     */
    public boolean isJsonRpcError() {
        return errorCode != -1;
    }
}
