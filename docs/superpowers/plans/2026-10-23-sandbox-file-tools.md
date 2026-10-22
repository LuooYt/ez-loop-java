# 会话沙箱与文件类工具实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 hms-core 中实现会话级文件沙箱，并补齐 12 个内置工具（文件、执行、Notebook、LSP、Worktree、通知）。

**Architecture:** 沙箱是操作系统文件系统上的真实目录，根路径由 `hms-core.sandbox.root` 配置，每个会话独占 `<root>/<sessionId>/`。所有文件类工具通过 `Sandbox.resolve()` 做「拒绝绝对路径 → normalize → 词法前缀 → realPath 符号链接复核」四步校验。Bash 以沙箱为 cwd 但不构成硬边界。工具按沙箱是否启用在 `ToolConfiguration` 条件注册。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Spring AI 2.0.1、Jackson、JUnit 5、Maven。

**Spec:** `docs/superpowers/specs/2026-10-23-sandbox-file-tools-design.md`

## Global Constraints

- 面向人的文字（Javadoc、注释、README、配置说明）使用简体中文；技术标识符保持英文。
- **工具返回给模型/调用方的错误文本保持英文**，与 spec §6 的固定串一致（如
  `Error: Path escapes the session sandbox...`）—— 这些是协议文本，不是界面文案。
- 包名统一 `com.inspirationi.loop.<module>`；不要引入新依赖。
- 每个工具返回面向模型的可读错误文本，不向外抛未捕获异常。
- 文件类工具只接受相对路径；`Sandbox.resolve()` 是唯一路径入口。
- 沙箱未启用时（`enabled=false` 或 `root` 为空）不注册 11 个路径类工具。
- 所有新代码按 TDD：先写失败测试，再实现，再跑测试，再提交。
- 测试使用 JUnit 5 `@TempDir`；需要符号链接权限时用 `Assumptions.assumeTrue` 跳过。
- 提交信息使用中文；每个任务至少一次独立提交。

## Review Focus

以下五类是 spec 隐含但最容易出错的输入/失败模式，对应测试必须在指定任务中落地：

1. **Windows 绝对路径判定**：`Path.of("/etc/passwd").isAbsolute()` 在 Windows 上为 `false`，但 `getRoot()` 非空。必须同时检查两者，否则 `/etc/passwd` 会被当作相对路径拼接。测试在 Task 1。
2. **符号链接父目录写入逃逸**：目标文件不存在、但其父目录是指向外部的符号链接。必须对「最近存在祖先」取 `toRealPath()`。测试在 Task 1。
3. **悬空符号链接**：`Files.exists()` 对悬空链接返回 `false`，写入会跟随链接在外部创建文件。必须显式拒绝 `Files.isSymbolicLink(candidate)`。测试在 Task 1。
4. **会话销毁误删**：`cleanup-on-destroy=true` 时，目标路径必须是根目录下一级且名字等于 sessionId，不能因拼接错误删除整个根。测试在 Task 2。
5. **子进程残留**：Bash 超时后必须杀死进程树，否则会话销毁后仍有孤儿进程。测试在 Task 8。

---

## 文件结构

```text
hms-core/src/main/java/com/inspirationi/loop/
├── sandbox/
│   ├── Sandbox.java                  路径解析、逻辑当前目录、会话资源
│   ├── SandboxManager.java           创建/查询/销毁会话沙箱
│   ├── SandboxProperties.java        hms-core.sandbox.* 配置
│   └── SandboxViolationException.java
├── tool/
│   ├── NotificationSink.java
│   ├── util/ProcessExecutor.java
│   └── impl/
│       ├── FileReadTool.java
│       ├── FileWriteTool.java
│       ├── FileEditTool.java
│       ├── GlobTool.java
│       ├── GrepTool.java
│       ├── ListFilesTool.java
│       ├── BashTool.java
│       ├── NotebookEditTool.java
│       ├── LSPTool.java
│       ├── EnterWorktreeTool.java
│       ├── ExitWorktreeTool.java
│       └── NotificationTool.java
├── lsp/ (5 个类，移植)
└── worktree/WorktreeManager.java (移植)

hms-core/src/test/java/com/inspirationi/loop/
├── sandbox/SandboxPathTest.java
├── sandbox/SandboxManagerTest.java
├── tool/impl/*Test.java              各工具测试（含 LSPToolTest、BashToolTest…）
├── lsp/LSPServerConfigTest.java
├── worktree/WorktreeManagerTest.java
└── api/SessionSandboxLifecycleTest.java
    config/SandboxToolRegistrationTest.java

修改：
├── config/AppConfig.java             注册 SandboxManager、启用配置绑定
├── config/ToolConfiguration.java     条件注册 + Bash 命令提取器
├── api/AgentRuntimeConfig.java       增加 SandboxManager 字段
├── api/AgentRuntimeFactory.java      传给会话管理器
├── api/DefaultHmsSessionManager.java 会话创建/销毁接线
├── demo-app/src/main/resources/application.yml
├── demo-app/src/main/resources/application-prod.yml
└── README.md
```

参考实现目录（只读）：

`C:\Users\Administrator\Downloads\claude-code-main\claude-code-main\src\main\java\com\claudecode`

---

### Task 1: 沙箱路径解析核心

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/sandbox/SandboxViolationException.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/sandbox/SandboxProperties.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/sandbox/Sandbox.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/sandbox/SandboxPathTest.java`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `SandboxProperties`：`isEnabled()`、`getRoot()`、`getMaxFileBytes()`、`isCleanupOnDestroy()`、`getBashTimeoutSeconds()`、`getBashMaxOutputChars()`、`getLsp()`、`getNotification()`。
  - `Sandbox(Path root)`；`Path resolve(String relativePath)`；`Path root()`；`Path currentDir()`；`void enter(Path dir)`；`void exit()`；`void addResource(AutoCloseable resource)`；`void close()`；`static Sandbox from(ToolContext ctx)`；常量 `STATE_KEY = "SANDBOX"`。
  - `SandboxViolationException(String message)`。

- [ ] **Step 1: 写失败的路径测试**

```java
package com.inspirationi.loop.sandbox;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SandboxPathTest {

    @TempDir Path temp;

    private Sandbox newSandbox() throws IOException {
        Path root = Files.createDirectories(temp.resolve("root"));
        return new Sandbox(root);
    }

    @Test
    void resolvesRelativePathInsideSandbox() throws IOException {
        Sandbox sandbox = newSandbox();
        Path resolved = sandbox.resolve("notes/a.md");
        assertEquals(sandbox.root().resolve("notes/a.md"), resolved);
    }

    @Test
    void rejectsParentTraversal() throws IOException {
        Sandbox sandbox = newSandbox();
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("../escape.txt"));
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("a/../../escape.txt"));
    }

    @Test
    void rejectsAbsoluteUnixPath() throws IOException {
        Sandbox sandbox = newSandbox();
        // 在 Windows 上 "/etc/passwd" 的 isAbsolute() 为 false，但 getRoot() 非空
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("/etc/passwd"));
    }

    @Test
    void rejectsWindowsDriveAndUncPaths() throws IOException {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        Sandbox sandbox = newSandbox();
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("C:\\Windows\\win.ini"));
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("\\\\server\\share\\x"));
    }

    @Test
    void rejectsSiblingDirectoryWithCommonPrefix() throws IOException {
        Sandbox sandbox = newSandbox();
        Files.createDirectories(temp.resolve("root-10"));
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("../root-10/x"));
    }

    @Test
    void rejectsSymlinkDirectoryEscape() throws IOException {
        Sandbox sandbox = newSandbox();
        Path outside = Files.createDirectories(temp.resolve("outside"));
        try {
            Files.createSymbolicLink(sandbox.root().resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symlink not supported: " + e.getMessage());
        }
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("link/x.txt"));
    }

    @Test
    void rejectsDanglingSymlink() throws IOException {
        Sandbox sandbox = newSandbox();
        Path target = temp.resolve("outside-not-created.txt");
        try {
            Files.createSymbolicLink(sandbox.root().resolve("dangling"), target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symlink not supported: " + e.getMessage());
        }
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("dangling"));
    }

    @Test
    void switchesCurrentDirectoryForWorktree() throws IOException {
        Sandbox sandbox = newSandbox();
        Path worktree = Files.createDirectories(sandbox.root().resolve("worktrees/w1"));
        sandbox.enter(worktree);
        assertEquals(worktree.toRealPath(), sandbox.resolve("a.txt").getParent().toRealPath());
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("../../escape.txt"));
        sandbox.exit();
        assertTrue(sandbox.resolve("a.txt").startsWith(sandbox.root()));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=SandboxPathTest`
Expected: 编译失败，找不到 `Sandbox` / `SandboxViolationException`。

- [ ] **Step 3: 实现异常与配置类**

`SandboxViolationException.java`：

```java
package com.inspirationi.loop.sandbox;

/** 沙箱路径校验失败。消息会直接回传给模型。 */
public class SandboxViolationException extends RuntimeException {
    public SandboxViolationException(String message) {
        super(message);
    }
}
```

`SandboxProperties.java`（JavaBean，便于 Spring Boot 宽松绑定）：

```java
package com.inspirationi.loop.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "hms-core.sandbox")
public class SandboxProperties {

