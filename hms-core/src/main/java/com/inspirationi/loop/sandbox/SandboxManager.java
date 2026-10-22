package com.inspirationi.loop.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 会话沙箱目录的创建、查询与销毁。 */
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final SandboxProperties props;
    private final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<>();
    /** 根目录已就绪。构造时创建失败则视为沙箱未生效。 */
    private final boolean ready;

    public SandboxManager(SandboxProperties props) {
        this.props = props;
        boolean ok = false;
        if (props.isUsable()) {
            try {
                Files.createDirectories(props.getRoot());
                ok = Files.isDirectory(props.getRoot());
                log.info("Sandbox enabled at {}", props.getRoot().toAbsolutePath());
            } catch (IOException e) {
                log.error("Cannot create sandbox root {}: {}", props.getRoot(), e.getMessage());
            }
        }
        if (!ok) {
            log.warn("Sandbox disabled: file tools will not be registered "
                    + "(set hms-core.sandbox.enabled=true and hms-core.sandbox.root)");
        }
        this.ready = ok;
    }

    public boolean enabled() {
        return ready;
    }

    public Sandbox create(String sessionId) {
        if (!SAFE_ID.matcher(sessionId == null ? "" : sessionId).matches()) {
            throw new SandboxViolationException("Invalid session id for sandbox: " + sessionId);
        }
        Path dir = props.getRoot().resolve(sessionId);
        try {
            Files.createDirectories(dir);
            setOwnerOnlyPermissions(dir);
            Sandbox sandbox = new Sandbox(dir);
            sandboxes.put(sessionId, sandbox);
            return sandbox;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create sandbox for session " + sessionId, e);
        }
    }

    public Optional<Sandbox> get(String sessionId) {
        return Optional.ofNullable(sandboxes.get(sessionId));
    }

    /** 会话销毁：按配置决定是否删除目录。 */
    public void destroy(String sessionId) {
        destroyInternal(sessionId, props.isCleanupOnDestroy());
    }

    /**
     * 无条件删除会话目录 —— 用于创建失败回滚。
     * <p>
     * 回滚时目录里只有本次新建的空内容，保留它只会留下无人认领的垃圾，
     * 因此不受 {@code cleanup-on-destroy} 影响。
     */
    public void destroyImmediately(String sessionId) {
        destroyInternal(sessionId, true);
    }

    private void destroyInternal(String sessionId, boolean deleteDirectory) {
        Sandbox sandbox = sandboxes.remove(sessionId);
        if (sandbox == null) return;
        sandbox.close();
        if (!deleteDirectory) return;

        Path target = sandbox.root();
        // 删除前双重校验：必须是根目录的直接子目录，且名字与 sessionId 一致。
        // 根目录取 toRealPath：target 也来自 toRealPath，Windows 上大小写/短名才可比。
        Path rootReal;
        try {
            rootReal = props.getRoot().toRealPath();
        } catch (IOException e) {
            rootReal = props.getRoot().toAbsolutePath().normalize();
        }
        if (!rootReal.equals(target.getParent())
                || !target.getFileName().toString().equals(sessionId)) {
            log.error("Refusing to delete unexpected sandbox path: {}", target);
            return;
        }
        try {
            deleteRecursively(target);
            log.info("Deleted sandbox for session {}", sessionId);
        } catch (IOException e) {
            log.warn("Failed to delete sandbox {}: {}", target, e.getMessage());
        }
    }

    private static void setOwnerOnlyPermissions(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 不支持 POSIX 权限，忽略。
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
