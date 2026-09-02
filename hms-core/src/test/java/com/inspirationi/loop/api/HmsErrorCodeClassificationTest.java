package com.inspirationi.loop.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HmsErrorCode#isClientError()} 必须与类文档声明的错误码分组一致。
 * <p>
 * 集成方靠它决定 HTTP 状态码与日志级别：调用方错误返 4xx 且只记 debug，服务端
 * 故障返 5xx 并打完整堆栈。分错的代价是真实故障被噪声淹没 —— 空消息请求
 * （{@code 1005 INVALID_INPUT}）曾在服务端留下完整的 dispatcherServlet 异常栈。
 * <p>
 * 这里逐个枚举而非只测分组算术：新增错误码时若归错组，本测试会失败并提醒作者
 * 确认它属于哪一类。
 */
class HmsErrorCodeClassificationTest {

    /** 1xxx/2xxx/3xxx —— 参数、状态、权限，都是调用方该修的。 */
    @Test
    void clientErrorsAreClassifiedAsClient() {
        for (HmsErrorCode code : new HmsErrorCode[]{
                HmsErrorCode.INVALID_ARGUMENT,
                HmsErrorCode.INVALID_INPUT,
                HmsErrorCode.SERVICE_BUSY,
                HmsErrorCode.REQUEST_CANCELLED,
                HmsErrorCode.UNSUPPORTED_OPERATION,
                HmsErrorCode.SESSION_NOT_FOUND,
                HmsErrorCode.SESSION_PAUSED,
                HmsErrorCode.SESSION_DESTROYED,
                HmsErrorCode.SESSION_CREATE_FAILED,
                HmsErrorCode.SESSION_LIMIT_EXCEEDED,
                HmsErrorCode.PERMISSION_DENIED,
                HmsErrorCode.PERMISSION_NEEDS_CONFIRMATION,
                HmsErrorCode.RISK_BLOCKED,
        }) {
            assertTrue(code.isClientError(),
                    code + "(" + code.code() + ") 属 1xxx/2xxx/3xxx，应判为调用方错误");
        }
    }

    /** 5xxx/6xxx/7xxx —— 执行、模型调用、超时，属服务端或上游问题。 */
    @Test
    void serverErrorsAreNotClassifiedAsClient() {
        for (HmsErrorCode code : new HmsErrorCode[]{
                HmsErrorCode.EXECUTION_FAILED,
                HmsErrorCode.TOOL_EXECUTION_FAILED,
                HmsErrorCode.TOOL_NOT_FOUND,
                HmsErrorCode.MAX_ITERATIONS_REACHED,
                HmsErrorCode.AI_CALL_FAILED,
                HmsErrorCode.AI_INVALID_RESPONSE,
                HmsErrorCode.AI_AUTH_FAILED,
                HmsErrorCode.AI_QUOTA_EXCEEDED,
                HmsErrorCode.REQUEST_TIMEOUT,
                HmsErrorCode.AI_CALL_TIMEOUT,
                HmsErrorCode.TOOL_EXECUTION_TIMEOUT,
        }) {
            assertFalse(code.isClientError(),
                    code + "(" + code.code() + ") 属 5xxx 及以上，不应判为调用方错误");
        }
    }

    /**
     * 全量覆盖：每个错误码的判定都必须与其码段一致。
     * <p>
     * 上面两个用例是显式清单（新增码时提醒作者归类），这个用例保证没有漏网的 ——
     * 包括本测试写就之后才加进来的码。
     */
    @Test
    void everyCodeMatchesItsNumericGroup() {
        for (HmsErrorCode code : HmsErrorCode.values()) {
            int group = code.code() / 1000;
            boolean expected = group == 1 || group == 2 || group == 3;
            assertTrue(code.isClientError() == expected,
                    code + "(" + code.code() + ") 的分组判定与码段不一致：isClientError="
                            + code.isClientError() + "，但码段为 " + group + "xxx");
        }
    }
}