    private boolean enabled = false;
    private Path root;
    private boolean cleanupOnDestroy = false;
    private long maxFileBytes = 10L * 1024 * 1024;
    private Bash bash = new Bash();
    private Lsp lsp = new Lsp();
    private Notification notification = new Notification();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getRoot() { return root; }
    public void setRoot(Path root) { this.root = root; }
    public boolean isCleanupOnDestroy() { return cleanupOnDestroy; }
    public void setCleanupOnDestroy(boolean cleanupOnDestroy) { this.cleanupOnDestroy = cleanupOnDestroy; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public Bash getBash() { return bash; }
    public void setBash(Bash bash) { this.bash = bash; }
    public Lsp getLsp() { return lsp; }
    public void setLsp(Lsp lsp) { this.lsp = lsp; }
    public Notification getNotification() { return notification; }
    public void setNotification(Notification notification) { this.notification = notification; }

    /** 是否真正可用：开关打开且根路径已配置。 */
    public boolean isUsable() {
        return enabled && root != null && !root.toString().isBlank();
    }

    public static class Bash {
        private int timeoutSeconds = 120;
        private int maxOutputChars = 40_000;
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public int getMaxOutputChars() { return maxOutputChars; }
        public void setMaxOutputChars(int v) { this.maxOutputChars = v; }
    }

    public static class Lsp {
        private Map<String, Server> servers = new LinkedHashMap<>();
        public Map<String, Server> getServers() { return servers; }
        public void setServers(Map<String, Server> servers) {
            this.servers = servers != null ? servers : new LinkedHashMap<>();
        }
    }

    public static class Server {
        private String command;
        private java.util.List<String> args = new java.util.ArrayList<>();
        private Map<String, String> extensions = new LinkedHashMap<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private long startupTimeout = 30_000;
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public java.util.List<String> getArgs() { return args; }
        public void setArgs(java.util.List<String> args) { this.args = args != null ? args : new java.util.ArrayList<>(); }
        public Map<String, String> getExtensions() { return extensions; }
        public void setExtensions(Map<String, String> extensions) {
            this.extensions = extensions != null ? extensions : new LinkedHashMap<>();
        }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env != null ? env : new LinkedHashMap<>(); }
        public long getStartupTimeout() { return startupTimeout; }
        public void setStartupTimeout(long startupTimeout) { this.startupTimeout = startupTimeout; }
    }

