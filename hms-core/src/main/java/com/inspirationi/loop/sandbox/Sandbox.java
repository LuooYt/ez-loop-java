package com.inspirationi.loop.sandbox;

import com.inspirationi.loop.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话沙箱 —— 所有文件路径的唯一入口。
 * <p>
 * 校验顺序：拒绝绝对路径 → normalize → 词法前缀 → 最近存在祖先的 realPath 复核。
 * 词法前缀单独不够：符号链接可以让一个「看起来在根内」的路径实际指向根外。
 */
public final class Sandbox {

    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);

    /** ToolContext 共享状态键。子上下文读取时回落到父级。 */
    public static final String STATE_KEY = "SANDBOX";

    private final Path root;
    private volatile Path currentDir;
    private final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();

    public Sandbox(Path root) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Sandbox root is not accessible: " + root, e);
        }
        this.currentDir = this.root;
    }

    public Path root() { return root; }
    public Path currentDir() { return currentDir; }

    /** 把沙箱内绝对路径显示为相对路径，避免把宿主目录结构泄露给模型。 */
    public String display(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString().replace('\\', '/')
                : path.toString();
    }

    /**
     * 安全解析相对路径。
     *
     * @throws SandboxViolationException 拒绝绝对路径、越界路径或符号链接逃逸
     */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new SandboxViolationException("Path is required.");
        }
        Path requested;
        try {
            requested = Path.of(relativePath);
        } catch (java.nio.file.InvalidPathException e) {
            throw new SandboxViolationException("Invalid path: " + relativePath);
        }
        // Windows 上 "/etc/passwd" 的 isAbsolute() 为 false，但 getRoot() 非空。
        if (requested.isAbsolute() || requested.getRoot() != null) {
            throw new SandboxViolationException(
                    "Absolute paths are not allowed. Use a path relative to the sandbox.");
        }

        Path candidate = currentDir.resolve(requested).normalize();
        if (!candidate.startsWith(root)) {
            throw new SandboxViolationException(
                    "Path escapes the session sandbox. Use a relative path inside the sandbox.");
        }
        // 悬空符号链接：Files.exists() 返回 false，但写入会跟随链接在外部创建文件。
        if (Files.isSymbolicLink(candidate)) {
            throw new SandboxViolationException(
                    "Symbolic links are not allowed inside the sandbox.");
        }

        Path real = realPathOfNearestExisting(candidate);
        if (!real.startsWith(root)) {
            throw new SandboxViolationException(
                    "Path resolves outside the session sandbox through a symbolic link.");
        }
        return candidate;
    }

    /** 对不存在的目标，取最近存在祖先的 realPath，再拼回剩余段。 */
    private Path realPathOfNearestExisting(Path candidate) {
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new SandboxViolationException("Cannot resolve path: " + candidate);
        }
        try {
            Path real = existing.toRealPath();
            Path remaining = existing.relativize(candidate);
            return remaining.toString().isEmpty() ? real : real.resolve(remaining).normalize();
        } catch (IOException e) {
            throw new SandboxViolationException("Cannot resolve path: " + candidate);
        }
    }

    /** 切换逻辑当前目录（EnterWorktree 使用）。目录必须已通过校验且存在。 */
    public void enter(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || !Files.isDirectory(normalized)) {
            throw new SandboxViolationException("Cannot enter directory outside the sandbox.");
        }
        this.currentDir = normalized;
    }

    /** 回到沙箱根。 */
    public void exit() {
        this.currentDir = root;
    }

    /** 登记会话级资源（如 LSP 管理器），会话销毁时统一关闭。 */
    public void addResource(AutoCloseable resource) {
        if (resource != null) {
            resources.add(resource);
        }
    }

    /** 关闭登记的资源；单个资源失败不影响其他资源。 */
    public void close() {
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Failed to close sandbox resource: {}", e.getClass().getSimpleName());
            }
        }
        resources.clear();
    }

    /** 从工具上下文读取沙箱；不存在返回 null。 */
    public static Sandbox from(ToolContext context) {
        return context != null ? context.get(STATE_KEY) : null;
    }
}
