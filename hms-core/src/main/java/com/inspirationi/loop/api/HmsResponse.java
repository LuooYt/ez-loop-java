package com.inspirationi.loop.api;

import java.util.UUID;

/**
 * HMS Core API 响应的完整结果模型 —— 通用 Web SDK 标准响应格式。
 * <p>
 * 包含 AI 回复文本、工具调用次数、Token 使用统计、错误信息和请求追踪。
 */
public record HmsResponse(
        /** 完整 AI 回复文本（流式模式下为聚合后的完整内容） */
        String content,

        /** 本轮对话中工具调用的次数 */
        int toolCallsCount,

        /** 本轮消耗的输入 Token 数 */
        long promptTokens,

        /** 本轮消耗的输出 Token 数 */
        long completionTokens,

        /** 请求是否被中断（取消或超时） */
        boolean interrupted,

        /** 错误码（成功时为 {@code null}） */
        HmsErrorCode errorCode,

        /** 错误详细信息（成功时为 {@code null}） */
        String errorDetail,

        /** 请求追踪 ID（用于日志关联和分布式追踪） */
        String requestId
) {

    /** 总 Token 消耗（输入 + 输出） */
    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    /** 是否成功（无错误码） */
    public boolean isSuccess() {
        return errorCode == null;
    }

    /** 是否为客户端错误（4xxx） */
    public boolean isClientError() {
        return errorCode != null && errorCode.code() >= 1000 && errorCode.code() < 5000;
    }

    /** 是否为服务端错误（5xxx） */
    public boolean isServerError() {
        return errorCode != null && errorCode.code() >= 5000;
    }

    // ==================== 工厂方法 ====================

    /** 创建成功响应（无工具调用） */
    public static HmsResponse ok(String content, long promptTokens, long completionTokens) {
        return new HmsResponse(content, 0, promptTokens, completionTokens, false,
                null, null, generateRequestId());
    }

    /** 创建成功响应（带工具调用次数） */
    public static HmsResponse ok(String content, int toolCalls,
                                  long promptTokens, long completionTokens) {
        return new HmsResponse(content, toolCalls, promptTokens, completionTokens, false,
                null, null, generateRequestId());
    }

    /** 创建成功响应（完整参数） */
    public static HmsResponse ok(String content, int toolCalls,
                                  long promptTokens, long completionTokens, String requestId) {
        return new HmsResponse(content, toolCalls, promptTokens, completionTokens, false,
                null, null, requestId);
    }

    /** 创建被中断的响应（不带用量信息）。 */
    public static HmsResponse interrupted(String content) {
        return new HmsResponse(content, 0, 0, 0, true,
                HmsErrorCode.REQUEST_CANCELLED, "Request was cancelled", generateRequestId());
    }

    /**
     * 创建被中断的响应，保留已产生的用量。
     * <p>
     * 中断前消耗的 token 一样要计费，工具也确实执行过 —— 把它们清零会让调用方的
     * 用量统计与账单对不上。
     */
    public static HmsResponse interrupted(String content, int toolCalls,
                                          long promptTokens, long completionTokens) {
        return new HmsResponse(content, toolCalls, promptTokens, completionTokens, true,
                HmsErrorCode.REQUEST_CANCELLED, "Request was cancelled", generateRequestId());
    }

    /** 创建错误响应 */
    public static HmsResponse error(HmsErrorCode errorCode, String detail) {
        return new HmsResponse(null, 0, 0, 0, false,
                errorCode, detail, generateRequestId());
    }

    /** 创建错误响应（带 requestId） */
    public static HmsResponse error(HmsErrorCode errorCode, String detail, String requestId) {
        return new HmsResponse(null, 0, 0, 0, false,
                errorCode, detail, requestId);
    }

    /** 生成 8 位短请求追踪 ID（用于日志关联与分布式追踪）。 */
    private static String generateRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
