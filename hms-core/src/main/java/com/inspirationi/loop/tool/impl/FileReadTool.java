package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxViolationException;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * 文件读取工具 —— 对应 claude-code/src/tools/read/ReadFileTool.ts。
 * <p>
 * 读取文件内容，支持行号范围过滤和图片读取（Base64 编码）。
 * <p>
 * <b>路径只能相对于会话沙箱</b>：所有路径都经 {@link Sandbox#resolve(String)} 校验，
 * 绝对路径与越界路径一律拒绝，工具返回的错误文本对模型可读。
 */
public class FileReadTool implements Tool {

    /** 单次读取最大行数 */
    private static final int MAX_LINES = 2000;

    /** 默认文件上限（与 SandboxProperties 默认值一致），文本与图片共用 */
    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    /** 支持的图片扩展名 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico"
    );

    private final long maxBytes;

    public FileReadTool() {
        this(DEFAULT_MAX_BYTES);
    }

    /** 包内构造器：供测试注入更小的上限。 */
    public FileReadTool(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public String name() {
        return "Read";
    }

    @Override
    public String description() {
        return """
            Read the contents of a file inside the session sandbox. Supports text files \
            with optional line ranges, and image files (png, jpg, jpeg, gif, webp, svg, bmp, ico) \
            which are returned as Base64-encoded data with MIME type. For large text files, \
            read in chunks using line_start and line_end. \
            The path must be relative to the sandbox root.""";
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
                "line_start": {
                  "type": "integer",
                  "description": "Starting line number (1-based, inclusive). Only for text files."
                },
                "line_end": {
                  "type": "integer",
                  "description": "Ending line number (1-based, inclusive). Only for text files."
                }
              },
              "required": ["file_path"]
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
        Object rawPath = input.get("file_path");
        if (!(rawPath instanceof String filePath) || filePath.isBlank()) {
            return "Error: file_path is required.";
        }

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
        if (!Files.isRegularFile(path)) {
            return "Error: Not a regular file: " + display;
        }

        String ext = getExtension(path.getFileName().toString());
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return readImage(path, ext, display);
        }
        return readText(path, input, display);
    }

    /** 读取图片文件，返回 Base64 编码数据和 MIME 类型信息。 */
    private String readImage(Path path, String ext, String display) {
        try {
            long size = Files.size(path);
            // 与文本共用 hms-core.sandbox.max-file-bytes：图片也要进内存做 Base64，
            // 单独写死一个上限会让配置对图片失效。
            if (size > maxBytes) {
                return "Error: Image too large (" + formatSize(size) + "). Max: "
                        + formatSize(maxBytes)
                        + " (hms-core.sandbox.max-file-bytes).";
            }
            if (size == 0) {
                return "Error: Image file is empty: " + display;
            }

            byte[] bytes = Files.readAllBytes(path);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mimeType = getMimeType(ext);

            return String.format("""
                {"type": "image", "file_path": "%s", "mime_type": "%s", \
                "size": %d, "base64_length": %d, "base64": "%s"}""",
                    escapeJson(display), mimeType, size, base64.length(), base64);

        } catch (IOException e) {
            return "Error reading image: " + Tool.describeError(e);
        }
    }

    /** 读取文本文件，支持行范围过滤。 */
    private String readText(Path path, Map<String, Object> input, String display) {
        try {
            long size = Files.size(path);
            if (size > maxBytes) {
                return "Error: File is too large (" + formatSize(size)
                        + "). Read it in chunks or increase hms-core.sandbox.max-file-bytes.";
            }

            var allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int total = allLines.size();

            // 空文件没有任何行：按行号范围处理会把 start=1、end=0 误判成参数错误
            if (total == 0) {
                return "";
            }

            int start = 1;
            int end = total;

            if (input.get("line_start") instanceof Number n) {
                start = n.intValue();
            }
            if (input.get("line_end") instanceof Number n) {
                end = n.intValue();
            }

            start = Math.max(1, start);
            end = Math.min(total, end);

            if (start > end) {
                return "Error: line_start (" + start + ") > line_end (" + end + ")";
            }

            if (end - start + 1 > MAX_LINES) {
                end = start + MAX_LINES - 1;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = start - 1; i < end; i++) {
                sb.append(String.format("%4d | %s%n", i + 1, allLines.get(i)));
            }

            if (end < total) {
                sb.append(String.format("... (%d more lines)%n", total - end));
            }

            return sb.toString().stripTrailing();

        } catch (IOException e) {
            return "Error reading file " + display + ": " + Tool.describeError(e);
        }
    }

    /** 获取文件扩展名（小写，不含点）。 */
    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }

    /** 根据扩展名返回 MIME 类型。 */
    private static String getMimeType(String ext) {
        return switch (ext) {
            case "png"  -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif"  -> "image/gif";
            case "webp" -> "image/webp";
            case "svg"  -> "image/svg+xml";
            case "bmp"  -> "image/bmp";
            case "ico"  -> "image/x-icon";
            default     -> "application/octet-stream";
        };
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String fp = String.valueOf(input.getOrDefault("file_path", "file"));
        String ext = getExtension(fp);
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return "🖼️ Reading image " + fp;
        }
        return "📄 Reading " + fp;
    }
}
