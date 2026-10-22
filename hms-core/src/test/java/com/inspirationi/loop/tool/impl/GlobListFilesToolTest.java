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
