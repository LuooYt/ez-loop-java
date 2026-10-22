package com.inspirationi.loop.tool.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessExecutorTest {

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 持续输出大量内容的命令（两个平台各写一遍，避免依赖外部工具）。 */
    private static String noisyCommand() {
        return windows()
                ? "for /L %i in (1,1,20000) do @echo 0123456789012345678901234567890123456789"
                : "yes 0123456789012345678901234567890123456789 | head -n 20000";
    }

    /**
     * 无上限的 ByteArrayOutputStream 会让一条刷屏命令在超时前就把堆打满
     * （例如 {@code yes} 跑 120 秒）。缓冲区必须有界，且仍然把管道读空。
     */
    @Test
    void capsOutputInsteadOfBufferingEverything() {
        ProcessExecutor.Result result =
                ProcessExecutor.executeShell(noisyCommand(), null, 60_000, 1024);

        assertFalse(result.timedOut(), "缓冲区满不得导致进程卡死超时");
        assertFalse(result.output().isEmpty(), "超限时应保留开头部分输出");
        int bytes = result.output().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 1024, "输出必须被截断到上限以内，实际 " + bytes + " 字节");
    }

    /** 未超限时输出必须完整保留。 */
    @Test
    void keepsCompleteOutputUnderTheLimit() {
        String command = windows() ? "echo hello" : "echo hello";

        ProcessExecutor.Result result = ProcessExecutor.executeShell(command, null, 30_000);

        assertTrue(result.output().contains("hello"), result.output());
    }
}
