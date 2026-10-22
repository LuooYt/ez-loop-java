package com.inspirationi.loop.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxViolationException;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Jupyter Notebook 编辑工具 —— 对应 claude-code/src/tools/NotebookEditTool.ts。
 * <p>
 * 操作 .ipynb 文件的单元格：插入、替换、删除。只接受沙箱内以 {@code .ipynb}
 * 结尾的路径，其余单元格保持原样。
 */
public class NotebookEditTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认文本文件上限（与 SandboxProperties 默认值一致）。 */
    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    private final long maxBytes;

    public NotebookEditTool() {
        this(DEFAULT_MAX_BYTES);
    }

    public NotebookEditTool(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public String name() {
        return "NotebookEdit";
    }

    @Override
    public String description() {
        return """
            Edit Jupyter Notebook (.ipynb) cells inside the session sandbox. \
            Supports replace, insert and delete operations on notebook cells \
            (nbformat 4.x). Cell numbers are 0-based. \
            The path must be relative to the sandbox root and end with .ipynb.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "notebook_path": {
                  "type": "string",
                  "description": "Path to the .ipynb notebook, relative to the sandbox root"
                },
                "cell_number": {
                  "type": "integer",
                  "description": "Target cell index (0-based). For insert, an index >= cell count appends."
                },
                "new_source": {
                  "type": "string",
                  "description": "Cell content (required for insert and replace)"
                },
                "cell_type": {
                  "type": "string",
                  "enum": ["code", "markdown", "raw"],
                  "description": "Cell type (default: code)"
                },
                "edit_mode": {
                  "type": "string",
                  "enum": ["replace", "insert", "delete"],
                  "description": "Edit operation (default: replace)"
                }
              },
              "required": ["notebook_path"]
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
        String err = ToolValidator.requireString(input, "notebook_path");
        if (err != null) return err;
        String pathStr = (String) input.get("notebook_path");
        String operation = ToolValidator.getString(input, "edit_mode", "replace");

        Path filePath;
        try {
            filePath = sandbox.resolve(pathStr);
        } catch (SandboxViolationException e) {
            return "Error: " + e.getMessage();
        }
        if (!filePath.getFileName().toString().toLowerCase().endsWith(".ipynb")) {
            return "Error: NotebookEdit only supports .ipynb files.";
        }

        return switch (operation) {
            case "insert" -> insertCell(filePath, input);
            case "replace" -> replaceCell(filePath, input);
            case "delete" -> deleteCell(filePath, input);
            default -> "Error: Unknown edit_mode '" + operation + "'. Use: replace, insert, delete";
        };
    }

    /** 在指定位置插入新单元格（索引越界时追加到末尾）。 */
    private String insertCell(Path filePath, Map<String, Object> input) {
        int cellIndex = ToolValidator.getInt(input, "cell_number", -1);
        String cellType = ToolValidator.getString(input, "cell_type", "code");
        String source = input.get("new_source") instanceof String s ? s : null;

        if (source == null) {
            return "Error: 'new_source' is required for insert operation.";
        }

        try {
            ObjectNode root = readNotebookJson(filePath);
            ArrayNode cells = cellsOf(root);
            if (cells == null) {
                return NO_CELLS;
            }

            ObjectNode newCell = createCell(cellType, source);
            if (cellIndex < 0 || cellIndex >= cells.size()) {
                cells.add(newCell);
                cellIndex = cells.size() - 1;
            } else {
                cells.insert(cellIndex, newCell);
            }
            writeNotebookJson(filePath, root);
            return "Inserted " + cellType + " cell at index " + cellIndex;

        } catch (IOException e) {
            return "Error: " + Tool.describeError(e);
        }
    }

    /** 替换指定单元格内容。 */
    private String replaceCell(Path filePath, Map<String, Object> input) {
        if (!(input.get("cell_number") instanceof Number)) {
            return "Error: 'cell_number' is required for replace operation.";
        }
        int cellIndex = ((Number) input.get("cell_number")).intValue();
        String source = input.get("new_source") instanceof String s ? s : null;
        if (source == null) {
            return "Error: 'new_source' is required for replace operation.";
        }

        try {
            ObjectNode root = readNotebookJson(filePath);
            ArrayNode cells = cellsOf(root);
            if (cells == null) {
                return NO_CELLS;
            }
            if (cellIndex < 0 || cellIndex >= cells.size()) {
                return "Error: Cell index " + cellIndex + " out of range (0-"
                        + (cells.size() - 1) + ").";
            }
            if (!cells.get(cellIndex).isObject()) {
                return "Error: Invalid notebook format (cell " + cellIndex + " is not an object).";
            }

            String cellType = input.get("cell_type") instanceof String s && !s.isBlank()
                    ? s
                    : cellTypeOf(cells.get(cellIndex));
            cells.set(cellIndex, createCell(cellType, source));
            writeNotebookJson(filePath, root);
            return "Replaced cell " + cellIndex + " [" + cellType + "]";

        } catch (IOException e) {
            return "Error: " + Tool.describeError(e);
        }
    }

    /** 删除指定单元格。 */
    private String deleteCell(Path filePath, Map<String, Object> input) {
        if (!(input.get("cell_number") instanceof Number)) {
            return "Error: 'cell_number' is required for delete operation.";
        }
        int cellIndex = ((Number) input.get("cell_number")).intValue();

        try {
            ObjectNode root = readNotebookJson(filePath);
            ArrayNode cells = cellsOf(root);
            if (cells == null) {
                return NO_CELLS;
            }
            if (cellIndex < 0 || cellIndex >= cells.size()) {
                return "Error: Cell index " + cellIndex + " out of range (0-"
                        + (cells.size() - 1) + ").";
            }
            if (!cells.get(cellIndex).isObject()) {
                return "Error: Invalid notebook format (cell " + cellIndex + " is not an object).";
            }

            String cellType = cellTypeOf(cells.get(cellIndex));
            cells.remove(cellIndex);
            writeNotebookJson(filePath, root);
            return "Deleted cell " + cellIndex + " [" + cellType
                    + "]. Remaining: " + cells.size() + " cells";

        } catch (IOException e) {
            return "Error: " + Tool.describeError(e);
        }
    }

    // ==================== 辅助方法 ====================

    private static final String NO_CELLS = "Error: Invalid notebook format (no 'cells' array).";

    /** 取 {@code cells} 数组；不是数组时返回 null（nbformat 允许任意 JSON，不能直接强转）。 */
    private static ArrayNode cellsOf(ObjectNode root) {
        JsonNode cells = root.get("cells");
        return cells instanceof ArrayNode array ? array : null;
    }

    /** 读单元格类型，缺失或非字符串时按 code 处理。 */
    private static String cellTypeOf(JsonNode cell) {
        JsonNode type = cell.get("cell_type");
        return type != null && type.isTextual() ? type.asText() : "code";
    }

    /** 读取 notebook JSON；文件不存在、超限或格式非法都转成 IOException。 */
    private ObjectNode readNotebookJson(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filePath.getFileName());
        }
        // notebook 要整份读进内存再改写，先卡上限，避免超大文件打满堆。
        long size = Files.size(filePath);
        if (size > maxBytes) {
            throw new IOException("File is too large (" + formatSize(size)
                    + "). Raise hms-core.sandbox.max-file-bytes to edit it.");
        }
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(content);
        if (root == null || !root.isObject()) {
            throw new IOException("Invalid notebook JSON: " + filePath.getFileName());
        }
        return (ObjectNode) root;
    }

    /** 写回 notebook JSON（保持缩进格式）。 */
    private static void writeNotebookJson(Path filePath, JsonNode root) throws IOException {
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(filePath, json, StandardCharsets.UTF_8);
    }

    /** 人类可读的字节数，用于超限错误提示。 */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** 创建 nbformat 4.x 单元格节点。 */
    private static ObjectNode createCell(String cellType, String source) {
        ObjectNode cell = MAPPER.createObjectNode();
        cell.put("cell_type", cellType);

        ArrayNode sourceArray = MAPPER.createArrayNode();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sourceArray.add(i < lines.length - 1 ? lines[i] + "\n" : lines[i]);
        }
        cell.set("source", sourceArray);
        cell.set("metadata", MAPPER.createObjectNode());

        if ("code".equals(cellType)) {
            cell.putNull("execution_count");
            cell.set("outputs", MAPPER.createArrayNode());
        }
        return cell;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String op = String.valueOf(input.getOrDefault("edit_mode", "edit"));
        String path = String.valueOf(input.getOrDefault("notebook_path", "notebook"));
        return "📓 Notebook " + op + ": " + path;
    }
}
