package com.inspirationi.loop.tool.impl;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试策略：只有瞬时故障才值得重试。
 * <p>
 * 超时若也重试，一次抓取会占住 Agent 90 秒以上（30s 超时 × 3 次尝试）—— 用户看到
 * 的就是「卡死」。子 Agent 里连续几次这样的抓取，整个任务十几分钟没有输出。
 */
class WebFetchRetryPolicyTest {

    @Test
    void timeoutsAreNotRetried() {
        assertFalse(WebFetchTool.isRetryable(new HttpTimeoutException("read timed out")),
                "超时不应重试：再等一整个超时大概率还是超时");
    }

    @Test
    void connectionFailuresAreRetried() {
        assertTrue(WebFetchTool.isRetryable(new ConnectException("connection reset")),
                "连接被重置这类瞬时故障应重试");
    }
}
