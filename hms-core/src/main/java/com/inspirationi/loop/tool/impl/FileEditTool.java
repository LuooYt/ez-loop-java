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
import java.util.List;
import java.util.Map;

/**
 * 文件编辑工具 —— 对应 claude-code/src/tools/edit/EditFileTool.ts。
 * <p>
 * 通过精确匹配 old_string 并替换为 new_string 来编辑文件。
 * old_string 必须<b>恰好</b>出现一次：零处或多处都拒绝，避免改错位置。
 * 编辑后返回 unified diff 格式的变更摘要。
 */
public class FileEditTool implements Tool {

    @Override
    public String name() {
        return "Edit";
    }

    @Override
    public String description() {
        return """
            Make a targeted edit to a file by replacing an exact string match with new content. \
            Performs exact string replacements — the old_string must match exactly one location in the file.

            IMPORTANT RULES:
            - Read the file with the Read tool before editing it.
            - Preserve the exact indentation (tabs/spaces) as it appears in the file.
            - If old_string matches multiple locations, include more context to disambiguate.
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
                "old_string": {
                  "type": "string",
                  "description": "The exact string to find and replace (must be unique)"
                },
                "new_string": {
                  "type": "string",
                  "description": "The replacement string"
                }
              },
              "required": ["file_path", "old_string", "new_string"]
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
        Object rawOld = input.get("old_string");
        if (!(rawOld instanceof String oldString)) {
            return "Error: 'old_string' is required.";
        }
        Object rawNew = input.get("new_string");
        if (!(rawNew instanceof String newString)) {
            return "Error: 'new_string' is required.";
        }

        String filePath = (String) input.get("file_path");
        Path path;
        try {
            path = sandbox.resolve(filePath);
        } catch (SandboxViolationException e) {
            return "Error: " + e.getMessage();
        }
        String display = sandbox.display(path);

        if (!Files.exists(path)) {
            return "Error: File not found: " + display;
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);

            int firstIdx = content.indexOf(oldString);
            if (firstIdx == -1) {
                return "Error: old_string not found in file " + display;
            }
            int secondIdx = content.indexOf(oldString, firstIdx + 1);
            if (secondIdx != -1) {
                return "Error: old_string matches multiple locations in " + display
                        + ". Be more specific.";
            }

            String newContent = content.substring(0, firstIdx) + newString
                    + content.substring(firstIdx + oldString.length());
            Files.writeString(path, newContent, StandardCharsets.UTF_8);

            String diff = generateUnifiedDiff(display, content, newContent);
            long oldLines = oldString.lines().count();
            long newLines = newString.lines().count();

            return "Edited " + display + " (replaced " + oldLines
                    + " lines with " + newLines + " lines)\n\n" + diff;

        } catch (IOException e) {
            return "Error editing file " + display + ": " + Tool.describeError(e);
        }
    }

    /**
     * 生成 unified diff 格式输出（纯 Java 实现，前后各保留 3 行上下文）。
     */
    private static String generateUnifiedDiff(String filePath, String original, String modified) {
        List<String> oldLines = original.lines().toList();
        List<String> newLines = modified.lines().toList();
        int contextLines = 3;

        int firstDiff = 0;
        int minLen = Math.min(oldLines.size(), newLines.size());
        while (firstDiff < minLen && oldLines.get(firstDiff).equals(newLines.get(firstDiff))) {
            firstDiff++;
        }

        int oldEnd = oldLines.size();
        int newEnd = newLines.size();
        while (oldEnd > firstDiff && newEnd > firstDiff
                && oldLines.get(oldEnd - 1).equals(newLines.get(newEnd - 1))) {
            oldEnd--;
            newEnd--;
        }

        int ctxStart = Math.max(0, firstDiff - contextLines);
        int oldCtxEnd = Math.min(oldLines.size(), oldEnd + contextLines);
        int newCtxEnd = Math.min(newLines.size(), newEnd + contextLines);

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append('\n');
        sb.append("+++ b/").append(filePath).append('\n');

        int oldHunkSize = oldCtxEnd - ctxStart;
        int newHunkSize = newCtxEnd - ctxStart - (oldEnd - firstDiff) + (newEnd - firstDiff);
        sb.append(String.format("@@ -%d,%d +%d,%d @@%n",
                ctxStart + 1, oldHunkSize, ctxStart + 1, newHunkSize));

        for (int i = ctxStart; i < firstDiff; i++) {
            sb.append(' ').append(oldLines.get(i)).append('\n');
        }
        for (int i = firstDiff; i < oldEnd; i++) {
            sb.append('-').append(oldLines.get(i)).append('\n');
        }
        for (int i = firstDiff; i < newEnd; i++) {
            sb.append('+').append(newLines.get(i)).append('\n');
        }
        for (int i = oldEnd; i < oldCtxEnd; i++) {
            sb.append(' ').append(oldLines.get(i)).append('\n');
        }

        return sb.toString().stripTrailing();
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "✏️ Editing " + input.getOrDefault("file_path", "file");
    }
}
