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

class FileReadToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    @Test
    void readsWholeTextFileWithLineNumbers() throws IOException {
        ToolContext ctx = context();
        Sandbox sandbox = Sandbox.from(ctx);
        Files.writeString(sandbox.root().resolve("a.txt"), "one\ntwo\nthree");

        String result = new FileReadTool().execute(Map.of("file_path", "a.txt"), ctx);
        assertTrue(result.contains("1 | one"));
        assertTrue(result.contains("3 | three"));
    }

    @Test
    void readsLineRange() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("a.txt"), "one\ntwo\nthree");
        String result = new FileReadTool().execute(
                Map.of("file_path", "a.txt", "line_start", 2, "line_end", 2), ctx);
        assertTrue(result.contains("2 | two"));
        assertFalse(result.contains("1 | one"));
    }

    @Test
    void rejectsPathOutsideSandbox() throws IOException {
        ToolContext ctx = context();
        String result = new FileReadTool().execute(Map.of("file_path", "../secret.txt"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("sandbox"));
    }

    @Test
    void reportsMissingFile() throws IOException {
        String result = new FileReadTool().execute(Map.of("file_path", "nope.txt"), context());
        assertTrue(result.startsWith("Error: File not found"));
    }

    @Test
    void returnsImageAsBase64Payload() throws IOException {
        ToolContext ctx = context();
        // 最小 PNG 头足以触发图片分支，不需要合法图片
        Files.write(Sandbox.from(ctx).root().resolve("pic.png"),
                new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        String result = new FileReadTool().execute(Map.of("file_path", "pic.png"), ctx);
        assertTrue(result.contains("image/png"));
        assertTrue(result.toLowerCase().contains("base64"));
    }

    @Test
    void rejectsFileLargerThanLimit() throws IOException {
        ToolContext ctx = context();
        Sandbox sandbox = Sandbox.from(ctx);
        Files.writeString(sandbox.root().resolve("big.txt"), "x".repeat(1024));
        FileReadTool tool = new FileReadTool(512); // 可注入上限，便于测试
        String result = tool.execute(Map.of("file_path", "big.txt"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("too large"));
    }
}