    public static class Notification {
        private boolean systemCommand = true;
        public boolean isSystemCommand() { return systemCommand; }
        public void setSystemCommand(boolean systemCommand) { this.systemCommand = systemCommand; }
    }
}
```

- [ ] **Step 4: 实现 Sandbox**

```java
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
                log.warn("Failed to close sandbox resource: {}", e.getSimpleName());
            }
        }
        resources.clear();
    }

    /** 从工具上下文读取沙箱；不存在返回 null。 */
    public static Sandbox from(ToolContext context) {
        return context != null ? context.get(STATE_KEY) : null;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=SandboxPathTest`
Expected: PASS（符号链接用例在不支持的环境显示 skipped）。

- [ ] **Step 6: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/sandbox hms-core/src/test/java/com/inspirationi/loop/sandbox/SandboxPathTest.java
git commit -m "feat: 沙箱路径解析与配置模型"
```

---

### Task 2: SandboxManager 与会话目录生命周期

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/sandbox/SandboxManager.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/sandbox/SandboxManagerTest.java`

**Interfaces:**
- Consumes: `SandboxProperties`、`Sandbox`。
- Produces:
  - `SandboxManager(SandboxProperties props)`
  - `boolean enabled()`
  - `Sandbox create(String sessionId)`
  - `Optional<Sandbox> get(String sessionId)`
  - `void destroy(String sessionId)` —— 按 `cleanup-on-destroy` 决定是否删目录
  - `void destroyImmediately(String sessionId)` —— 无条件删除，用于创建失败回滚

- [ ] **Step 1: 写失败测试**

```java
package com.inspirationi.loop.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SandboxManagerTest {

    @TempDir Path temp;

    private SandboxProperties props(boolean cleanup) throws IOException {
        SandboxProperties p = new SandboxProperties();
        p.setEnabled(true);
        p.setRoot(Files.createDirectories(temp.resolve("sandbox-root")));
        p.setCleanupOnDestroy(cleanup);
        return p;
    }

    @Test
    void createsSessionDirectory() throws IOException {
        SandboxManager manager = new SandboxManager(props(false));
        Sandbox sandbox = manager.create("session-1");
        assertTrue(Files.isDirectory(temp.resolve("sandbox-root/session-1")));
        assertEquals("session-1", sandbox.root().getFileName().toString());
    }

    @Test
    void keepsDirectoryWhenCleanupDisabled() throws IOException {
        SandboxManager manager = new SandboxManager(props(false));
        manager.create("session-1");
        manager.destroy("session-1");
        assertTrue(Files.isDirectory(temp.resolve("sandbox-root/session-1")));
        assertTrue(manager.get("session-1").isEmpty());
    }

    @Test
    void removesDirectoryWhenCleanupEnabled() throws IOException {
        SandboxManager manager = new SandboxManager(props(true));
        manager.create("session-1");
        Files.writeString(temp.resolve("sandbox-root/session-1/a.txt"), "x");
        manager.destroy("session-1");
        assertFalse(Files.exists(temp.resolve("sandbox-root/session-1")));
        // 根目录本身必须保留
        assertTrue(Files.isDirectory(temp.resolve("sandbox-root")));
    }

    @Test
    void destroyImmediatelyDeletesEvenWhenCleanupDisabled() throws IOException {
        SandboxManager manager = new SandboxManager(props(false));
        manager.create("session-rollback");
        manager.destroyImmediately("session-rollback");
        assertFalse(Files.exists(temp.resolve("sandbox-root/session-rollback")),
                "创建失败回滚必须删除空目录，不受 cleanup-on-destroy 影响");
        assertTrue(Files.isDirectory(temp.resolve("sandbox-root")));
    }

    @Test
    void rejectsUnsafeSessionId() throws IOException {
        SandboxManager manager = new SandboxManager(props(false));
        assertThrows(SandboxViolationException.class, () -> manager.create("../escape"));
        assertThrows(SandboxViolationException.class, () -> manager.create("a/b"));
        assertThrows(SandboxViolationException.class, () -> manager.create(""));
    }

    @Test
    void reportsDisabledWhenRootMissing() {
        SandboxProperties p = new SandboxProperties();
        p.setEnabled(true);
        assertFalse(new SandboxManager(p).enabled());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=SandboxManagerTest`
Expected: 编译失败，找不到 `SandboxManager`。

- [ ] **Step 3: 实现 SandboxManager**

```java
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

    public SandboxManager(SandboxProperties props) {
        this.props = props;
        if (enabled()) {
            try {
                Files.createDirectories(props.getRoot());
                log.info("Sandbox enabled at {}", props.getRoot().toAbsolutePath());
            } catch (IOException e) {
                log.error("Cannot create sandbox root {}: {}", props.getRoot(), e.getMessage());
            }
        } else {
            log.warn("Sandbox disabled: file tools will not be registered "
                    + "(set hms-core.sandbox.enabled=true and hms-core.sandbox.root)");
        }
    }

    public boolean enabled() {
        if (!props.isUsable()) return false;
        try {
            return Files.isDirectory(props.getRoot());
        } catch (Exception e) {
            return false;
        }
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=SandboxManagerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/sandbox/SandboxManager.java hms-core/src/test/java/com/inspirationi/loop/sandbox/SandboxManagerTest.java
git commit -m "feat: 会话沙箱目录管理"
```

---

### Task 3: 会话生命周期接线

**Files:**
- Modify: `hms-core/src/main/java/com/inspirationi/loop/api/DefaultHmsSessionManager.java`
- Modify: `hms-core/src/main/java/com/inspirationi/loop/api/AgentRuntimeConfig.java`
- Modify: `hms-core/src/main/java/com/inspirationi/loop/api/AgentRuntimeFactory.java`
- Modify: `hms-core/src/main/java/com/inspirationi/loop/config/AppConfig.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/api/SessionSandboxLifecycleTest.java`

**Interfaces:**
- Consumes: `SandboxManager`、`Sandbox`。
- Produces:
  - `DefaultHmsSessionManager.Builder.sandboxManager(SandboxManager)`
  - 会话 `ToolContext` 中 `Sandbox.STATE_KEY` 可读。
  - `AgentRuntimeConfig` 增加 `SandboxManager sandboxManager` 分量。
  - Task 10 会再追加 `Builder.sandboxProperties(SandboxProperties)` 与
    `AgentRuntimeConfig.sandboxProperties` 分量，本任务先不加。

- [ ] **Step 1: 写失败测试**

`DefaultHmsSessionManager` 已有包内可见的 `getSessionInternal(String)`，测试通过
`getSessionInternal(id).getAgentLoop().getToolContext()` 读会话上下文，无需新增访问器。

```java
package com.inspirationi.loop.api;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxManager;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionSandboxLifecycleTest {

    @TempDir Path temp;

    private static ChatModel stubChatModel() {
        ChatOptions options = ChatOptions.builder().model("stub-model").build();
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("stub"), ChatGenerationMetadata.NULL)));
            }

            @Override
            public ChatOptions getOptions() {
                return options;
            }
        };
    }

    private SandboxManager sandboxManager(boolean cleanup) throws IOException {
        SandboxProperties props = new SandboxProperties();
        props.setEnabled(true);
        props.setRoot(Files.createDirectories(temp.resolve("sandbox-root")));
        props.setCleanupOnDestroy(cleanup);
        return new SandboxManager(props);
    }

    private DefaultHmsSessionManager newManager(SandboxManager sandboxManager, int maxSessions) {
        return DefaultHmsSessionManager.builder(
                        stubChatModel(), new ToolRegistry(), new DefaultPromptManager(null, "global"))
                .idleTimeoutSeconds(3600)
                .cleanupIntervalSeconds(3600)
                .maxSessions(maxSessions)
                .sandboxManager(sandboxManager)
                .build();
    }

    @Test
    void sessionCreatesSandboxAndExposesItToContext() throws IOException {
        SandboxManager sandboxes = sandboxManager(false);
        try (DefaultHmsSessionManager manager = newManager(sandboxes, 10)) {
            String sessionId = manager.createSession("hello");

            assertTrue(Files.isDirectory(temp.resolve("sandbox-root").resolve(sessionId)),
                    "会话创建时必须建出 root/<sessionId>");

            ToolContext ctx = manager.getSessionInternal(sessionId).getAgentLoop().getToolContext();
            Object sandbox = ctx.get(Sandbox.STATE_KEY);
            assertInstanceOf(Sandbox.class, sandbox, "会话上下文必须能读到沙箱");
            assertEquals(sessionId, ((Sandbox) sandbox).root().getFileName().toString());
        }
    }

    @Test
    void destroyedSessionRemovesRegistryEntryButKeepsDirectory() throws IOException {
        SandboxManager sandboxes = sandboxManager(false);
        try (DefaultHmsSessionManager manager = newManager(sandboxes, 10)) {
            String sessionId = manager.createSession("hello");
            manager.destroySession(sessionId);

            assertTrue(sandboxes.get(sessionId).isEmpty(), "销毁后管理器不应再持有沙箱");
            assertTrue(Files.isDirectory(temp.resolve("sandbox-root").resolve(sessionId)),
                    "cleanup=false 时必须保留会话目录");
        }
    }

    @Test
    void rejectedSessionRollbackDeletesSandboxDirectory() throws IOException {
        SandboxManager sandboxes = sandboxManager(false);
        try (DefaultHmsSessionManager manager = newManager(sandboxes, 1)) {
            String first = manager.createSession("a");
            assertThrows(HmsException.class, () -> manager.createSession("b"));

            // 容量超限时被拒会话的目录必须删掉，且不能误伤已存在的会话目录
            assertEquals(1, sandboxesCount(temp.resolve("sandbox-root")),
                    "回滚后只应剩下已成功会话的目录");
            assertTrue(Files.isDirectory(temp.resolve("sandbox-root").resolve(first)));
        }
    }

    @Test
    void idleCleanupUsesTheSameDestroyPath() throws Exception {
        SandboxManager sandboxes = sandboxManager(true);
        try (DefaultHmsSessionManager manager = newManager(sandboxes, 10)) {
            String sessionId = manager.createSession("idle");
            Thread.sleep(1_100); // idleSeconds 以秒计，确保 > 0

            assertEquals(1, manager.cleanupIdleSessions(0));
            assertFalse(manager.sessionExists(sessionId));
            assertTrue(sandboxes.get(sessionId).isEmpty(), "空闲回收必须走同一销毁路径");
            assertFalse(Files.exists(temp.resolve("sandbox-root").resolve(sessionId)),
                    "cleanup=true 时空闲回收也应删除目录");
        }
    }

    private static long sandboxesCount(Path root) throws IOException {
        try (var stream = Files.list(root)) {
            return stream.count();
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=SessionSandboxLifecycleTest`
Expected: FAIL / 编译失败，`Builder` 没有 `sandboxManager`。

- [ ] **Step 3: 修改会话管理器**

在 `DefaultHmsSessionManager`：

1. 字段 `private final SandboxManager sandboxManager;`（允许 `null`，表示不使用沙箱）。
2. `Builder` 增加字段与链式方法：

```java
private SandboxManager sandboxManager;

public Builder sandboxManager(SandboxManager sandboxManager) {
    this.sandboxManager = sandboxManager;
    return this;
}
```

构造器里 `this.sandboxManager = builder.sandboxManager;`。

3. `doCreateSession` 在 `newAgentLoop` 之前创建沙箱，并传给 `newAgentLoop`：

```java
Sandbox sandbox = sandboxManager != null && sandboxManager.enabled()
        ? sandboxManager.create(sessionId) : null;
AgentLoop agentLoop = newAgentLoop(sessionId, tokenTracker,
        buildFullPrompt(sessionPrompt), allowSubAgents, sandbox);
```

4. `newAgentLoop` 增加参数 `Sandbox sandbox`，在 `ToolContext childOf` 之后：

```java
if (sandbox != null) {
    toolContext.set(Sandbox.STATE_KEY, sandbox);
}
```

   **子 Agent 工厂必须传同一个沙箱**：`newAgentLoop` 内部那处
   `toolContext.set(AgentTool.AGENT_FACTORY_KEY, prompt -> newAgentLoop(sessionId, ...))`
   （约 571 行）是同一会话派生子 Agent 的路径。它的调用要补上当前的 `sandbox` 参数 ——
   子 Agent 与主会话共享沙箱；漏传会让子 Agent 的所有文件工具返回
   `Error: Sandbox is not configured for this session.`

5. 会话数超限回滚分支，在 `loopSession.destroy()` 后增加：

```java
if (sandbox != null) {
    // 回滚必须无条件删目录：此时目录里只有本次新建的空内容
    sandboxManager.destroyImmediately(sessionId);
}
```

6. `destroySession` 与 `destroyIfExists` 在移除会话后调用：

```java
if (sandboxManager != null) {
    sandboxManager.destroy(sessionId);
}
```

7. `close()`（约 1259 行）逐个销毁会话的循环里，同样在 `entry.getValue().destroy()`
   之后补一次 `sandboxManager.destroy(entry.getKey())` —— 否则容器关闭时 LSP 子进程
   与沙箱登记资源不会被释放。

`cleanupIdleSessions` 已经复用 `destroyIfExists`，无需额外改动；确认其实现确实调用了该私有方法。

- [ ] **Step 4: 修改 AgentRuntimeConfig 与 Factory**

`AgentRuntimeConfig` 增加分量 `SandboxManager sandboxManager`（可为 `null`），并加 import。`AgentRuntimeFactory.create` 的 builder 链增加：

```java
.sandboxManager(config.sandboxManager())
```

`ApiAutoConfiguration.agentRuntimeConfig` 参数增加 `SandboxManager sandboxManager`，构造
`AgentRuntimeConfig` 时传入。

**同步修改测试装配**：`hms-core/src/test/java/com/inspirationi/loop/api/MultiAgentTestSupport.java:82`
的 `new AgentRuntimeConfig(...)` 也是位置参数构造，末位补 `null`（测试不启用沙箱）。
漏改会让整个测试模块编译失败。Task 10 追加 `sandboxProperties` 分量时同样处理这两处。

- [ ] **Step 5: AppConfig 注册 SandboxManager**

`AppConfig` 类注解改为：

```java
@EnableConfigurationProperties({PricingProperties.class, SandboxProperties.class})
```

新增 Bean：

```java
@Bean
@ConditionalOnMissingBean(SandboxManager.class)
public SandboxManager sandboxManager(SandboxProperties properties) {
    return new SandboxManager(properties);
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=SessionSandboxLifecycleTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add hms-core/src/main/java hms-core/src/test/java/com/inspirationi/loop/api/SessionSandboxLifecycleTest.java
git commit -m "feat: 会话生命周期接入沙箱"
```

---

### Task 4: Read 工具

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/FileReadTool.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/tool/impl/FileReadToolTest.java`

**Interfaces:**
- Consumes: `Sandbox.from(context)`、`SandboxProperties.getMaxFileBytes()`。
- Produces: 工具名 `Read`；输入 `file_path`、`line_start`、`line_end`。

- [ ] **Step 1: 写失败测试**

```java
package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileReadToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    @Test
    void readsWholeTextFileWithLineNumbers() throws IOException {
        ToolContext ctx = context();
        Sandbox sandbox = Sandbox.from(ctx);
        Files.writeString(sandbox.root().resolve("a.txt"), "one\ntwo\nthree");

        String result = new FileReadTool().execute(Map.of("file_path", "a.txt"), ctx);
        assertTrue(result.contains("1 | one"));
        assertTrue(result.contains("3 | three"));
    }

    @Test
    void readsLineRange() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("a.txt"), "one\ntwo\nthree");
        String result = new FileReadTool().execute(
                Map.of("file_path", "a.txt", "line_start", 2, "line_end", 2), ctx);
        assertTrue(result.contains("2 | two"));
        assertFalse(result.contains("1 | one"));
    }

    @Test
    void rejectsPathOutsideSandbox() throws IOException {
        ToolContext ctx = context();
        String result = new FileReadTool().execute(Map.of("file_path", "../secret.txt"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("sandbox"));
    }

    @Test
    void reportsMissingFile() throws IOException {
        String result = new FileReadTool().execute(Map.of("file_path", "nope.txt"), context());
        assertTrue(result.startsWith("Error: File not found"));
    }

    @Test
    void returnsImageAsBase64Payload() throws IOException {
        ToolContext ctx = context();
        // 最小 PNG 头足以触发图片分支，不需要合法图片
        Files.write(Sandbox.from(ctx).root().resolve("pic.png"),
                new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        String result = new FileReadTool().execute(Map.of("file_path", "pic.png"), ctx);
        assertTrue(result.contains("image/png"));
        assertTrue(result.toLowerCase().contains("base64"));
    }

    @Test
    void rejectsFileLargerThanLimit() throws IOException {
        ToolContext ctx = context();
        Sandbox sandbox = Sandbox.from(ctx);
        Files.writeString(sandbox.root().resolve("big.txt"), "x".repeat(1024));
        FileReadTool tool = new FileReadTool(512); // 可注入上限，便于测试
        String result = tool.execute(Map.of("file_path", "big.txt"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("too large"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=FileReadToolTest`
Expected: 编译失败。

- [ ] **Step 3: 实现工具**

要点：

- 构造器 `FileReadTool()` 默认上限 10 MiB；包内 `FileReadTool(long maxBytes)` 供测试。
- `sandbox = Sandbox.from(context)`，为 null 返回 `Error: Sandbox is not configured for this session.`
- 用 `sandbox.resolve(filePath)` 包裹 `SandboxViolationException`。
- `Files.size` 超限返回 `Error: File is too large (...). Read it in chunks or increase hms-core.sandbox.max-file-bytes.`
- 文本：`Files.readAllLines(path, UTF_8)`，行号格式 `%4d | %s`，单次最多 2000 行，末尾提示剩余行数。
- 图片扩展名集合 `png/jpg/jpeg/gif/webp/svg/bmp/ico`，MIME 映射与 20 MiB 上限；返回 JSON 字符串（与参考实现一致）。
- `isReadOnly()` 返回 true；`activityDescription` 返回 `📄 Reading <path>`。

完整实现直接参考：
参考实现目录下的 `tool/impl/FileReadTool.java`（213 行），仅做两处替换：
1. `context.getWorkDir().resolve(filePath).normalize()` → `Sandbox.from(context).resolve(filePath)`；
2. 错误文本保持 spec §6 的英文固定串（保留 `Error:` 前缀）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=FileReadToolTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/tool/impl/FileReadTool.java hms-core/src/test/java/com/inspirationi/loop/tool/impl/FileReadToolTest.java
git commit -m "feat: Read 工具"
```

---

### Task 5: Write 与 Edit 工具

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/FileWriteTool.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/FileEditTool.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/tool/impl/FileWriteEditToolTest.java`

**Interfaces:**
- Consumes: `Sandbox.from(context)`、`SandboxProperties.getMaxFileBytes()`。
- Produces: `Write`（`file_path`、`content`）、`Edit`（`file_path`、`old_string`、`new_string`）。

- [ ] **Step 1: 写失败测试**

```java
package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileWriteEditToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    @Test
    void writeCreatesFileAndParentDirectories() throws IOException {
        ToolContext ctx = context();
        String result = new FileWriteTool().execute(
                Map.of("file_path", "a/b/c.txt", "content", "hello"), ctx);
        assertTrue(result.startsWith("Created"));
        assertEquals("hello", Files.readString(Sandbox.from(ctx).root().resolve("a/b/c.txt")));
    }

    @Test
    void writeOverwritesExistingFile() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("a.txt");
        Files.writeString(file, "old");
        String result = new FileWriteTool().execute(
                Map.of("file_path", "a.txt", "content", "new"), ctx);
        assertTrue(result.startsWith("Updated"));
        assertEquals("new", Files.readString(file));
    }

    @Test
    void writeRejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        String result = new FileWriteTool().execute(
                Map.of("file_path", "../escape.txt", "content", "x"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
    }

    @Test
    void writeRejectsContentBeyondLimit() throws IOException {
        ToolContext ctx = context();
        String result = new FileWriteTool(4).execute(
                Map.of("file_path", "a.txt", "content", "12345"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("too large"));
    }

    @Test
    void editReplacesSingleOccurrenceAndReturnsDiff() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("a.txt");
        Files.writeString(file, "hello world");
        String result = new FileEditTool().execute(Map.of(
                "file_path", "a.txt", "old_string", "world", "new_string", "sandbox"), ctx);
        assertEquals("hello sandbox", Files.readString(file));
        assertTrue(result.contains("hello world"), "diff 必须包含被删除的行");
        assertTrue(result.contains("hello sandbox"), "diff 必须包含新增的行");
    }

    @Test
    void editFailsWhenOldStringMissing() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("a.txt"), "hello");
        String result = new FileEditTool().execute(Map.of(
                "file_path", "a.txt", "old_string", "absent", "new_string", "x"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("not found"));
    }

    @Test
    void editFailsWhenOldStringIsAmbiguous() throws IOException {
        ToolContext ctx = context();
        Path file = Sandbox.from(ctx).root().resolve("a.txt");
        Files.writeString(file, "x x");
        String result = new FileEditTool().execute(Map.of(
                "file_path", "a.txt", "old_string", "x", "new_string", "y"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("multiple") || result.contains("2"));
        assertEquals("x x", Files.readString(file), "匹配歧义时不得写入");
    }

    @Test
    void editRejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        String result = new FileEditTool().execute(Map.of(
                "file_path", "../a.txt", "old_string", "a", "new_string", "b"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("sandbox"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=FileWriteEditToolTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 Write**

参考实现目录下的 `tool/impl/FileWriteTool.java`，替换路径解析：

```java
Sandbox sandbox = Sandbox.from(context);
if (sandbox == null) return "Error: Sandbox is not configured for this session.";
Path path;
try {
    path = sandbox.resolve(filePath);
} catch (SandboxViolationException e) {
    return "Error: " + e.getMessage();
}
```

- 写入前校验 `content.getBytes(UTF_8).length <= maxBytes`，超限返回错误。
- 父目录 `Files.createDirectories(path.getParent())`；`path.getParent()` 已在沙箱校验范围内（路径本身已校验）。
- 返回 `Created`/`Updated` + 相对路径 + 行数。
- `riskLevel()` 返回 `RiskLevel.MEDIUM`。

- [ ] **Step 4: 实现 Edit**

参考实现目录下的 `tool/impl/FileEditTool.java`（197 行），保留：

- `old_string` 必须恰好出现一次；
- 读写 UTF-8；
- `generateUnifiedDiff` 的逻辑；
- 返回 diff 摘要。

差异：
1. 路径解析改用 `Sandbox.resolve`；
2. 删除参考实现里的 `startsWith` 检查（已由 Sandbox 统一完成）；
3. 错误文本沿用英文（`Error:` 前缀 + 具体原因）；
4. NULL/空白参数校验使用 `ToolValidator.requireString`；
5. `riskLevel()` 返回 `MEDIUM`。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=FileWriteEditToolTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/tool/impl/FileWriteTool.java hms-core/src/main/java/com/inspirationi/loop/tool/impl/FileEditTool.java hms-core/src/test/java/com/inspirationi/loop/tool/impl/FileWriteEditToolTest.java
git commit -m "feat: Write 与 Edit 工具"
```

---

### Task 6: Glob 与 ListFiles 工具

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/GlobTool.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/ListFilesTool.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/tool/impl/GlobListFilesToolTest.java`

**Interfaces:**
- Consumes: `Sandbox.resolve`。
- Produces:
  - `Glob`：`pattern`、可选 `path`；结果按修改时间倒序，最多 1000 条。
  - `ListFiles`：可选 `path`、`depth`（1-5）、`includeHidden`；最多 500 条。

- [ ] **Step 1: 写失败测试**

```java
package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobListFilesToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    @Test
    void globFindsNestedMatches() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/a.txt"), "a");
        Files.writeString(root.resolve("sub/b.md"), "b");

        String result = new GlobTool().execute(Map.of("pattern", "**/*.txt"), ctx);
        assertTrue(result.contains("sub/a.txt"));
        assertFalse(result.contains("sub/b.md"));
    }

    @Test
    void globDoesNotFollowSymlinkedDirectories() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symlink not supported: " + e.getMessage());
        }

        String result = new GlobTool().execute(Map.of("pattern", "**/*.txt"), ctx);
        assertFalse(result.contains("secret.txt"), "遍历不得跟随符号链接目录");
    }

    @Test
    void globRejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        String result = new GlobTool().execute(Map.of("pattern", "*.txt", "path", "../"), ctx);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void globStopsAtNodeLimit() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        for (int i = 0; i < 5; i++) {
            Files.writeString(root.resolve("f" + i + ".txt"), "x");
        }
        String result = new GlobTool(1).execute(Map.of("pattern", "*.txt"), ctx);
        assertTrue(result.contains("truncated"), "触发节点上限时必须显式标注截断");
    }

    @Test
    void listFilesShowsEntriesWithSize() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.writeString(root.resolve("a.txt"), "abc");
        Files.createDirectories(root.resolve("sub"));

        String result = new ListFilesTool().execute(Map.of(), ctx);
        assertTrue(result.contains("a.txt"));
        assertTrue(result.contains("3"), "文件大小必须出现在输出中");
        assertTrue(result.contains("sub"));
    }

    @Test
    void listFilesRecursesToRequestedDepth() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.createDirectories(root.resolve("a/b/c"));
        Files.writeString(root.resolve("a/b/c/deep.txt"), "x");

        String shallow = new ListFilesTool().execute(Map.of("depth", 1), ctx);
        assertFalse(shallow.contains("deep.txt"));
        String deep = new ListFilesTool().execute(Map.of("depth", 4), ctx);
        assertTrue(deep.contains("deep.txt"));
    }

    @Test
    void listFilesRejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        String result = new ListFilesTool().execute(Map.of("path", "../"), ctx);
        assertTrue(result.startsWith("Error:"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=GlobListFilesToolTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 Glob**

自研实现（不依赖外部命令）：

```java
Path base = sandbox.resolve(searchPath);
if (!Files.isDirectory(base)) return "Error: Directory not found: " + searchPath;

PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
List<Path> matches = new ArrayList<>();
AtomicInteger visited = new AtomicInteger();

Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), 20, new SimpleFileVisitor<>() {
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if (visited.incrementAndGet() > maxNodes) return FileVisitResult.TERMINATE;
        Path name = dir.getFileName();
        if (!dir.equals(base) && name != null && isIgnoredDir(name.toString())) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (visited.incrementAndGet() > maxNodes) return FileVisitResult.TERMINATE;
        if (attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
        Path relative = base.relativize(file);
        if (matcher.matches(Path.of(relative.toString().replace('\\', '/')))) {
            matches.add(file);
            if (matches.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
        return FileVisitResult.CONTINUE;
    }
});
```

注意：`EnumSet.noneOf(FileVisitOption.class)` 即不跟随符号链接；但 `visitFile` 仍可能收到符号链接文件（attrs.isSymbolicLink()），显式跳过。

常量：`MAX_RESULTS = 1000`，`maxNodes` 默认 `100_000`（包内构造器 `GlobTool(int maxNodes)` 供测试注入）。

达到 `maxNodes` 或 `MAX_RESULTS` 提前终止时，输出末尾追加 `... (results truncated)`。

排序：`Comparator.comparingLong(p -> lastModified(p)).reversed()`；输出相对路径。忽略目录集合：`.git`、`node_modules`、`target`、`build`、`__pycache__`、`.idea`、`.vscode`。

- [ ] **Step 4: 实现 ListFiles**

参考实现目录下的 `tool/impl/ListFilesTool.java`，差异：
1. `context.getWorkDir().resolve(...)` → `sandbox.resolve(...)`；
2. `Files.isDirectory(path)` 改为 `Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)`；
3. `Files.size(path)` 包 try/catch，单条失败跳过；
4. 输出用相对路径，不暴露宿主绝对路径；
5. 加入 `maxNodes` 上限（默认 100000），`entries.size() >= MAX_ENTRIES` 时截断。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=GlobListFilesToolTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/tool/impl/GlobTool.java hms-core/src/main/java/com/inspirationi/loop/tool/impl/ListFilesTool.java hms-core/src/test/java/com/inspirationi/loop/tool/impl/GlobListFilesToolTest.java
git commit -m "feat: Glob 与 ListFiles 工具"
```

---

### Task 7: Grep 工具

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/GrepTool.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/tool/impl/GrepToolTest.java`

**Interfaces:**
- Consumes: `Sandbox.resolve`、`SandboxProperties.getMaxFileBytes()`。
- Produces: `Grep`：`pattern`、可选 `path`、`glob`、`case_insensitive`、`max_results`。

- [ ] **Step 1: 写失败测试**

```java
package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    @Test
    void grepsMatchingLinesWithPathAndLineNumber() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.writeString(root.resolve("a.txt"), "alpha\nbeta\ngamma");

        String result = new GrepTool().execute(Map.of("pattern", "beta"), ctx);
        assertTrue(result.contains("a.txt:2:beta"));
    }

    @Test
    void caseInsensitiveOptionWorks() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("a.txt"), "Hello");

        assertTrue(new GrepTool().execute(Map.of("pattern", "hello"), ctx).contains("No matches"));
        assertTrue(new GrepTool().execute(
                Map.of("pattern", "hello", "case_insensitive", true), ctx).contains("Hello"));
    }

    @Test
    void globOptionFiltersFiles() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.writeString(root.resolve("A.java"), "needle");
        Files.writeString(root.resolve("B.txt"), "needle");

        String result = new GrepTool().execute(Map.of("pattern", "needle", "glob", "*.java"), ctx);
        assertTrue(result.contains("A.java"));
        assertFalse(result.contains("B.txt"));
    }

    @Test
    void invalidRegexReturnsError() throws IOException {
        String result = new GrepTool().execute(Map.of("pattern", "[unclosed"), context());
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.toLowerCase().contains("regular expression"));
    }

    @Test
    void rejectsEscapingPath() throws IOException {
        ToolContext ctx = context();
        Files.writeString(temp.resolve("outside.txt"), "needle");
        String result = new GrepTool().execute(Map.of("pattern", "needle", "path", "../"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertFalse(result.contains("needle"));
    }

    @Test
    void skipsFilesLargerThanLimit() throws IOException {
        ToolContext ctx = context();
        Files.writeString(Sandbox.from(ctx).root().resolve("big.txt"), "needle".repeat(100));

        String result = new GrepTool(16).execute(Map.of("pattern", "needle"), ctx);
        assertFalse(result.contains("big.txt:1:"), "超过 max-file-bytes 的文件必须跳过");
    }

    @Test
    void skipsBinaryFiles() throws IOException {
        ToolContext ctx = context();
        Files.write(Sandbox.from(ctx).root().resolve("bin.dat"),
                new byte[]{'a', 'b', 0, 'c', 'd'});

        String result = new GrepTool().execute(Map.of("pattern", "a"), ctx);
        assertFalse(result.contains("bin.dat"));
    }

    @Test
    void respectsMaxResults() throws IOException {
        ToolContext ctx = context();
        Path root = Sandbox.from(ctx).root();
        Files.writeString(root.resolve("a.txt"), "needle");
        Files.writeString(root.resolve("b.txt"), "needle");

        String result = new GrepTool().execute(Map.of("pattern", "needle", "max_results", 1), ctx);
        long hits = result.lines().filter(line -> line.contains(":1:needle")).count();
        assertEquals(1, hits, "结果数不得超过 max_results");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=GrepToolTest`
Expected: 编译失败。

- [ ] **Step 3: 实现纯 Java 版本**

不使用外部 `rg`/`grep`，保证跨平台与沙箱可控：

```java
Pattern regex;
try {
    regex = Pattern.compile(pattern,
            caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
} catch (PatternSyntaxException e) {
    return "Error: Invalid regular expression: " + e.getDescription();
}
```

遍历逻辑与 Glob 相同（不跟随符号链接、忽略目录、节点上限 100000）。对每个普通文件：

```java
if (attrs.size() > maxFileBytes) return FileVisitResult.CONTINUE;
byte[] bytes = Files.readAllBytes(file);
if (containsNul(bytes)) return FileVisitResult.CONTINUE; // 二进制
String text = new String(bytes, UTF_8);
String[] lines = text.split("\n", -1);
for (int i = 0; i < lines.length && results.size() < maxResults; i++) {
    if (regex.matcher(lines[i]).find()) {
        results.add(sandbox.display(file) + ":" + (i + 1) + ":" + lines[i]);
    }
}
```

`glob` 过滤使用 `FileSystems.getDefault().getPathMatcher("glob:" + glob)` 对相对路径匹配。输出末尾在截断时追加 `... (results truncated at N)`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=GrepToolTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/tool/impl/GrepTool.java hms-core/src/test/java/com/inspirationi/loop/tool/impl/GrepToolTest.java
git commit -m "feat: Grep 工具"
```

---

### Task 8: ProcessExecutor 与 Bash 工具

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/util/ProcessExecutor.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/BashTool.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/tool/impl/BashToolTest.java`

**Interfaces:**
- Consumes: `Sandbox.currentDir()`、`SandboxProperties.getBash()`。
- Produces:
  - `ProcessExecutor.executeShell(String command, Path cwd, long timeoutMs)` 与
    `ProcessExecutor.execute(List<String> command, Path cwd, long timeoutMs)`
    → `Result(int exitCode, String output, boolean timedOut)`
  - `Bash`：`command`、可选 `timeout_seconds`（不超过配置上限）、可选 `description`；风险等级 `HIGH`。

- [ ] **Step 1: 写失败测试**

```java
package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    @TempDir Path temp;

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        Sandbox sandbox = new Sandbox(root);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);
        return ctx;
    }

    private BashTool tool(int timeoutSeconds, int maxOutputChars) {
        SandboxProperties.Bash props = new SandboxProperties.Bash();
        props.setTimeoutSeconds(timeoutSeconds);
        props.setMaxOutputChars(maxOutputChars);
        return new BashTool(props);
    }

    @Test
    void executesCommandInSandboxDirectory() throws IOException {
        ToolContext ctx = context();
        String result = tool(10, 10_000).execute(Map.of("command", "echo hello"), ctx);
        assertTrue(result.contains("hello"));
        assertTrue(result.contains("[Exit code: 0]") || result.contains("Exit code: 0"));
    }

    @Test
    void runsInSandboxWorkingDirectory() throws IOException {
        ToolContext ctx = context();
        String result = tool(10, 10_000).execute(
                Map.of("command", isWindows() ? "cd" : "pwd"), ctx);

        String expected = Sandbox.from(ctx).root().toString()
                .replace('\\', '/').toLowerCase();
        assertTrue(result.replace('\\', '/').toLowerCase().contains(expected),
                "cwd 必须是会话沙箱：" + result);
    }

    @Test
    void reportsNonZeroExitCode() throws IOException {
        String result = tool(10, 10_000).execute(Map.of("command", "exit 3"), context());
        assertTrue(result.contains("3"), "非零退出码必须回传给模型");
    }

    @Test
    void timeoutKillsProcessAndReturnsQuickly() throws IOException {
        String slow = isWindows()
                ? "cmd /c ping -n 6 127.0.0.1 >nul"
                : "sleep 5";
        long start = System.nanoTime();
        String result = tool(1, 10_000).execute(Map.of("command", slow), context());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.toLowerCase().contains("timed out"), "超时必须明确报告");
        assertTrue(elapsedMs < 4_000, "超时后必须立即返回，实际用时 " + elapsedMs + "ms");
    }

    @Test
    void rejectsDangerousCommand() throws IOException {
        ToolContext ctx = context();
        Path marker = Sandbox.from(ctx).root().resolve("should-not-exist.txt");
        String result = tool(10, 10_000).execute(
                Map.of("command", "rm -rf / && touch " + marker.getFileName()), ctx);
        assertTrue(result.startsWith("Error:"));
        assertFalse(Files.exists(marker));
    }

    @Test
    void truncatesLongOutput() throws IOException {
        String result = tool(10, 10).execute(
                Map.of("command", "echo 0123456789abcdefghij"), context());
        assertTrue(result.contains("output truncated"));
    }

    @Test
    void descriptionStatesItIsNotASecurityBoundary() {
        String description = tool(10, 10_000).description();
        assertTrue(description.contains("不是安全边界"),
                "工具描述必须明确 Bash 不是安全边界");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=BashToolTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 ProcessExecutor**

移植参考实现目录下的 `tool/util/ProcessExecutor.java`，但修复两处：

1. **超时后杀死进程树**：

```java
if (!finished) {
    killTree(process);
    process.waitFor(2, TimeUnit.SECONDS);
    return new Result(-1, stdout, stderr, true);
}
```

```java
private static void killTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
}
```

2. **读取与等待顺序**：输出必须由独立线程读取，主线程 `waitFor(timeout)`。
   若在主线程直接 `readAllBytes()`，它会阻塞到进程退出，超时形同虚设；若既不读又等待，
   子进程写满管道后会死锁。`redirectErrorStream(true)` 合并 stderr：

```java
public static Result execute(List<String> command, Path workDir, long timeoutMs) {
    ProcessBuilder pb = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true);
    Process process = null;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
        process = pb.start();
        Process started = process;
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream in = started.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
                // 进程被杀时管道关闭，属于预期
            }
        });

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            killTree(process);
            process.waitFor(2, TimeUnit.SECONDS);
            reader.join(1_000);
            return new Result(-1, buffer.toString(StandardCharsets.UTF_8), true);
        }
        reader.join(1_000);
        return new Result(process.exitValue(), buffer.toString(StandardCharsets.UTF_8), false);
    } catch (IOException e) {
        return new Result(-1, "IOException: " + e.getMessage(), false);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Result(-1, "Interrupted", false);
    } finally {
        if (process != null && process.isAlive()) {
            killTree(process);
        }
    }
}

