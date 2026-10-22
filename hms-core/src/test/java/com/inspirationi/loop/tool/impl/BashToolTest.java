package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    @TempDir Path temp;

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    private BashTool tool(int timeoutSeconds, int maxOutputChars) {
        SandboxProperties.Bash props = new SandboxProperties.Bash();
        props.setTimeoutSeconds(timeoutSeconds);
        props.setMaxOutputChars(maxOutputChars);
        return new BashTool(props);
    }

    @Test
    void executesCommandInSandboxDirectory() throws IOException {
        ToolContext ctx = context();
        String result = tool(10, 10_000).execute(Map.of("command", "echo hello"), ctx);
        assertTrue(result.contains("hello"));
        assertTrue(result.contains("[Exit code: 0]") || result.contains("Exit code: 0"));
    }

    @Test
    void runsInSandboxWorkingDirectory() throws IOException {
        ToolContext ctx = context();
        String result = tool(10, 10_000).execute(
                Map.of("command", isWindows() ? "cd" : "pwd"), ctx);

        String expected = Sandbox.from(ctx).root().toString()
                .replace('\\', '/').toLowerCase();
        assertTrue(result.replace('\\', '/').toLowerCase().contains(expected),
                "cwd 必须是会话沙箱：" + result);
    }

    @Test
    void reportsNonZeroExitCode() throws IOException {
        String result = tool(10, 10_000).execute(Map.of("command", "exit 3"), context());
        assertTrue(result.contains("3"), "非零退出码必须回传给模型");
    }

    @Test
    void timeoutKillsProcessAndReturnsQuickly() throws IOException {
        String slow = isWindows()
                ? "cmd /c ping -n 6 127.0.0.1 >nul"
                : "sleep 5";
        long start = System.nanoTime();
        String result = tool(1, 10_000).execute(Map.of("command", slow), context());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.toLowerCase().contains("timed out"), "超时必须明确报告");
        assertTrue(elapsedMs < 4_000, "超时后必须立即返回，实际用时 " + elapsedMs + "ms");
    }

    @Test
    void rejectsDangerousCommand() throws IOException {
        ToolContext ctx = context();
        Path marker = Sandbox.from(ctx).root().resolve("should-not-exist.txt");
        String result = tool(10, 10_000).execute(
                Map.of("command", "rm -rf / && touch " + marker.getFileName()), ctx);
        assertTrue(result.startsWith("Error:"));
        assertFalse(Files.exists(marker));
    }

    @Test
    void truncatesLongOutput() throws IOException {
        String result = tool(10, 10).execute(
                Map.of("command", "echo 0123456789abcdefghij"), context());
        assertTrue(result.contains("output truncated"));
    }

    @Test
    void descriptionStatesItIsNotASecurityBoundary() {
        String description = tool(10, 10_000).description();
        assertTrue(description.contains("不是安全边界"),
                "工具描述必须明确 Bash 不是安全边界");
    }
}
