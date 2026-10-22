package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    @Test
    void grepsMatchingLinesWithPathAndLineNumber() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.writeString(root.resolve("a.txt"), "alpha\nbeta\ngamma");

        String result = new GrepTool().execute(Map.of("pattern", "beta"), ctx);
        assertTrue(result.contains("a.txt:2:beta"));
    }

    @Test
    void caseInsensitiveOptionWorks() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("a.txt"), "Hello");

        assertTrue(new GrepTool().execute(Map.of("pattern", "hello"), ctx).contains("No matches"));
        assertTrue(new GrepTool().execute(
                Map.of("pattern", "hello", "case_insensitive", true), ctx).contains("Hello"));
    }

    @Test
    void globOptionFiltersFiles() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.writeString(root.resolve("A.java"), "needle");
        Files.writeString(root.resolve("B.txt"), "needle");

        String result = new GrepTool().execute(Map.of("pattern", "needle", "glob", "*.java"), ctx);
        assertTrue(result.contains("A.java"));
        assertFalse(result.contains("B.txt"));
    }

    @Test
    void invalidRegexReturnsError() throws IOException {
        String result = new GrepTool().execute(Map.of("pattern", "[unclosed"), context());
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.toLowerCase().contains("regular expression"));
    }

    @Test
    void rejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        Files.writeString(temp.resolve("outside.txt"), "needle");
        String result = new GrepTool().execute(Map.of("pattern", "needle", "path", "../"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertFalse(result.contains("needle"));
    }

    /** 错误文本不得泄露宿主绝对路径，只显示沙箱内的相对路径。 */
    @Test
    void missingDirectoryErrorUsesSandboxRelativePath() throws IOException {
        ToolContext ctx = context();

        String result = new GrepTool().execute(
                Map.of("pattern", "x", "path", "no-such-dir"), ctx);

        assertEquals("Error: Directory not found: no-such-dir", result);
    }

    @Test
    void skipsFilesLargerThanLimit() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("big.txt"), "needle".repeat(100));

        String result = new GrepTool(16).execute(Map.of("pattern", "needle"), ctx);
        assertFalse(result.contains("big.txt:1:"), "超过 max-file-bytes 的文件必须跳过");
    }

    @Test
    void skipsBinaryFiles() throws IOException {
        ToolContext ctx = context();
        Files.write(Sandbox.from(ctx).root().resolve("bin.dat"),
                new byte[]{'a', 'b', 0, 'c', 'd'});

        String result = new GrepTool().execute(Map.of("pattern", "a"), ctx);
        assertFalse(result.contains("bin.dat"));
    }

    @Test
    void respectsMaxResults() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.writeString(root.resolve("a.txt"), "needle");
        Files.writeString(root.resolve("b.txt"), "needle");

        String result = new GrepTool().execute(Map.of("pattern", "needle", "max_results", 1), ctx);
        long hits = result.lines().filter(line -> line.contains(":1:needle")).count();
        assertEquals(1, hits, "结果数不得超过 max_results");
    }
}
