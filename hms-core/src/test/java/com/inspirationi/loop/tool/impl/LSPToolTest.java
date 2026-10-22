package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.lsp.LSPServerManager;
import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LSPToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, new Sandbox(root));
        return ctx;
    }

    @Test
    void reportsMissingManager() throws IOException {
        String result = new LSPTool().execute(Map.of(
                "operation", "hover", "file_path", "a.java", "line", 1, "character", 0), context());
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("LSP"), result);
    }

    @Test
    void rejectsUnknownOperation() throws IOException {
        ToolContext ctx = context();
        ctx.set("LSP_SERVER_MANAGER", new LSPServerManager(Sandbox.from(ctx).root()));

        String result = new LSPTool().execute(Map.of(
                "operation", "renameEverything", "file_path", "a.java", "line", 1, "character", 0), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("operation"), result);
    }
}
