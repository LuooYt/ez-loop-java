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

    /**
     * 词法前缀不足以判断 enter 的目标：root 下的目录链接（Windows junction 在 NIO 里
     * 不是 symbolicLink）实际可以指向沙箱外。enter 必须按 realPath 复核。
     */
    @Test
    void rejectsEnteringDirectoryLinkPointingOutside() throws IOException {
        Sandbox sandbox = newSandbox();
        Path outside = Files.createDirectories(temp.resolve("outside-enter"));
        Path link = sandbox.root().resolve("linked-enter");
        if (!tryCreateDirectoryLink(link, outside)) {
            Assumptions.assumeTrue(false, "directory links not supported");
        }

        assertThrows(SandboxViolationException.class, () -> sandbox.enter(link));
        assertEquals(sandbox.root(), sandbox.currentDir(), "拒绝后当前目录必须保持原样");
    }

    private static boolean tryCreateDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 无权限创符号链接时退回 junction（无需管理员权限）
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void switchesCurrentDirectoryForWorktree() throws IOException {
        Sandbox sandbox = newSandbox();
        Path worktree = Files.createDirectories(sandbox.root().resolve("worktrees/w1"));
        sandbox.enter(worktree);
        assertEquals(worktree.toRealPath(), sandbox.resolve("a.txt").getParent().toRealPath());
        // 从 root/worktrees/w1 出发需要三层才能离开沙箱根
        assertThrows(SandboxViolationException.class, () -> sandbox.resolve("../../../escape.txt"));
        sandbox.exit();
        assertTrue(sandbox.resolve("a.txt").startsWith(sandbox.root()));
    }
}