/** 通过系统 shell 执行 —— 供 Bash 工具使用。 */
public static Result executeShell(String command, Path workDir, long timeoutMs) {
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    return execute(windows
            ? List.of("cmd.exe", "/c", command)
            : List.of("/bin/sh", "-c", command), workDir, timeoutMs);
}
```

`execute(List, ...)` 供 Notification 使用：参数以列表传入，不经过 shell，避免注入。

- [ ] **Step 4: 实现 BashTool**

- 危险命令检查**复用底座已有实现** `com.inspirationi.loop.permission.DangerousPatterns.detectShellDanger(command)`
  （返回非 null 即拒绝），不要移植参考项目的 `DANGEROUS_COMMANDS` 列表；
- cwd = `sandbox.currentDir()`；
- 超时取 `min(input.timeout_seconds, props.getBash().getTimeoutSeconds())`，缺省用配置值；
- 输出按 `maxOutputChars` 截断，追加 `... (output truncated)`；
- 结果末尾追加 `[Exit code: N]`（非零时）；
- 描述中明确：`Bash 在沙箱目录中执行，但不是安全边界；绝对路径与 cd 仍可访问沙箱外文件。`
- `riskLevel()` 返回 `HIGH`；
- `activityDescription` 截断命令到 60 字符。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=BashToolTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/tool/util/ProcessExecutor.java hms-core/src/main/java/com/inspirationi/loop/tool/impl/BashTool.java hms-core/src/test/java/com/inspirationi/loop/tool/impl/BashToolTest.java
git commit -m "feat: ProcessExecutor 与 Bash 工具"
```

