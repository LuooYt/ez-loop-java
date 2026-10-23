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

    // ==================== ReDoS 防护 ====================

    /**
     * {@code (a+)+$} 对超长不匹配输入是指数级回溯 —— 在它开始之前就得拒绝。
     * 拒绝也必须瞬时完成，否则「先卡死再报错」没有意义。
     */
    @Test
    void rejectsNestedQuantifierPattern() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("a.txt"), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaab");

        long start = System.nanoTime();
        String result = new GrepTool().execute(Map.of("pattern", "(a+)+$"), ctx);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.startsWith("Error:"), "应拒绝而不是执行，实际：" + result);
        assertTrue(result.contains("backtrack"), "错误信息应说明原因：" + result);
        assertTrue(elapsedMs < 2000, "拒绝必须瞬时完成，实际耗时 " + elapsedMs + "ms");
    }

    /** 普通分组 + 量词不受影响：{@code (foo|bar)+} 并不可疑。 */
    @Test
    void ordinaryGroupedPatternIsStillAccepted() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("a.txt"), "foobar");

        String result = new GrepTool().execute(Map.of("pattern", "(foo|bar)+"), ctx);
        assertTrue(result.contains("foobar"), result);
    }

    /** 超长正则既没人读得懂，也更容易写出危险结构，直接拒绝。 */
    @Test
    void rejectsOverlongPattern() throws IOException {
        ToolContext ctx = context();
        String pattern = "a".repeat(1500);

        String result = new GrepTool().execute(Map.of("pattern", pattern), ctx);
        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("too long"), result);
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

    /**
     * {@code **}{@code /*.md} 要按 ripgrep 语义匹配「零或多级」目录：
     * Java 的 PathMatcher 要求至少一级，会让 base 目录直属的文件漏掉。
     * 实测模型在 {@code path=notes, glob=**}{@code /*.md} 下得到「No matches found」，
     * 与 GlobTool（已做零级变体）行为不一致。
     */
    @Test
    void doubleStarGlobAlsoMatchesFilesDirectlyUnderBase() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.createDirectories(root.resolve("notes/deep"));
        Files.writeString(root.resolve("notes/a.md"), "needle");
        Files.writeString(root.resolve("notes/deep/b.md"), "needle");
        Files.writeString(root.resolve("notes/c.txt"), "needle");

        String result = new GrepTool().execute(
                Map.of("pattern", "needle", "path", "notes", "glob", "**/*.md"), ctx);

        assertTrue(result.contains("notes/a.md"), "零级目录也要匹配，实际: " + result);
        assertTrue(result.contains("notes/deep/b.md"), result);
        assertFalse(result.contains("c.txt"), result);
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
