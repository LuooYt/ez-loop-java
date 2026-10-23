package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobListFilesToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    @Test
    void globFindsNestedMatches() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/a.txt"), "a");
        Files.writeString(root.resolve("sub/b.md"), "b");

        String result = new GlobTool().execute(Map.of("pattern", "**/*.txt"), ctx);
        assertTrue(result.contains("sub/a.txt"));
        assertFalse(result.contains("sub/b.md"));
    }

    @Test
    void globDoesNotFollowSymlinkedDirectories() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symlink not supported: " + e.getMessage());
        }

        String result = new GlobTool().execute(Map.of("pattern", "**/*.txt"), ctx);
        assertFalse(result.contains("secret.txt"), "遍历不得跟随符号链接目录");
    }

    /**
     * {@code **}{@code /} 在 gitignore 语义里表示「零或多级目录」。Java 的 glob
     * 把它当成「至少一级」，于是 {@code **}{@code /*.java} 反而漏掉根目录下的文件。
     */
    @Test
    void globDoubleStarAlsoMatchesBaseDirectory() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.writeString(root.resolve("Main.java"), "x");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/Other.java"), "x");

        String result = new GlobTool().execute(Map.of("pattern", "**/*.java"), ctx);

        assertTrue(result.contains("Main.java"), "**/ 必须匹配零级目录:\n" + result);
        assertTrue(result.contains("sub/Other.java"), "**/ 必须匹配多级目录:\n" + result);
    }

    /**
     * {@code **}{@code /} 的「零或多级」语义靠枚举变体实现，每多一段翻倍。
     * 20 段就是 100 万个变体 —— 必须在开始遍历之前就拒绝，否则线程直接卡死。
     */
    @Test
    void globRejectsPatternWithTooManyWildcardSegments() throws IOException {
        ToolContext ctx = context();
        String pattern = "**/".repeat(20) + "*.java";

        long start = System.nanoTime();
        String result = new GlobTool().execute(Map.of("pattern", pattern), ctx);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.startsWith("Error:"), "应作为参数错误拒绝，实际：" + result);
        assertTrue(result.contains("**/"), "错误信息应点明问题所在：" + result);
        assertTrue(elapsedMs < 2000, "拒绝必须是即时的，实际耗时 " + elapsedMs + "ms");
    }

    /** 常规 pattern 不受上限影响。 */
    @Test
    void ordinaryPatternsAreAccepted() {
        assertNull(GlobTool.checkPatternCost("**/*.java"));
        assertNull(GlobTool.checkPatternCost("src/**/test/**/*.java"));
        assertNotNull(GlobTool.checkPatternCost("**/".repeat(7) + "*.java"));
    }

    @Test
    void globRejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        String result = new GlobTool().execute(Map.of("pattern", "*.txt", "path", "../"), ctx);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void globStopsAtNodeLimit() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        for (int i = 0; i < 5; i++) {
            Files.writeString(root.resolve("f" + i + ".txt"), "x");
        }
        String result = new GlobTool(1).execute(Map.of("pattern", "*.txt"), ctx);
        assertTrue(result.contains("truncated"), "触发节点上限时必须显式标注截断");
    }

    @Test
    void listFilesShowsEntriesWithSize() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.writeString(root.resolve("a.txt"), "abc");
        Files.createDirectories(root.resolve("sub"));

        String result = new ListFilesTool().execute(Map.of(), ctx);
        assertTrue(result.contains("a.txt"));
        assertTrue(result.contains("3"), "文件大小必须出现在输出中");
        assertTrue(result.contains("sub"));
    }

    @Test
    void listFilesRecursesToRequestedDepth() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.createDirectories(root.resolve("a/b/c"));
        Files.writeString(root.resolve("a/b/c/deep.txt"), "x");

        String shallow = new ListFilesTool().execute(Map.of("depth", 1), ctx);
        assertFalse(shallow.contains("deep.txt"));
        String deep = new ListFilesTool().execute(Map.of("depth", 4), ctx);
        assertTrue(deep.contains("deep.txt"));
    }

    @Test
    void listFilesRejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        String result = new ListFilesTool().execute(Map.of("path", "../"), ctx);
        assertTrue(result.startsWith("Error:"));
    }
}
