package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.lsp.LSPServerManager;
import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写入后向语言服务器同步内容的测试。
 * <p>
 * 缺了这一步，模型「改文件 → 查诊断」拿到的是修改前的诊断，于是反复修同一个错误。
 */
class LspWriteSyncTest {

    @TempDir Path temp;

    /** 记录调用序列的替身；只覆写文件同步相关的几个方法。 */
    private static class RecordingManager extends LSPServerManager {
        final List<String> calls = new ArrayList<>();
        final Set<String> open = new HashSet<>();
        boolean running = true;

        RecordingManager() {
            super(Path.of("."));
        }

        @Override
        public boolean isServerRunningForFile(String filePath) {
            return running;
        }

        @Override
        public boolean isFileOpen(String filePath) {
            return open.contains(filePath);
        }

        @Override
        public void openFile(String filePath, String content) {
            open.add(filePath);
            calls.add("didOpen:" + content);
        }

        @Override
        public void changeFile(String filePath, String content) {
            calls.add("didChange:" + content);
        }

        @Override
        public void saveFile(String filePath) {
            calls.add("didSave");
        }
    }

    private ToolContext contextWith(LSPServerManager manager) throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, new Sandbox(root));
        if (manager != null) {
            ctx.set(LSPTool.MANAGER_KEY, manager);
        }
        return ctx;
    }

    /** 文件尚未在语言服务器里打开：先 didOpen 再 didSave。 */
    @Test
    void writeOpensThenSavesInLanguageServer() throws IOException {
        RecordingManager manager = new RecordingManager();
        ToolContext ctx = contextWith(manager);

        new FileWriteTool().execute(
                Map.of("file_path", "Main.java", "content", "class Main {}"), ctx);

        assertEquals(List.of("didOpen:class Main {}", "didSave"), manager.calls);
    }

    /** 已打开的文件：走 didChange 而不是重复 didOpen。 */
    @Test
    void editChangesThenSavesInLanguageServer() throws IOException {
        Path root = Sandbox.from(contextWith(null)).root();
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");

        RecordingManager manager = new RecordingManager();
        manager.open.add(root.resolve("Main.java").toString());
        ToolContext ctx = contextWith(manager);

        new FileEditTool().execute(Map.of(
                "file_path", "Main.java",
                "old_string", "class Main {}",
                "new_string", "class Main { int x; }"), ctx);

        assertEquals(List.of("didChange:class Main { int x; }\n", "didSave"), manager.calls);
    }

    /**
     * 没有运行中的服务器时必须什么都不做 —— {@code openFile} 会懒启动语言服务器，
     * 一次普通写文件不该拉起一个后台进程。
     */
    @Test
    void doesNotStartLanguageServerJustBecauseAFileWasWritten() throws IOException {
        RecordingManager manager = new RecordingManager();
        manager.running = false;
        ToolContext ctx = contextWith(manager);

        String result = new FileWriteTool().execute(
                Map.of("file_path", "notes.txt", "content", "hi"), ctx);

        assertTrue(result.startsWith("Created "), result);
        assertTrue(manager.calls.isEmpty(), "不得触发任何 LSP 调用，实际：" + manager.calls);
    }

    /** 会话没配语言服务器时，写文件照常成功。 */
    @Test
    void writeWorksWithoutLanguageServer() throws IOException {
        ToolContext ctx = contextWith(null);

        String result = new FileWriteTool().execute(
                Map.of("file_path", "a.txt", "content", "x"), ctx);

        assertTrue(result.startsWith("Created "), result);
    }
}
