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

class FileWriteEditToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    @Test
    void writeCreatesFileAndParentDirectories() throws IOException {
        ToolContext ctx = context();
        String result = new FileWriteTool().execute(
                Map.of("file_path", "a/b/c.txt", "content", "hello"), ctx);
        assertTrue(result.startsWith("Created"));
        assertEquals("hello", Files.readString(Sandbox.from(ctx).root().resolve("a/b/c.txt")));
    }

    @Test
    void writeOverwritesExistingFile() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("a.txt");
        Files.writeString(file, "old");
        String result = new FileWriteTool().execute(
                Map.of("file_path", "a.txt", "content", "new"), ctx);
        assertTrue(result.startsWith("Updated"));
        assertEquals("new", Files.readString(file));
    }

    @Test
    void writeRejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        String result = new FileWriteTool().execute(
                Map.of("file_path", "../escape.txt", "content", "x"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
    }

    @Test
    void writeRejectsContentBeyondLimit() throws IOException {
        ToolContext ctx = context();
        String result = new FileWriteTool(4).execute(
                Map.of("file_path", "a.txt", "content", "12345"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("too large"));
    }

    @Test
    void editReplacesSingleOccurrenceAndReturnsDiff() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("a.txt");
        Files.writeString(file, "hello world");
        String result = new FileEditTool().execute(Map.of(
                "file_path", "a.txt", "old_string", "world", "new_string", "sandbox"), ctx);
        assertEquals("hello sandbox", Files.readString(file));
        assertTrue(result.contains("hello world"), "diff 必须包含被删除的行");
        assertTrue(result.contains("hello sandbox"), "diff 必须包含新增的行");
    }

    @Test
    void editFailsWhenOldStringMissing() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("a.txt"), "hello");
        String result = new FileEditTool().execute(Map.of(
                "file_path", "a.txt", "old_string", "absent", "new_string", "x"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("not found"));
    }

    @Test
    void editFailsWhenOldStringIsAmbiguous() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("a.txt");
        Files.writeString(file, "x x");
        String result = new FileEditTool().execute(Map.of(
                "file_path", "a.txt", "old_string", "x", "new_string", "y"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("multiple") || result.contains("2"));
        assertEquals("x x", Files.readString(file), "匹配歧义时不得写入");
    }

    @Test
    void editRefusesFilesLargerThanLimit() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("big.txt");
        Files.writeString(file, "x".repeat(2048));

        String result = new FileEditTool(1024).execute(Map.of(
                "file_path", "big.txt", "old_string", "x", "new_string", "y"), ctx);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("too large"), result);
        assertEquals("x".repeat(2048), Files.readString(file), "超限时不得读取或写入");
    }

    @Test
    void editRejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        String result = new FileEditTool().execute(Map.of(
                "file_path", "../a.txt", "old_string", "a", "new_string", "b"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("sandbox"));
    }
}
