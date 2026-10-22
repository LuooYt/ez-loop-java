package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxViolationException;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件名匹配工具 —— 对应 claude-code 的 Glob 工具。
 * <p>
 * 用 {@link PathMatcher} 在沙箱内做 glob 匹配；<b>不跟随符号链接</b>，
 * 并按节点数与结果数设上限，避免大目录耗尽资源。
 */
public class GlobTool implements Tool {

    /** 单次最多返回条数 */
    private static final int MAX_RESULTS = 1000;
    /** 默认最多遍历节点数 */
    private static final int DEFAULT_MAX_NODES = 100_000;
    /** 遍历深度上限 */
    private static final int MAX_DEPTH = 40;

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "__pycache__", ".idea", ".vscode");

    private final int maxNodes;

    public GlobTool() {
        this(DEFAULT_MAX_NODES);
    }

    /** 包内构造器：供测试注入更小的节点上限。 */
    GlobTool(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return """
            Find files by glob pattern inside the session sandbox. Supports patterns like \
            "**/*.java" or "src/**/*.txt". Results are sorted by modification time (newest first) \
            and capped at 1000 entries. Symbolic links are not followed. \
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
                  "description": "Glob pattern, e.g. **/*.java"
                },
                "path": {
                  "type": "string",
                  "description": "Directory to search in, relative to the sandbox root (default: sandbox root)"
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
        String pattern = (String) input.get("pattern");
        String searchPath = ToolValidator.getString(input, "path", ".");

        Path base;
        try {
            base = sandbox.resolve(searchPath);
        } catch (SandboxViolationException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isDirectory(base, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return "Error: Directory not found: " + searchPath;
        }

        List<PathMatcher> matchers;
        try {
            matchers = buildMatchers(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            return "Error: Invalid glob pattern: " + pattern;
        }

        List<Path> matches = new ArrayList<>();
        AtomicInteger visited = new AtomicInteger();
        AtomicBoolean truncated = new AtomicBoolean();

        try {
            Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (visited.incrementAndGet() > maxNodes) {
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
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (visited.incrementAndGet() > maxNodes) {
                                truncated.set(true);
                                return FileVisitResult.TERMINATE;
                            }
                            if (attrs.isSymbolicLink()) {
                                return FileVisitResult.CONTINUE;
                            }
                            String relative = base.relativize(file).toString().replace('\\', '/');
                            Path relativePath = Path.of(relative);
                            if (matchers.stream().anyMatch(m -> m.matches(relativePath))) {
                                matches.add(file);
                                if (matches.size() >= MAX_RESULTS) {
                                    truncated.set(true);
                                    return FileVisitResult.TERMINATE;
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

        if (matches.isEmpty()) {
            return truncated.get()
                    ? "No files matched. (results truncated)"
                    : "No files matched.";
        }

        matches.sort(Comparator.comparingLong(GlobTool::lastModified).reversed());

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(matches.size()).append(" file(s)");
        if (truncated.get()) {
            sb.append(" (results truncated)");
        }
        sb.append(":\n");
        for (Path match : matches) {
            sb.append(sandbox.display(match)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 构建匹配器列表。
     * <p>
     * Java 的 glob 把 {@code **}{@code /} 当作「至少一级目录」，而调用方（以及
     * gitignore 语义）期待「零或多级」：{@code **}{@code /*.java} 反而漏掉根目录
     * 下的文件。为每个 {@code **}{@code /} 生成一个去掉它的变体，「零级」由此覆盖。
     *
     * @throws java.util.regex.PatternSyntaxException 原模式非法时（调用方转成参数错误）
     */
    private static List<PathMatcher> buildMatchers(String pattern) {
        // 先校验原模式：非法模式必须报错，不能因为变体可用就放行
        FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        java.util.Set<String> variants = new java.util.LinkedHashSet<>();
        collectVariants(pattern, variants);

        List<PathMatcher> matchers = new ArrayList<>(variants.size());
        for (String variant : variants) {
            if (variant.isBlank()) continue;
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + variant));
            } catch (java.util.regex.PatternSyntaxException ignored) {
                // 变体非法不影响原模式
            }
        }
        return matchers;
    }

    private static void collectVariants(String pattern, Set<String> out) {
        if (pattern.isEmpty() || !out.add(pattern)) return;
        for (int i = pattern.indexOf("**/"); i >= 0; i = pattern.indexOf("**/", i + 1)) {
            collectVariants(pattern.substring(0, i) + pattern.substring(i + 3), out);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔍 Glob " + input.getOrDefault("pattern", "*");
    }
}
