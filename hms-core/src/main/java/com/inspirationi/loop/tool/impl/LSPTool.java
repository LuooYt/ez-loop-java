package com.inspirationi.loop.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.inspirationi.loop.lsp.LSPDiagnosticRegistry;
import com.inspirationi.loop.lsp.LSPServerManager;
import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxViolationException;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LSP 工具 —— 通过 Language Server Protocol 提供代码智能。
 * <p>
 * 支持 goToDefinition / findReferences / hover / documentSymbols / diagnostics
 * 五种操作。文件路径相对沙箱根目录，读取前会把内容同步给语言服务器。
 * <p>
 * 未配置语言服务器时，会话上下文中没有 {@code LSP_SERVER_MANAGER}，此时
 * 工具返回明确的错误提示，而不是静默失败。
 */
public class LSPTool implements Tool {

    /** 上下文键：语言服务器管理器。 */
    public static final String MANAGER_KEY = "LSP_SERVER_MANAGER";
    /** 上下文键：诊断注册表。 */
    public static final String DIAGNOSTICS_KEY = "LSP_DIAGNOSTIC_REGISTRY";

    @Override
    public String name() {
        return "LSP";
    }

    @Override
    public String description() {
        return """
            Query a Language Server for code intelligence. Operations:
            - goToDefinition: where a symbol is defined
            - findReferences: all usages of a symbol
            - hover: type information and documentation
            - documentSymbols: symbols declared in a file
            - diagnostics: errors and warnings reported for a file

            The file path is relative to the session sandbox root. Requires a language \
            server configured for the file type.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "enum": ["goToDefinition", "findReferences", "hover", "documentSymbols", "diagnostics"],
                  "description": "The LSP operation to perform"
                },
                "file_path": {
                  "type": "string",
                  "description": "Path to the source file, relative to the sandbox root. Required for all operations except diagnostics, where it narrows the file reported in the message."
                },
                "line": {
                  "type": "integer",
                  "description": "1-based line number (required for goToDefinition, findReferences, hover)"
                },
                "character": {
                  "type": "integer",
                  "description": "0-based character offset on the line"
                }
              },
              "required": ["operation"]
            }""";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.READ_ONLY;
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
        String err = ToolValidator.requireString(input, "operation");
        if (err != null) return err;

        String operation = (String) input.get("operation");

        Object managerObj = context.get(MANAGER_KEY);
        if (!(managerObj instanceof LSPServerManager manager)) {
            return "Error: LSP server manager not available. No language server configured.";
        }

        // 先校验 operation，避免未知操作再去读文件
        String lspMethod = switch (operation) {
            case "goToDefinition" -> "textDocument/definition";
            case "findReferences" -> "textDocument/references";
            case "hover" -> "textDocument/hover";
            case "documentSymbols" -> "textDocument/documentSymbol";
            case "diagnostics" -> null;
            default -> "";
        };
        // diagnostics 的 lspMethod 为 null（不走 LSP 请求），只有未知操作才是空串
        if (lspMethod != null && lspMethod.isEmpty()) {
            return "Error: Unknown operation '" + operation
                    + "'. Use: goToDefinition, findReferences, hover, documentSymbols, diagnostics";
        }

        // diagnostics 查询的是整个注册表，不需要文件；其余操作必须有文件
        boolean diagnostics = lspMethod == null;
        if (!diagnostics) {
            err = ToolValidator.requireString(input, "file_path");
            if (err != null) return err;
        }

        Path path = null;
        String filePath = ToolValidator.getString(input, "file_path", null);
        if (filePath != null) {
            try {
                path = sandbox.resolve(filePath);
            } catch (SandboxViolationException e) {
                return "Error: " + e.getMessage();
            }
            if (!Files.isRegularFile(path)) {
                return "Error: File not found: " + sandbox.display(path);
            }
        }

        if (diagnostics) {
            return getDiagnostics(context, path);
        }

        try {
            if (!manager.isFileOpen(path.toString())) {
                manager.openFile(path.toString(), Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return "Error: " + Tool.describeError(e);
        }

        int line = ToolValidator.getInt(input, "line", 1);
        int character = ToolValidator.getInt(input, "character", 0);
        int lspLine = Math.max(0, line - 1);
        int lspCharacter = Math.max(0, character);

        return switch (operation) {
            case "goToDefinition" -> goToDefinition(manager, path, lspLine, lspCharacter);
            case "findReferences" -> findReferences(manager, path, lspLine, lspCharacter);
            case "hover" -> hover(manager, path, lspLine, lspCharacter);
            case "documentSymbols" -> documentSymbols(manager, path);
            default -> "Error: Unknown operation '" + operation + "'";
        };
    }

    private String goToDefinition(LSPServerManager manager, Path file, int line, int col) {
        try {
            JsonNode result = manager.sendRequest(file.toString(), "textDocument/definition",
                    positionParams(file, line, col));
            if (result == null || result.isNull()) return "No definition found";
            return formatLocations("Definition", result);
        } catch (Exception e) {
            return "Error: " + Tool.describeError(e);
        }
    }

    private String findReferences(LSPServerManager manager, Path file, int line, int col) {
        try {
            Map<String, Object> params = positionParams(file, line, col);
            params.put("context", Map.of("includeDeclaration", true));
            JsonNode result = manager.sendRequest(file.toString(), "textDocument/references", params);
            if (result == null || result.isNull()) return "No references found";
            return formatLocations("References", result);
        } catch (Exception e) {
            return "Error: " + Tool.describeError(e);
        }
    }

    private String hover(LSPServerManager manager, Path file, int line, int col) {
        try {
            JsonNode result = manager.sendRequest(file.toString(), "textDocument/hover",
                    positionParams(file, line, col));
            if (result == null || result.isNull()) return "No hover information available";
            return formatHover(result);
        } catch (Exception e) {
            return "Error: " + Tool.describeError(e);
        }
    }

    private String getDiagnostics(ToolContext context, Path file) {
        Object regObj = context.get(DIAGNOSTICS_KEY);
        if (regObj instanceof LSPDiagnosticRegistry registry) {
            var files = registry.checkAndExtract();
            if (files.isEmpty()) {
                return file == null
                        ? "No diagnostics"
                        : "No diagnostics for " + file.getFileName();
            }
            String formatted = LSPDiagnosticRegistry.formatForContext(files);
            if (formatted.isEmpty()) {
                return "No diagnostics";
            }
            // 提交必须发生在内容真正交出去之后：提前标记的话，一旦结果没能送达，
            // 这些诊断就再也不会出现
            registry.commitDelivered(files);
            return formatted;
        }
        return "Error: Diagnostic registry not available.";
    }

    private String documentSymbols(LSPServerManager manager, Path file) {
        try {
            Map<String, Object> params = Map.of(
                    "textDocument", Map.of("uri", file.toUri().toString()));
            JsonNode result = manager.sendRequest(file.toString(), "textDocument/documentSymbol", params);
            if (result == null || result.isNull()) return "No symbols found";
            return formatSymbols(result);
        } catch (Exception e) {
            return "Error: " + Tool.describeError(e);
        }
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> positionParams(Path file, int line, int col) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", Map.of("uri", file.toUri().toString()));
        params.put("position", Map.of("line", line, "character", col));
        return params;
    }

    private String formatLocations(String label, JsonNode result) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(":\n");

        if (result.isArray()) {
            if (result.isEmpty()) return "No " + label.toLowerCase() + " found";
            int count = 0;
            for (JsonNode loc : result) {
                String uri = loc.path("uri").asText("");
                sb.append("  ").append(uri.replaceFirst("^file:///", "").replaceFirst("^file://", ""));
                JsonNode range = loc.get("range");
                if (range != null) {
                    JsonNode start = range.get("start");
                    if (start != null) {
                        sb.append(":").append(start.path("line").asInt() + 1);
                        sb.append(":").append(start.path("character").asInt() + 1);
                    }
                }
                sb.append("\n");
                if (++count >= 20) {
                    sb.append("  ... and ").append(result.size() - 20).append(" more\n");
                    break;
                }
            }
        } else if (result.isObject()) {
            String uri = result.path("uri").asText("");
            sb.append("  ").append(uri.replaceFirst("^file:///", "")).append("\n");
        }
        return sb.toString();
    }

    private String formatHover(JsonNode result) {
        if (result.isObject()) {
            JsonNode contents = result.get("contents");
            if (contents == null) return "No information";
            if (contents.isTextual()) return contents.asText();
            if (contents.isObject()) {
                return contents.path("value").asText("No information");
            }
            if (contents.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : contents) {
                    if (item.isTextual()) sb.append(item.asText()).append("\n");
                    else if (item.isObject()) sb.append(item.path("value").asText()).append("\n");
                }
                return sb.toString().trim();
            }
        }
        return result.toString();
    }

