package com.inspirationi.hmsweb.controller;

import com.inspirationi.hmsweb.model.ApiResponse;
import com.inspirationi.loop.api.HmsErrorCode;
import com.inspirationi.loop.api.HmsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /**
     * hms-core 抛出的结构化异常 —— 按错误码分组决定状态码与日志级别。
     * <p>
     * 没有这个处理器时，{@code HmsException} 走 Spring 默认路径：返回 500，并在
     * 服务端留下完整的 dispatcherServlet 异常栈。而其中一大类（1xxx/2xxx/3xxx）
     * 是调用方错误 —— 例如发送空消息会得到 {@code code=1005 INVALID_INPUT}，
     * 那是客户端该修的参数问题，却按服务端故障记 ERROR，真实故障会淹没在这类噪声里。
     * <p>
     * 分组判定用 {@link HmsErrorCode#isClientError()}，规则维护在 core 的错误码
     * 体系内，不在此处复制一份。
     */
    @ExceptionHandler(HmsException.class)
    public ResponseEntity<ApiResponse<Void>> handleHmsException(HmsException e) {
        HmsErrorCode code = e.getErrorCode();
        boolean clientError = code != null && code.isClientError();

        if (clientError) {
            log.debug("Client error [{}]: {}", code, e.getMessage());
        } else {
            // 服务端/上游故障才值得完整堆栈
            log.error("HMS core failure [{}]: {}", code, e.getMessage(), e);
        }

        return ResponseEntity
                .status(clientError ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(e.getMessage()));
    }
}
