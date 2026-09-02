package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理 —— 把 SDK 抛出的运行时异常翻译成统一的 {@link ApiResponse} 失败体。
 * <p>
 * hms-core 的会话查询（token / metrics / 历史 / pause / resume）在会话不存在时抛
 * {@link IllegalArgumentException}，会话已暂停时抛 {@link IllegalStateException}。
 * 没有这个处理器，它们会变成 Spring 默认的 500 白页，前端 {@code API.request} 只能
 * 拿到一段 HTML —— 而这两种情况都是正常的业务分支（会话刚被删、重复点暂停），
 * 不该按服务端故障对待。
 * <p>
 * 统一返回 400 而非 404：前端只判 {@code success} 字段，区分状态码没有额外价值，
 * 而 {@code IllegalStateException} 本身也不对应「资源不存在」。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 会话不存在 / 状态非法 / 工具名无效等调用方错误。
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(RuntimeException e) {
        // 调用方错误属预期分支，记 debug 即可，不必污染 WARN 日志
        log.debug("Bad request: {}", e.getMessage());
        return ApiResponse.fail(e.getMessage());
    }
}
