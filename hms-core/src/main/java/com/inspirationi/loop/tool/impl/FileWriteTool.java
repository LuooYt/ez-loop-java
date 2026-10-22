package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxViolationException;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * 文件写入工具 —— 对应 claude-code/src/tools/write/WriteFileTool.ts。
 * <p>
 * 将内容写入文件（创建或覆盖），自动创建沙箱内的父目录。
 */
public class FileWriteTool implements Tool {

    /** 默认写入上限（与 SandboxProperties 默认值一致） */
    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    private final long maxBytes;

    public FileWriteTool() {
        this(DEFAULT_MAX_BYTES);
    }

    /** 包内构造器：供测试注入更小的上限。 */
    public FileWriteTool(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public String name() {
        return "Write";
    }

    @Override
    public String description() {
        return """
            Write content to a file inside the session sandbox. Creates the file and any \
            parent directories if they don't exist. If the file exists, it will be overwritten.

            IMPORTANT RULES:
            - If this is an existing file, read it first with the Read tool before overwriting.
            - Prefer the Edit tool for modifying existing files — it only sends the diff and is safer.
            - Only use Write to create new files or for complete rewrites.
            - The path must be relative to the sandbox root.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "file_path": {
                  "type": "string",
                  "description": "Path relative to the session sandbox root"
                },
                "content": {
                  "type": "string",
                  "description": "The content to write to the file"
                }
              },
              "required": ["file_path", "content"]
            }""";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        Sandbox sandbox = Sandbox.from(context);
        if (sandbox == null) {
            return "Error: Sandbox is not configured for this session.";
        }
        String err = ToolValidator.requireString(input, "file_path");
        if (err != null) return err;
        Object rawContent = input.get("content");
        if (!(rawContent instanceof String content)) {
            return "Error: 'content' is required.";
        }

        String filePath = (String) input.get("file_path");
        Path path;
        try {
            path = sandbox.resolve(filePath);
        } catch (SandboxViolationException e) {
            return "Error: " + e.getMessage();
        }

        long bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            return "Error: Content is too large (" + bytes + " bytes). Max: " + maxBytes
                    + ". Split the write or increase hms-core.sandbox.max-file-bytes.";
        }

        String display = sandbox.display(path);
        try {
            // 自动创建父目录（路径本身已通过沙箱校验，父目录必然在沙箱内）
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            boolean existed = Files.exists(path);
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            long lines = content.lines().count();
            return (existed ? "Updated " : "Created ") + display + " (" + lines + " lines)";

        } catch (IOException e) {
            return "Error writing file " + display + ": " + Tool.describeError(e);
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "✏️ Writing " + input.getOrDefault("file_path", "file");
    }
}