    private String formatSymbols(JsonNode result) {
        if (!result.isArray()) return "No symbols";

        StringBuilder sb = new StringBuilder();
        sb.append("Symbols:\n");
        int count = 0;
        for (JsonNode sym : result) {
            String symName = sym.path("name").asText("?");
            sb.append(String.format("  %s %s%n", symbolKindName(sym.path("kind").asInt(0)), symName));

            JsonNode children = sym.get("children");
            if (children != null && children.isArray()) {
                for (JsonNode child : children) {
                    sb.append(String.format("    %s %s%n",
                            symbolKindName(child.path("kind").asInt(0)),
                            child.path("name").asText("?")));
                }
            }

            if (++count >= 50) {
                sb.append("  ... and more\n");
                break;
            }
        }
        return sb.toString();
    }

    private String symbolKindName(int kind) {
        return switch (kind) {
            case 1 -> "📄 File";
            case 2 -> "📦 Module";
            case 3 -> "🔲 Namespace";
            case 4 -> "📦 Package";
            case 5 -> "🏷 Class";
            case 6 -> "⚡ Method";
            case 7 -> "🔧 Property";
            case 8 -> "📌 Field";
            case 9 -> "🔨 Constructor";
            case 10 -> "📋 Enum";
            case 11 -> "🔗 Interface";
            case 12 -> "λ Function";
            case 13 -> "📎 Variable";
            case 14 -> "📐 Constant";
            case 15 -> "📝 String";
            case 16 -> "🔢 Number";
            case 17 -> "☑ Boolean";
            case 18 -> "📊 Array";
            case 19 -> "📋 Object";
            case 23 -> "📌 Struct";
            case 26 -> "📎 TypeParameter";
            default -> "  Symbol";
        };
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String operation = String.valueOf(input.getOrDefault("operation", "query"));
        String file = String.valueOf(input.getOrDefault("file_path", ""));
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String name = slash >= 0 ? file.substring(slash + 1) : file;
        return "🔍 LSP " + operation + " in " + name;
    }
}
