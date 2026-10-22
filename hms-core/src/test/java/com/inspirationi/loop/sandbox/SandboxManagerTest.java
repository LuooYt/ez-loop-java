package com.inspirationi.loop.sandbox;

import org.junit.jupiter.api.Assumptions;
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

    /**
     * Windows junction（mklink /J，无需管理员）在 NIO 里 {@code isSymbolicLink} 为 false，
     * {@code walkFileTree} 会进入它。若不按 realPath 复核，会话销毁会把 junction
     * 目标目录里的文件一起删掉 —— 那是沙箱之外的数据。
     */
    @Test
    void destroyNeverFollowsDirectoryLinks() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase().contains("win"),
                "junction 仅 Windows 可创建");

        Path victim = Files.createDirectories(temp.resolve("victim"));
        Files.writeString(victim.resolve("keep.txt"), "important");

        SandboxManager manager = new SandboxManager(props(true));
        Sandbox sandbox = manager.create("session-link");
        Path link = sandbox.root().resolve("linked");
        Process linkProcess = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), victim.toString())
                .redirectErrorStream(true).start();
        linkProcess.getInputStream().readAllBytes();
        Assumptions.assumeTrue(linkProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                && linkProcess.exitValue() == 0, "无法创建 junction，跳过");

        manager.destroy("session-link");

        assertTrue(Files.exists(victim.resolve("keep.txt")),
                "删除会话沙箱时不得穿过 junction 删除沙箱外的文件");
    }

    @Test
    void reportsDisabledWhenRootMissing() {
        SandboxProperties p = new SandboxProperties();
        p.setEnabled(true);
        assertFalse(new SandboxManager(p).enabled());
    }

    @Test
    void createsRootDirectoryOnStartup() {
        SandboxProperties p = new SandboxProperties();
        p.setEnabled(true);
        p.setRoot(temp.resolve("fresh-root"));
        SandboxManager manager = new SandboxManager(p);
        assertTrue(Files.isDirectory(temp.resolve("fresh-root")),
                "启动时应创建沙箱根目录");
        assertTrue(manager.enabled());
    }
}
