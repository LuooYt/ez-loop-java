package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxViolationException;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内容检索工具 —— 对应 claude-code 的 Grep 工具。
 * <p>
 * 纯 Java 实现（不依赖外部 {@code rg}/{@code grep}），跨平台且完全受沙箱约束：
 * 只遍历沙箱内、跳过符号链接与二进制文件、超过 {@code max-file-bytes} 的文件。
 */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 1000;
    private static final int DEFAULT_MAX_NODES = 100_000;
    private static final int MAX_DEPTH = 40;
    /** 默认单文件上限（与 SandboxProperties 默认值一致） */
    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "__pycache__", ".idea", ".vscode");

    private final long maxFileBytes;

    public GrepTool() {
        this(DEFAULT_MAX_BYTES);
    }

    /** 包内构造器：供测试注入更小的单文件上限。 */
    public GrepTool(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return """
            Search file contents for a regular expression inside the session sandbox. \
            Returns lines as "path:line:content". Binary files are skipped, as are files \
            larger than the configured limit. Symbolic links are not followed. \
            The search path must be relative to the sandbox root.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "pattern": {
                  "type": "string",
                  "description": "Java regular expression to search for"
                },
                "path": {
                  "type": "string",
                  "description": "Directory to search in, relative to the sandbox root (default: sandbox root)"
                },
                "glob": {
                  "type": "string",
                  "description": "Optional file name glob filter, e.g. *.java"
                },
                "case_insensitive": {
                  "type": "boolean",
                  "description": "Case-insensitive matching (default: false)"
                },
                "max_results": {
                  "type": "integer",
                  "description": "Maximum number of matching lines (default/max: 1000)"
                }
              },
              "required": ["pattern"]
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
        String err = ToolValidator.requireString(input, "pattern");
        if (err != null) return err;

        String patternText = (String) input.get("pattern");
        boolean caseInsensitive = Boolean.TRUE.equals(input.get("case_insensitive"));
        int maxResults = Math.min(
                Math.max(1, ToolValidator.getInt(input, "max_results", MAX_RESULTS)), MAX_RESULTS);

        Pattern regex;
        try {
            regex = Pattern.compile(patternText,
                    caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException e) {
            return "Error: Invalid regular expression: " + e.getDescription();
        }

        // 复用 GlobTool 的匹配器构造：它把 `**/` 展开成「零或多级」变体。
        // 直接用 PathMatcher 会要求至少一级目录，path 直属的文件会被漏掉，
        // 与 ripgrep（模型预期的语义）不一致。
        List<PathMatcher> globMatchers = null;
        String glob = ToolValidator.getString(input, "glob", null);
        if (glob != null) {
            String tooComplex = GlobTool.checkPatternCost(glob);
            if (tooComplex != null) {
                return tooComplex;
            }
            try {
                globMatchers = GlobTool.buildMatchers(glob);
            } catch (PatternSyntaxException e) {
                return "Error: Invalid glob filter: " + glob;
            }
        }

        Path base;
        try {
            base = sandbox.resolve(ToolValidator.getString(input, "path", "."));
        } catch (SandboxViolationException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            return "Error: Directory not found: " + sandbox.display(base);
        }

        List<String> results = new ArrayList<>();
        AtomicInteger visited = new AtomicInteger();
        AtomicBoolean truncated = new AtomicBoolean();
        List<PathMatcher> fileFilters = globMatchers;

        try {
            Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (visited.incrementAndGet() > DEFAULT_MAX_NODES) {
                                truncated.set(true);
                                return FileVisitResult.TERMINATE;
                            }
                            Path name = dir.getFileName();
                            if (!dir.equals(base) && name != null
                                    && IGNORED_DIRS.contains(name.toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            if (visited.incrementAndGet() > DEFAULT_MAX_NODES) {
                                truncated.set(true);
                                return FileVisitResult.TERMINATE;
                            }
                            if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (attrs.size() > maxFileBytes) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (fileFilters != null) {
                                String relative = base.relativize(file).toString().replace('\\', '/');
                                Path relativePath = Path.of(relative);
                                if (fileFilters.stream().noneMatch(m -> m.matches(relativePath))) {
                                    return FileVisitResult.CONTINUE;
                                }
                            }

                            byte[] bytes = Files.readAllBytes(file);
                            if (containsNul(bytes)) {
                                return FileVisitResult.CONTINUE; // 二进制文件
                            }
                            String text = new String(bytes, StandardCharsets.UTF_8);
                            String[] lines = text.split("\n", -1);
                            String display = sandbox.display(file);
                            for (int i = 0; i < lines.length; i++) {
                                if (results.size() >= maxResults) {
                                    truncated.set(true);
                                    return FileVisitResult.TERMINATE;
                                }
                                String line = lines[i].endsWith("\r")
                                        ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
                                if (regex.matcher(line).find()) {
                                    results.add(display + ":" + (i + 1) + ":" + line);
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            return "Error searching files: " + Tool.describeError(e);
        }

        if (results.isEmpty()) {
            return "No matches found.";
        }

        StringBuilder sb = new StringBuilder();
        for (String line : results) {
            sb.append(line).append('\n');
        }
        if (truncated.get()) {
            sb.append("... (results truncated at ").append(results.size()).append(')');
        }
        return sb.toString().stripTrailing();
    }

    /** 前 8KB 内出现 NUL 字节即视为二进制。 */
    private static boolean containsNul(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔍 Grep " + input.getOrDefault("pattern", "");
    }
}