---

### Task 9: NotebookEdit 工具

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/NotebookEditTool.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/tool/impl/NotebookEditToolTest.java`

**Interfaces:**
- Consumes: `Sandbox.resolve`。
- Produces: `NotebookEdit`：`notebook_path`、`cell_number`（0-based）、`new_source`、可选
  `cell_type`、可选 `edit_mode`（`replace`/`insert`/`delete`，默认 `replace`）。

- [ ] **Step 1: 写失败测试**

```java
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
    void requiresNewSourceForReplace() throws IOException {
        ToolContext ctx = context();
        writeNotebook(Sandbox.from(ctx), "a.ipynb", "print(1)");

        String result = new NotebookEditTool().execute(Map.of(
                "notebook_path", "a.ipynb", "cell_number", 0, "edit_mode", "replace"), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("new_source"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=NotebookEditToolTest`
Expected: 编译失败。

- [ ] **Step 3: 移植实现**

从参考实现目录下的 `tool/impl/NotebookEditTool.java`（343 行）逐行复制，差异：

0. **输入字段按本 spec 重命名**（参考实现用的是 `path`/`operation`/`cellIndex`/`source`）：
   `notebook_path` → 内部变量 `pathStr`；`edit_mode` → 内部 `operation`；
   `cell_number` → 内部 `cellIndex`；`new_source` → 内部 `source`。
   默认 `edit_mode=replace`；`cell_number` 为 0-based。
1. 包名改为 `com.inspirationi.loop.tool.impl`；
2. `context.getWorkDir().resolve(pathStr)` → `Sandbox.from(context).resolve(pathStr)`，并包裹 `SandboxViolationException`；
3. 增加扩展名校验：`filePath.getFileName().toString().toLowerCase().endsWith(".ipynb")`，否则返回 `Error: NotebookEdit only supports .ipynb files.`；
4. 错误文本沿用英文（保留 `Error:` 前缀）；
5. `riskLevel()` 返回 `MEDIUM`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=NotebookEditToolTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/tool/impl/NotebookEditTool.java hms-core/src/test/java/com/inspirationi/loop/tool/impl/NotebookEditToolTest.java
git commit -m "feat: NotebookEdit 工具"
```

---

### Task 10: LSP 子系统与 LSPTool

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/lsp/LSPClient.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/lsp/LSPServerConfig.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/lsp/LSPServerInstance.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/lsp/LSPServerManager.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/lsp/LSPDiagnosticRegistry.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/LSPTool.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/lsp/LSPServerConfigTest.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/tool/impl/LSPToolTest.java`

**Interfaces:**
- Consumes: `SandboxProperties.Server`、`Sandbox.root()`、`Sandbox.addResource()`；
  以及 Task 3 建立的 `DefaultHmsSessionManager.Builder` / `AgentRuntimeConfig`，本任务为二者
  追加 `sandboxProperties(SandboxProperties)` 链式方法与同名记录分量。
- Produces:
  - `LSPServerManager(Path workspaceRoot)`；`registerServer(LSPServerConfig)`；`sendRequest(String filePath, String method, Object params)`；`close()`。
  - `LSPTool` 从 `ToolContext.get("LSP_SERVER_MANAGER")` 与 `ToolContext.get("LSP_DIAGNOSTIC_REGISTRY")`
    取管理器。
  - `LSP` 输入：`operation`（`goToDefinition`/`findReferences`/`hover`/`documentSymbols`/`diagnostics`）、
    `file_path`、`line`（1-based）、`character`（0-based）。

- [ ] **Step 1: 写失败的配置测试**

```java
@Test
void serverConfigValidatesRequiredFields() {
    assertThrows(IllegalArgumentException.class, () ->
            new LSPServerConfig("x", "", List.of(), Map.of(".java", "java"),
                    null, Map.of(), Map.of(), null, 0, 0));
    assertThrows(IllegalArgumentException.class, () ->
            new LSPServerConfig("x", "jdtls", List.of(), Map.of(),
                    null, Map.of(), Map.of(), null, 0, 0));
}

@Test
void defaultsAreApplied() {
    LSPServerConfig cfg = new LSPServerConfig("x", "jdtls", null,
            Map.of(".java", "java"), null, null, null, null, 0, 0);
    assertEquals(List.of(), cfg.args());
    assertEquals("stdio", cfg.transport());
    assertEquals(30_000, cfg.startupTimeout());
    assertEquals(3, cfg.maxRestarts());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=LSPServerConfigTest`
Expected: 编译失败。

- [ ] **Step 3: 移植 LSP 类**

从参考目录逐文件复制并改包名：

| 参考文件 | 目标文件 | 差异 |
|---|---|---|
| `lsp/LSPClient.java` (308 行) | `lsp/LSPClient.java` | 仅改包名；确认 `ProcessExecutor` 无关 |
| `lsp/LSPServerConfig.java` (56 行) | `lsp/LSPServerConfig.java` | 仅改包名 |
| `lsp/LSPServerInstance.java` (277 行) | `lsp/LSPServerInstance.java` | 仅改包名 |
| `lsp/LSPServerManager.java` (281 行) | `lsp/LSPServerManager.java` | 仅改包名 |
| `lsp/LSPDiagnosticRegistry.java` (251 行) | `lsp/LSPDiagnosticRegistry.java` | 仅改包名 |

复制命令（在项目根执行，注意路径含空格时加引号）：

```bash
SRC="/c/Users/Administrator/Downloads/claude-code-main/claude-code-main/src/main/java/com/claudecode/lsp"
DST="hms-core/src/main/java/com/inspirationi/loop/lsp"
mkdir -p "$DST"
for f in LSPClient LSPServerConfig LSPServerInstance LSPServerManager LSPDiagnosticRegistry; do
  sed 's/^package com\.claudecode\.lsp;/package com.inspirationi.loop.lsp;/' "$SRC/$f.java" > "$DST/$f.java"
done
```

检查引用：`grep -rn "com.claudecode" hms-core/src/main/java/com/inspirationi/loop/lsp` 必须无输出。

- [ ] **Step 4: 移植 LSPTool 并接沙箱**

从参考实现目录下的 `tool/impl/LSPTool.java` 复制，差异：

0. **operation 取值改为本 spec 的 camelCase**，在入口处映射到参考实现的方法名：
   `goToDefinition`→`textDocument/definition`，`findReferences`→`textDocument/references`，
   `hover`→`textDocument/hover`，`documentSymbols`→`textDocument/documentSymbol`，
   `diagnostics`→读 `LSP_DIAGNOSTIC_REGISTRY`（不发请求）；未知值返回参数错误。
1. 包名；
2. 路径解析：

```java
Sandbox sandbox = Sandbox.from(context);
if (sandbox == null) return "Error: Sandbox is not configured for this session.";
Path path;
try {
    path = sandbox.resolve(filePath);
} catch (SandboxViolationException e) {
    return "Error: " + e.getMessage();
}
```

3. 文件必须在沙箱内且在打开前同步内容：调用 `manager.openFile(path.toString(), Files.readString(path))`（若尚未打开）后再发请求；
4. `diagnostics` 保持从 `LSP_DIAGNOSTIC_REGISTRY` 读取；
5. `riskLevel()` 返回 `READ_ONLY`。

同一任务内新增 `hms-core/src/test/java/com/inspirationi/loop/tool/impl/LSPToolTest.java`
（无语言服务器环境也能覆盖参数与缺失检查）：

```java
package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.lsp.LSPServerManager;
import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LSPToolTest {

    @TempDir Path temp;

    private ToolContext context() throws IOException {
        Path root = Files.createDirectories(temp.resolve("session"));
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, new Sandbox(root));
        return ctx;
    }

    @Test
    void reportsMissingManager() throws IOException {
        String result = new LSPTool().execute(Map.of(
                "operation", "hover", "file_path", "a.java", "line", 1, "character", 0), context());
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("LSP"), result);
    }

    @Test
    void rejectsUnknownOperation() throws IOException {
        ToolContext ctx = context();
        ctx.set("LSP_SERVER_MANAGER", new LSPServerManager(Sandbox.from(ctx).root()));

        String result = new LSPTool().execute(Map.of(
                "operation", "renameEverything", "file_path", "a.java", "line", 1, "character", 0), ctx);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("operation"), result);
    }
}
```

- [ ] **Step 5: 会话创建时创建 LSP 管理器**

在 `DefaultHmsSessionManager.newAgentLoop` 中，沙箱存在且 `sandboxProperties.getLsp().getServers()` 非空时：

```java
LSPServerManager lsp = new LSPServerManager(sandbox.root());
for (var entry : sandboxProperties.getLsp().getServers().entrySet()) {
    SandboxProperties.Server s = entry.getValue();
    lsp.registerServer(new LSPServerConfig(entry.getKey(), s.getCommand(), s.getArgs(),
            s.getExtensions(), "stdio", s.getEnv(), Map.of(), null,
            s.getStartupTimeout(), 3));
}
sandbox.addResource(lsp);
toolContext.set("LSP_SERVER_MANAGER", lsp);
LSPDiagnosticRegistry registry = new LSPDiagnosticRegistry();
lsp.setDiagnosticRegistry(registry);
toolContext.set("LSP_DIAGNOSTIC_REGISTRY", registry);
```

`DefaultHmsSessionManager` 需要注入 `SandboxProperties`（Builder 增加 `sandboxProperties`）。`AgentRuntimeConfig` 同步增加该字段，`ApiAutoConfiguration` 传参。

- [ ] **Step 6: 运行测试**

Run: `mvn -pl hms-core test -Dtest=LSPServerConfigTest`
Expected: PASS。

Run: `mvn -pl hms-core test -Dtest=SessionSandboxLifecycleTest`
Expected: PASS（无 LSP 配置时不创建管理器）。

- [ ] **Step 7: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/lsp hms-core/src/main/java/com/inspirationi/loop/tool/impl/LSPTool.java hms-core/src/main/java/com/inspirationi/loop/api hms-core/src/test/java/com/inspirationi/loop/lsp
git commit -m "feat: LSP 子系统与 LSP 工具"
```

---

### Task 11: Worktree 子系统与 Enter/ExitWorktree

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/worktree/WorktreeManager.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/EnterWorktreeTool.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/ExitWorktreeTool.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/worktree/WorktreeManagerTest.java`

**Interfaces:**
- Consumes: `Sandbox.root()`、`Sandbox.currentDir()`、`Sandbox.enter`、`Sandbox.exit`。
- Produces:
  - `WorktreeManager`：`validateSlug`、`createOrResume`、`enterWorktree`、`cleanupWorktree`、
    `getCurrentSession`、`findGitRoot`；常量 `CONTEXT_KEY = "WORKTREE_MANAGER"`。
  - `EnterWorktree`：`name`、可选 `base_branch`。
  - `ExitWorktree`：`action`（keep/remove）、可选 `discard_changes`（默认 false）。

- [ ] **Step 1: 写失败的 Worktree 测试**

```java
package com.inspirationi.loop.worktree;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.impl.EnterWorktreeTool;
import com.inspirationi.loop.tool.impl.ExitWorktreeTool;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeManagerTest {

    @Test
    void rejectsUnsafeSlugs() {
        assertThrows(IllegalArgumentException.class, () -> WorktreeManager.validateSlug("../x"));
        assertThrows(IllegalArgumentException.class, () -> WorktreeManager.validateSlug("a/../../b"));
        WorktreeManager.validateSlug("feature/one");
    }

    @Test
    void createsAndRemovesWorktreeInsideSandbox(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git not installed");
        Path repo = Files.createDirectories(temp.resolve("repo"));
        run(repo, "git", "init");
        run(repo, "git", "config", "user.email", "t@example.com");
        run(repo, "git", "config", "user.name", "t");
        Files.writeString(repo.resolve("a.txt"), "a");
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-m", "init");

        WorktreeManager manager = new WorktreeManager();
        var result = manager.createOrResume("w1", repo, null);

        assertTrue(Files.isDirectory(result.worktreePath()));
        assertTrue(result.worktreePath().startsWith(repo), "工作树必须位于沙箱仓库内");
        assertTrue(result.worktreePath().getParent().getFileName().toString().equals("worktrees"));
        assertTrue(manager.removeWorktree(result.worktreePath(), result.worktreeBranch(), repo));
        assertFalse(Files.exists(result.worktreePath()));
    }

    @Test
    void enterTracksOriginalCwd(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git not installed");
        Path repo = Files.createDirectories(temp.resolve("repo"));
        run(repo, "git", "init");
        run(repo, "git", "config", "user.email", "t@example.com");
        run(repo, "git", "config", "user.name", "t");
        Files.writeString(repo.resolve("a.txt"), "a");
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-m", "init");

        WorktreeManager manager = new WorktreeManager();
        var session = manager.enterWorktree("w2", repo, null, repo);

        assertEquals(repo, session.originalCwd());
        assertNotNull(manager.getCurrentSession());
        assertTrue(manager.cleanupWorktree(true));
        assertNull(manager.getCurrentSession());
    }

    @Test
    void enterToolSwitchesSandboxCurrentDirAndExitRestores(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git not installed");
        Path repo = initRepo(temp);
        Sandbox sandbox = new Sandbox(repo);
        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);

        String entered = new EnterWorktreeTool().execute(Map.of("name", "feature-x"), ctx);
        assertFalse(entered.startsWith("Error:"), entered);
        assertNotEquals(sandbox.root(), sandbox.currentDir());
        assertTrue(sandbox.currentDir().startsWith(repo));

        String exited = new ExitWorktreeTool().execute(Map.of("action", "keep"), ctx);
        assertFalse(exited.startsWith("Error:"), exited);
        assertEquals(sandbox.root(), sandbox.currentDir());
    }

    private static Path initRepo(Path temp) throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        run(repo, "git", "init");
        run(repo, "git", "config", "user.email", "t@example.com");
        run(repo, "git", "config", "user.name", "t");
        Files.writeString(repo.resolve("a.txt"), "a");
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-m", "init");
        return repo;
    }

    private static boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "command timed out: " + String.join(" ", command));
        assertEquals(0, process.exitValue(), "command failed: " + String.join(" ", command));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=WorktreeManagerTest`
Expected: 编译失败。

- [ ] **Step 3: 移植 WorktreeManager**

从参考实现目录下的 `worktree/WorktreeManager.java` 复制，差异：

1. 包名；
2. worktree 目录从 `gitRoot.resolve(".claude").resolve("worktrees")` 改为 `gitRoot.resolve("worktrees")`（沙箱根即 git 仓库，见 spec）；
3. `enterWorktree` 改为四参
   `enterWorktree(String slug, Path gitRoot, String baseBranch, Path originalCwd)`，
   内部不再读 `System.getProperty("user.dir")`。该方法只有新工具与测试调用，没有其他内部调用者；
4. 删除 `cleanupStaleWorktrees` 中与 `.claude` 相关的路径拼接，改为 `worktrees`；
5. `symlinkDirectories` 保留（符号链接目标仍在沙箱内）。

- [ ] **Step 4: 实现 EnterWorktree**

```java
Sandbox sandbox = Sandbox.from(context);
Path gitRoot = WorktreeManager.findGitRoot(sandbox.root());
if (gitRoot == null) {
    return "Error: The session sandbox is not a git repository. Run `git init` in the sandbox first.";
}
if (!gitRoot.toRealPath().equals(sandbox.root())) {
    return "Error: Worktrees can only be created from a git repository at the sandbox root.";
}

