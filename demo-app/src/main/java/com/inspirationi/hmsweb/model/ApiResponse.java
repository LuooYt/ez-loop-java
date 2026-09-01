package com.inspirationi.hmsweb.model;

/**
 * 统一 API 响应包装。
 */
public record ApiResponse<T>(
        /** 是否成功 */
        boolean success,
        /** 提示信息（成功时为 "ok"，失败时为错误描述） */
        String message,
        /** 业务数据（失败时通常为 null） */
        T data
) {
    /** 构造成功响应，使用默认提示信息 "ok"。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "ok", data);
    }

    /** 构造失败响应（不携带业务数据）。 */
    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
