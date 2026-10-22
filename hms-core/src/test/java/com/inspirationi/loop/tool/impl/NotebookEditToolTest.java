package com.inspirationi.loop.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NotebookEditToolTest {

    @TempDir Path temp;

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    private static void addCell(ArrayNode cells, String source) {
        ObjectNode cell = cells.addObject();
        cell.put("cell_type", "code");
        cell.put("source", source);
        cell.putObject("metadata");
        cell.putArray("outputs");
        cell.put("execution_count", 1);
    }

    private Path writeNotebook(Sandbox sandbox, String name, String... sources) throws IOException {
        ObjectNode nb = mapper.createObjectNode();
        nb.put("nbformat", 4);
        nb.put("nbformat_minor", 5);
        nb.putObject("metadata");
        ArrayNode cells = nb.putArray("cells");
        for (String source : sources) {
            addCell(cells, source);
        }
        Path file = sandbox.root().resolve(name);
        mapper.writeValue(file.toFile(), nb);
        return file;
    }

    private JsonNode read(Path file) throws IOException {
        return mapper.readTree(file.toFile());
    }

    @Test
    void insertsCellAtEnd() throws IOException {
        ToolContext ctx = context();
        Path file = writeNotebook(Sandbox.from(ctx), "a.ipynb", "print(1)");

        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "a.ipynb",
                "cell_number", 1,
                "new_source", "print(2)",
                "edit_mode", "insert"), ctx);

        assertFalse(result.startsWith("Error:"), result);
        JsonNode cells = read(file).get("cells");
        assertEquals(2, cells.size());
        assertTrue(cells.get(1).get("source").toString().contains("print(2)"));
    }

    @Test
    void replaceKeepsOtherCellsIntact() throws IOException {
        ToolContext ctx = context();
        Path file = writeNotebook(Sandbox.from(ctx), "a.ipynb", "print(1)", "print(2)");

        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "a.ipynb",
                "cell_number", 0,
                "new_source", "print(9)",
                "edit_mode", "replace"), ctx);

        assertFalse(result.startsWith("Error:"), result);
        JsonNode cells = read(file).get("cells");
        assertEquals(2, cells.size());
        assertTrue(cells.get(0).get("source").toString().contains("print(9)"));
        assertTrue(cells.get(1).get("source").toString().contains("print(2)"));
    }

    @Test
    void deletesCell() throws IOException {
        ToolContext ctx = context();
        Path file = writeNotebook(Sandbox.from(ctx), "a.ipynb", "print(1)", "print(2)");

        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "a.ipynb",
                "cell_number", 0,
                "edit_mode", "delete"), ctx);

        assertFalse(result.startsWith("Error:"), result);
        JsonNode cells = read(file).get("cells");
        assertEquals(1, cells.size());
        assertTrue(cells.get(0).get("source").toString().contains("print(2)"));
    }

    @Test
    void rejectsOutOfRangeCellNumber() throws IOException {
        ToolContext ctx = context();
        writeNotebook(Sandbox.from(ctx), "a.ipynb", "print(1)");

        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "a.ipynb",
                "cell_number", 5,
                "new_source", "print(2)",
                "edit_mode", "replace"), ctx);

        assertTrue(result.startsWith("Error:"));
        assertTrue(result.toLowerCase().contains("range") || result.contains("越界"));
    }

    @Test
    void rejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "../a.ipynb",
                "cell_number", 0,
                "new_source", "x"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("sandbox"));
    }

    @Test
    void rejectsNonNotebookExtension() throws IOException {
        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "a.txt", "cell_number", 0, "new_source", "x"), context());
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains(".ipynb"));
    }

    @Test
    void rejectsInvalidJson() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("broken.ipynb"), "not json");

        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "broken.ipynb", "cell_number", 0, "new_source", "x"), ctx);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void refusesNotebookLargerThanLimit() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("big.ipynb");
        Files.writeString(file, "{\"cells\":[" + "\"pad\"," .repeat(200) + "\"x\"]}");

        String result = new NotebookEditTool(64).execute(Map.of(
                "notebook_path", "big.ipynb", "cell_number", 0, "new_source", "x"), ctx);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("too large"), result);
    }

    @Test
    void rejectsNonArrayCellsWithoutThrowing() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("bad.ipynb");
        Files.writeString(file, "{\"cells\":{}}");

        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "bad.ipynb", "cell_number", 0, "new_source", "x"), ctx);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("cells"), result);
    }

    @Test
    void rejectsNonObjectCellsWithoutThrowing() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("bad2.ipynb");
        Files.writeString(file, "{\"cells\":[\"text\"]}");

        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "bad2.ipynb", "cell_number", 0, "new_source", "x"), ctx);

        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void requiresNewSourceForReplace() throws IOException {
        ToolContext ctx = context();
        writeNotebook(Sandbox.from(ctx), "a.ipynb", "print(1)");

        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "a.ipynb", "cell_number", 0, "edit_mode", "replace"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("new_source"));
    }
}