WorktreeManager manager = context.getOrDefault(WorktreeManager.CONTEXT_KEY, new WorktreeManager());
context.set(WorktreeManager.CONTEXT_KEY, manager);
if (manager.getCurrentSession() != null) {
    return "Error: This session is already inside a worktree. Call ExitWorktree first.";
}
```

- slug 校验失败返回错误文本；
- `manager.createOrResume(slug, gitRoot, baseBranch)`；
- `sandbox.enter(result.worktreePath())`；
- 返回 worktree 相对路径与分支名。

管理器实例存放在 `ToolContext`（key `WorktreeManager.CONTEXT_KEY = "WORKTREE_MANAGER"`），首次使用时创建并 `context.set`。

- [ ] **Step 5: 实现 ExitWorktree**

- 从 context 取管理器；
- `action=remove` 时先检查未提交修改：有修改且未传 `discard_changes=true` 返回错误；
- 删除后 `sandbox.exit()`；
- `keep` 只调用 `sandbox.exit()`；
- 无活动 worktree 时返回 `Error: This session is not inside a worktree.`

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=WorktreeManagerTest`
Expected: PASS（无 git 时跳过）。

- [ ] **Step 7: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/worktree hms-core/src/main/java/com/inspirationi/loop/tool/impl/EnterWorktreeTool.java hms-core/src/main/java/com/inspirationi/loop/tool/impl/ExitWorktreeTool.java hms-core/src/test/java/com/inspirationi/loop/worktree
git commit -m "feat: Worktree 子系统与进入/退出工具"
```

---

### Task 12: Notification 工具

**Files:**
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/NotificationSink.java`
- Create: `hms-core/src/main/java/com/inspirationi/loop/tool/impl/NotificationTool.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/tool/impl/NotificationToolTest.java`

