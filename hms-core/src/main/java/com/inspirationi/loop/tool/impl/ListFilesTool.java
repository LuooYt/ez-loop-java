package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxViolationException;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 目录列表工具 —— 对应 claude-code/src/tools/ls/LsTool.ts。
 * <p>
 * 列出沙箱内指定目录的文件与子目录，支持递归深度控制。
 * <b>不跟随符号链接</b>：链接目录按非目录项展示，不会递归进去。
 */
public class ListFilesTool implements Tool {

    private static final int DEFAULT_DEPTH = 1;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_ENTRIES = 500;

    /** 遍历中不进入的目录（与 Glob 保持一致）。 */
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "__pycache__", ".idea", ".vscode");

    @Override
    public String name() {
        return "ListFiles";
    }

    @Override
    public String description() {
        return """
            List files and directories inside the session sandbox. Returns a structured listing \
            with types and sizes. By default lists the immediate contents (depth=1, max 5). \
            Symbolic links are not followed. The path must be relative to the sandbox root.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Directory path relative to the sandbox root (default: sandbox root)"
                },
                "depth": {
                  "type": "integer",
                  "description": "Recursion depth (default: 1, max: 5)"
                },
                "includeHidden": {
                  "type": "boolean",
                  "description": "Whether to include hidden files/dirs (default: false)"
                }
              }
            }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        Sandbox sandbox = Sandbox.from(context);
        if (sandbox == null) {
            return "Error: Sandbox is not configured for this session.";
        }
        String pathStr = ToolValidator.getString(input, "path", ".");
        int depth = Math.min(ToolValidator.getInt(input, "depth", DEFAULT_DEPTH), MAX_DEPTH);
        if (depth < 1) depth = DEFAULT_DEPTH;
        boolean includeHidden = Boolean.TRUE.equals(input.get("includeHidden"));

        Path baseDir;
        try {
            baseDir = sandbox.resolve(pathStr);
        } catch (SandboxViolationException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isDirectory(baseDir, LinkOption.NOFOLLOW_LINKS)) {
            return "Error: Not a directory: " + pathStr;
        }

        try {
            List<String> entries = new ArrayList<>();
            listRecursive(sandbox, baseDir, depth, includeHidden, entries);

            if (entries.isEmpty()) {
                return "Directory is empty: " + pathStr;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Directory: ").append(sandbox.display(baseDir)).append('\n');
            sb.append("Entries: ").append(entries.size());
            if (entries.size() >= MAX_ENTRIES) {
                sb.append(" (truncated)");
            }
            sb.append("\n\n");
            for (String entry : entries) {
                sb.append(entry).append('\n');
            }
            return sb.toString().stripTrailing();

        } catch (IOException e) {
            return "Error listing directory: " + Tool.describeError(e);
        }
    }

    /** 递归列出目录内容，带缩进和类型标记。 */
    private void listRecursive(Sandbox sandbox, Path currentDir, int remainingDepth,
                               boolean includeHidden, List<String> entries) throws IOException {
        if (remainingDepth < 0 || entries.size() >= MAX_ENTRIES) {
            return;
        }

        List<Path> paths;
        try (Stream<Path> stream = Files.list(currentDir).sorted()) {
            paths = stream.toList();
        }

        for (Path path : paths) {
            if (entries.size() >= MAX_ENTRIES) break;

            String fileName = path.getFileName().toString();
            if (!includeHidden && fileName.startsWith(".")) {
                continue;
            }

            // 不跟随符号链接：链接目录按普通项展示，不递归进入
            boolean isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            if (isDir && IGNORED_DIRS.contains(fileName)) {
                continue;
            }

            String relStr = sandbox.display(path);
            if (isDir) {
                entries.add("📁 " + relStr + "/");
                if (remainingDepth > 1) {
                    listRecursive(sandbox, path, remainingDepth - 1, includeHidden, entries);
                }
            } else {
                long size;
                try {
                    size = Files.size(path);
                } catch (IOException e) {
                    continue; // 单个条目读取失败不影响整体列表
                }
                entries.add("📄 " + relStr + "  (" + formatSize(size) + ")");
            }
        }
    }

    /** 友好的文件大小格式化。 */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "📁 Listing " + input.getOrDefault("path", ".");
    }
}
