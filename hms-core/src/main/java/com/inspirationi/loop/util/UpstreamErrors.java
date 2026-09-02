package com.inspirationi.loop.util;

/**
 * 上游（provider SDK）异常的分类工具。
 * <p>
 * <b>按 HTTP 状态码文本判断，而非 provider 专有异常类型</b>：本项目同时支持 OpenAI
 * 与 Anthropic，两者异常层次完全不同，且都可能被 Reactor 包装成
 * {@code ReactiveException}；而两个 SDK 都会把状态码写进异常消息（如
 * {@code "403: {\"error\":{\"type\":\"forbidden\"}}"}）。依赖具体类型会让代码绑死在
 * 某一个 provider 上。
 */
public final class UpstreamErrors {

    private UpstreamErrors() {
    }

    /**
     * 客户端类错误 —— 换一种调用方式（如流式改阻塞）也必然复现。
     * <p>
     * 401/403 凭证或权限问题、404 模型名或端点错误、400/422 请求本身不合法、
     * 413 请求体过大。这些都不该靠「再试一次」解决。
     * <p>
     * <b>不含 429</b>：限流是暂时的，重试有意义。<b>不含 5xx</b>：服务端故障可能只
     * 影响流式端点，阻塞调用仍有机会成功。
     */
    private static final int[] NON_RETRYABLE_STATUS = {400, 401, 403, 404, 413, 422};

    /**
     * 异常是否属于「换个调用方式也必然复现」的客户端错误。
     * <p>
     * 判断<b>偏保守</b>：认不出来就返回 {@code false}（当作可重试），保留降级兜底
     * 路径。误判为可重试的代价只是多发一个请求；误判为不可重试则会让本可能成功的
     * 调用直接失败。
     *
     * @param error 上游异常（可为 {@code null}）
     * @return 不可重试时为 {@code true}
     */
    public static boolean isNonRetryable(Throwable error) {
        return findStatus(error, NON_RETRYABLE_STATUS) != 0;
    }

    /**
     * 在整条 cause 链的消息中查找首个命中的状态码。
     *
     * @param error    起始异常（可为 {@code null}）
     * @param statuses 待匹配的状态码
     * @return 命中的状态码；未命中为 {@code 0}
     */
    public static int findStatus(Throwable error, int... statuses) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && !message.isBlank()) {
                for (int status : statuses) {
                    if (hasStatus(message, status)) {
                        return status;
                    }
                }
            }
            if (t.getCause() == t) {
                break;   // 自引用的 cause 链，防止死循环
            }
        }
        return 0;
    }

    /**
     * 消息中是否含<b>独立出现</b>的该状态码。
     * <p>
     * 前后都不能紧邻数字 —— 否则 token 数、耗时毫秒里的 {@code "1403"}、
     * {@code "4030"} 会被误认成状态码，把普通失败错判成认证或配额问题。
     */
    public static boolean hasStatus(String message, int status) {
        if (message == null) {
            return false;
        }
        String code = String.valueOf(status);
        for (int from = 0; ; ) {
            int idx = message.indexOf(code, from);
            if (idx < 0) {
                return false;
            }
            int end = idx + code.length();
            boolean leftClear = idx == 0 || !Character.isDigit(message.charAt(idx - 1));
            boolean rightClear = end >= message.length() || !Character.isDigit(message.charAt(end));
            if (leftClear && rightClear) {
                return true;
            }
            from = idx + 1;
        }
    }
}