**Interfaces:**
- Consumes: `ToolContext.get(NotificationSink.CONTEXT_KEY)`、`SandboxProperties.Notification`。
- Produces: `Notification`：`title`、`message`、可选 `level`、`sound`。

- [ ] **Step 1: 写失败测试**

```java
package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.NotificationSink;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NotificationToolTest {

    private static SandboxProperties.Notification props(boolean systemCommand) {
        SandboxProperties.Notification props = new SandboxProperties.Notification();
        props.setSystemCommand(systemCommand);
        return props;
    }

    @Test
    void delegatesToInjectedSink() {
        ToolContext ctx = ToolContext.defaultContext();
        AtomicReference<String> title = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        AtomicReference<String> level = new AtomicReference<>();
        NotificationSink sink = (t, m, l, sound) -> {
            title.set(t);
            message.set(m);
            level.set(l);
        };
        ctx.set(NotificationSink.CONTEXT_KEY, sink);

        String result = new NotificationTool(props(false)).execute(
                Map.of("title", "构建完成", "message", "全部测试通过", "level", "warning"), ctx);

        assertFalse(result.startsWith("Error:"), result);
        assertEquals("构建完成", title.get());
        assertEquals("全部测试通过", message.get());
        assertEquals("warning", level.get());
    }

    @Test
    void failsWhenNoSinkAndSystemCommandDisabled() {
        String result = new NotificationTool(props(false)).execute(
                Map.of("title", "t", "message", "m"), ToolContext.defaultContext());

        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No notification sink"));
    }

    @Test
    void validatesRequiredFields() {
        NotificationTool tool = new NotificationTool(props(false));
        assertTrue(tool.execute(Map.of("message", "m"), ToolContext.defaultContext())
                .startsWith("Error:"));
        assertTrue(tool.execute(Map.of("title", "t"), ToolContext.defaultContext())
                .startsWith("Error:"));
    }

    @Test
    void truncatesLongTitleToEightyChars() {
        ToolContext ctx = ToolContext.defaultContext();
        AtomicReference<String> sent = new AtomicReference<>();
        NotificationSink sink = (t, m, l, sound) -> sent.set(t);
        ctx.set(NotificationSink.CONTEXT_KEY, sink);

        new NotificationTool(props(false)).execute(
                Map.of("title", "x".repeat(200), "message", "m"), ctx);

        assertEquals(80, sent.get().length(), "title 必须截断到 80 字符");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=NotificationToolTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 NotificationSink 与工具**

```java
package com.inspirationi.loop.tool;

public interface NotificationSink {
    String CONTEXT_KEY = "NOTIFICATION_SINK";
    void send(String title, String message, String level, boolean sound);
}
```

工具逻辑：

1. `ToolValidator.requireString(input, "title")` / `"message"`；
2. `level` 默认 `info`，限定 `info|warning|error`；
3. `title` 截断到 80；
4. 优先 `context.get(NotificationSink.CONTEXT_KEY)`；
5. 未注入且 `systemCommand=true`：Windows 用 PowerShell Toast（失败回退 `msg`），macOS `osascript -e 'display notification ...'`，Linux `notify-send`；
6. 全部失败返回 `Error: ...`；
7. `riskLevel()` 返回 `READ_ONLY`。

系统命令通过 `ProcessExecutor.execute` 执行，参数使用列表形式，避免 shell 注入。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=NotificationToolTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/tool/NotificationSink.java hms-core/src/main/java/com/inspirationi/loop/tool/impl/NotificationTool.java hms-core/src/test/java/com/inspirationi/loop/tool/impl/NotificationToolTest.java
git commit -m "feat: Notification 工具"
```

---

### Task 13: 工具注册与权限

**Files:**
- Modify: `hms-core/src/main/java/com/inspirationi/loop/config/ToolConfiguration.java`
- Test: `hms-core/src/test/java/com/inspirationi/loop/config/SandboxToolRegistrationTest.java`

**Interfaces:**
- Consumes: `SandboxProperties.isUsable()`、`SandboxProperties.getLsp().getServers()`、
  `PermissionRuleEngine.addCommandExtractor`。
- Produces:
  - 沙箱启用时注册 `Read`、`Write`、`Edit`、`Glob`、`Grep`、`ListFiles`、`Bash`、`NotebookEdit`、
    `EnterWorktree`、`ExitWorktree`；
  - LSP 配置非空时注册 `LSP`；
  - 始终注册 `Notification`；
  - `Bash` 命令提取器（`permissionRuleEngine.addCommandExtractor("Bash", "command")`）；
  - 工具构造器契约：`FileReadTool(long maxBytes)`、`FileWriteTool(long maxBytes)`、
    `GrepTool(long maxBytes)`、`BashTool(SandboxProperties.Bash)`、
    `NotificationTool(SandboxProperties.Notification)`，其余工具无参。

- [ ] **Step 1: 写失败测试**

把注册逻辑抽成包内静态方法便于测试：

```java
package com.inspirationi.loop.config;

import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SandboxToolRegistrationTest {

    @TempDir Path temp;

@Test
void doesNotRegisterPathToolsWhenSandboxDisabled() {
    ToolRegistry registry = new ToolRegistry();
    SandboxProperties props = new SandboxProperties(); // enabled=false
    ToolConfiguration.registerSandboxTools(registry, props, new PermissionRuleEngine(new PermissionSettings()));
    assertFalse(registry.getToolNames().contains("Read"));
    assertFalse(registry.getToolNames().contains("Bash"));
}

@Test
void registersPathToolsWhenSandboxEnabled() throws IOException {
    ToolRegistry registry = new ToolRegistry();
    SandboxProperties props = new SandboxProperties();
    props.setEnabled(true);
    props.setRoot(temp.resolve("root"));
    Files.createDirectories(props.getRoot());
    ToolConfiguration.registerSandboxTools(registry, props, new PermissionRuleEngine(new PermissionSettings()));
    assertTrue(registry.getToolNames().containsAll(
            Set.of("Read", "Write", "Edit", "Glob", "Grep", "ListFiles", "Bash", "NotebookEdit",
                    "EnterWorktree", "ExitWorktree")));
    assertFalse(registry.getToolNames().contains("LSP")); // 未配置服务器
}

@Test
void registersLspOnlyWhenServersConfigured() throws IOException {
    SandboxProperties props = new SandboxProperties();
    props.setEnabled(true);
    props.setRoot(temp.resolve("root"));
    Files.createDirectories(props.getRoot());
    SandboxProperties.Server server = new SandboxProperties.Server();
    server.setCommand("jdtls");
    server.getExtensions().put(".java", "java");
    props.getLsp().getServers().put("java", server);
    ToolRegistry registry = new ToolRegistry();
    ToolConfiguration.registerSandboxTools(registry, props, new PermissionRuleEngine(new PermissionSettings()));
    assertTrue(registry.getToolNames().contains("LSP"));
}

@Test
void permissionModesFollowToolRiskLevels() throws IOException {
    SandboxProperties props = new SandboxProperties();
    props.setEnabled(true);
    props.setRoot(temp.resolve("root"));
    Files.createDirectories(props.getRoot());

    PermissionSettings settings = new PermissionSettings();
    PermissionRuleEngine engine = new PermissionRuleEngine(settings);
    ToolConfiguration.registerSandboxTools(new ToolRegistry(), props, engine);

    // READ_ONLY 在 STRICT 放行
    settings.setCurrentMode(PermissionMode.STRICT);
    assertTrue(engine.evaluate("Read", Map.of(), Tool.RiskLevel.READ_ONLY).isAllowed());

    // MEDIUM 在 SAFE 不自动放行：权限引擎的既有语义是 ASK（不是 deny）
    settings.setCurrentMode(PermissionMode.SAFE);
    assertTrue(engine.evaluate("Write", Map.of(), Tool.RiskLevel.MEDIUM).needsAsk());

    // HIGH 在 DEFAULT 触发确认，且命令提取器已注册（input 带 command 不报错）
    settings.setCurrentMode(PermissionMode.DEFAULT);
    assertTrue(engine.evaluate("Bash", Map.of("command", "mvn -q test"),
            Tool.RiskLevel.HIGH).needsAsk());
    assertEquals("mvn", engine.extractCommandPrefixForTool("Bash", Map.of("command", "mvn -q test")));
}
}
```

> 与 spec §7.4 的差异：`SAFE` 模式对 `MEDIUM` 的既有行为是 ASK 而非 deny
> （`PermissionRuleEngine` 第 ⑦/⑧ 步），此测试按实际行为断言；spec 已同步修正。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl hms-core test -Dtest=SandboxToolRegistrationTest`
Expected: 编译失败。

- [ ] **Step 3: 实现注册方法**

```java
static void registerSandboxTools(ToolRegistry registry, SandboxProperties props,
                                 PermissionRuleEngine permissionRuleEngine) {
    if (!props.isUsable()) {
        log.warn("Sandbox not usable: skipping file tools");
        return;
    }
    long maxBytes = props.getMaxFileBytes();
    registry.registerAll(
            new FileReadTool(maxBytes),
            new FileWriteTool(maxBytes),
            new FileEditTool(),
            new GlobTool(),
            new GrepTool(maxBytes),
            new ListFilesTool(),
            new BashTool(props.getBash()),
            new NotebookEditTool(),
            new EnterWorktreeTool(),
            new ExitWorktreeTool()
    );
    if (!props.getLsp().getServers().isEmpty()) {
        registry.register(new LSPTool());
    }
    permissionRuleEngine.addCommandExtractor("Bash", "command");
}
```

在 `toolRegistry(...)` 方法中调用：

```java
registerSandboxTools(registry, sandboxProperties, permissionRuleEngine);
registry.register(new NotificationTool(sandboxProperties.getNotification()));
```

`toolRegistry` 方法签名增加 `SandboxProperties sandboxProperties` 参数；可用性判断统一走
`props.isUsable()`，不额外注入 `SandboxManager`（避免只用来做日志的无用依赖）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl hms-core test -Dtest=SandboxToolRegistrationTest`
Expected: PASS。

- [ ] **Step 5: 运行已有装配测试**

Run: `mvn -pl hms-core test -Dtest=AutoConfigurationImportsTest,BeanShutdownTest,ToolRegistryConcurrencyTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add hms-core/src/main/java/com/inspirationi/loop/config/ToolConfiguration.java hms-core/src/test/java/com/inspirationi/loop/config/SandboxToolRegistrationTest.java
git commit -m "feat: 条件注册沙箱工具并接入权限命令提取"
```

---

### Task 14: 配置示例、文档与全量回归

**Files:**
- Modify: `demo-app/src/main/resources/application.yml`
- Modify: `demo-app/src/main/resources/application-prod.yml`
- Modify: `README.md`

- [ ] **Step 1: 增加公共配置示例**

在 `application.yml` 的 `spring:` 之前增加：

```yaml
hms-core:
  sandbox:
    # 沙箱总开关。关闭时不注册全部文件类工具。
    enabled: ${HMS_SANDBOX_ENABLED:true}
    # 沙箱根目录：每个会话在其下拥有独立的 <sessionId>/ 子目录。
    root: ${HMS_SANDBOX_ROOT:./sandbox}
    # 会话销毁/空闲回收时是否删除会话沙箱目录（默认保留产出）。
    cleanup-on-destroy: false
    max-file-bytes: 10485760
    bash:
      timeout-seconds: 120
      max-output-chars: 40000
```

- [ ] **Step 2: 增加生产配置**

在 `application-prod.yml` 的 `hms-core:` 下增加：

```yaml
  sandbox:
    enabled: ${HMS_SANDBOX_ENABLED:true}
    # 生产部署建议用环境变量指向持久化目录（如 /var/lib/hms/sandbox）；
    # 默认值保持相对路径，避免在 Windows 上把 /var/... 解析到当前盘根导致创建失败。
    root: ${HMS_SANDBOX_ROOT:./sandbox}
    cleanup-on-destroy: ${HMS_SANDBOX_CLEANUP:false}
    lsp:
      servers: {}
```

- [ ] **Step 3: 更新 README**

中英文两节各补充：

1. 沙箱模型与目录布局；
2. 配置项表（enabled/root/cleanup-on-destroy/max-file-bytes/bash/lsp/notification）；
3. 12 个工具清单与风险等级；
4. 明确 Bash 不是安全边界；
5. LSP 需要本地安装语言服务器；
6. 沙箱初始为空，调用方需自行投放文件。

- [ ] **Step 4: 运行全量测试**

Run: `mvn -pl hms-core test`
Expected: 全部 PASS。

- [ ] **Step 5: 运行 demo-app 测试**

Run: `mvn -pl demo-app -am test`
Expected: 全部 PASS；若 demo-app 测试依赖环境变量，按现有 README 的说明设置后再跑。

- [ ] **Step 6: 提交**

```bash
git add demo-app/src/main/resources README.md
git commit -m "docs: 沙箱配置示例与工具说明"
```

---

## 自审补充

- 每个工具测试文件都要包含「越界路径被拒绝」用例；Task 4-12 的测试清单已逐项列出。
- 任务顺序按依赖排定：Task 13（注册）必须在全部工具类存在之后执行，否则无法编译。
- `ToolValidator` 已提供 `requireString`/`getInt`/`getBoolean`，新工具复用，不重复实现。
- 所有新增工具的 `inputSchema()` 返回合法 JSON 字符串；在实现后用 `new ObjectMapper().readTree(schema)` 做一次冒烟断言（可放在对应测试中）。
- `ToolCallbackAdapter` 会把 `PermissionResult` 的拒绝转成 `Permission denied:` 文本；文件工具不需要自定义 `checkPermission`，风险等级由 `riskLevel()` 声明。
- 沙箱未启用时，`Notification` 仍注册；`ToolConfiguration` 中不要把它放进条件块。
