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

    /**
     * slug 最终会拼成分支名 {@code worktree-<slug>}，必须同时满足 git ref 规则：
     * {@code ..}、尾随 {@code .}、尾随 {@code .lock} 都会让 {@code git branch} 直接失败。
     */
    @Test
    void rejectsSlugsThatProduceInvalidGitRefs() {
        assertThrows(IllegalArgumentException.class, () -> WorktreeManager.validateSlug("a..b"));
        assertThrows(IllegalArgumentException.class, () -> WorktreeManager.validateSlug("x.lock"));
        assertThrows(IllegalArgumentException.class,
                () -> WorktreeManager.validateSlug("trailing-dot."));
        WorktreeManager.validateSlug("release/1.0");
    }

    /** Windows 绝对路径（盘符、UNC）与 Unix 绝对路径都不能当作符号链接目标。 */
    @Test
    void rejectsAbsoluteAndTraversalSymlinkTargets() {
        assertFalse(WorktreeManager.isSafeSymlinkDir("/etc"));
        assertFalse(WorktreeManager.isSafeSymlinkDir("C:\\Windows"));
        assertFalse(WorktreeManager.isSafeSymlinkDir("\\\\server\\share"));
        assertFalse(WorktreeManager.isSafeSymlinkDir("../outside"));
        assertFalse(WorktreeManager.isSafeSymlinkDir("a/../../outside"));
        assertFalse(WorktreeManager.isSafeSymlinkDir(""));

        assertTrue(WorktreeManager.isSafeSymlinkDir("node_modules"));
        assertTrue(WorktreeManager.isSafeSymlinkDir("build/dist"));
    }

    @Test
    void createsAndRemovesWorktreeInsideSandbox(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git not installed");
        Path repo = initRepo(temp);

        WorktreeManager manager = new WorktreeManager();
        var result = manager.createOrResume("w1", repo, null);

        assertTrue(Files.isDirectory(result.worktreePath()));
        assertTrue(result.worktreePath().startsWith(repo), "工作树必须位于沙箱仓库内");
        assertEquals("worktrees", result.worktreePath().getParent().getFileName().toString());
        assertTrue(manager.removeWorktree(result.worktreePath(), result.worktreeBranch(), repo));
        assertFalse(Files.exists(result.worktreePath()));
    }

    /**
     * 快速恢复路径不能只看「目录存在」：普通目录（甚至链接到仓库外的目录）
     * 都会被当作已有 worktree 直接放行，后续所有 git 操作都在错误的位置执行。
     */
    @Test
    void refusesToResumeDirectoryThatIsNotAWorktree(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git not installed");
        Path repo = initRepo(temp);
        Files.createDirectories(repo.resolve("worktrees/impostor"));

        WorktreeManager manager = new WorktreeManager();
        assertThrows(java.io.IOException.class,
                () -> manager.createOrResume("impostor", repo, null),
                "已存在但不是 git worktree 的目录不得当作可恢复的 worktree");
    }

    /** 真正的 worktree 仍然要走快速恢复，不能每次重建。 */
    @Test
    void resumesExistingRealWorktree(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git not installed");
        Path repo = initRepo(temp);

        WorktreeManager manager = new WorktreeManager();
        var first = manager.createOrResume("w3", repo, null);
        var second = manager.createOrResume("w3", repo, null);

        assertFalse(first.existed());
        assertTrue(second.existed(), "已存在的 worktree 必须走恢复路径");
        assertEquals(first.worktreePath(), second.worktreePath());
    }

    @Test
    void enterTracksOriginalCwd(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git not installed");
        Path repo = initRepo(temp);

        WorktreeManager manager = new WorktreeManager();
        var session = manager.enterWorktree("w2", repo, null, repo);

        assertEquals(repo, session.originalCwd());
        assertNotNull(manager.getCurrentSession());
        assertTrue(manager.cleanupWorktree(true));
        assertNull(manager.getCurrentSession());
    }

    /**
     * 清理「别处的」过期 worktree 不能把当前会话一起忘掉。
     * <p>
     * removeWorktree 原先无条件清空 currentSession：定时清理器删掉一个无关的
     * worktree，当前会话就丢了 —— 此后 cleanupWorktree() 变成空操作（真正在用的
     * 工作树没人收尾），EnterWorktree 还能再进一次，两套状态互相覆盖。
     */
    @Test
    void removingAnotherWorktreeKeepsCurrentSession(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git not installed");
        Path repo = initRepo(temp);
        WorktreeManager manager = new WorktreeManager();

        var active = manager.enterWorktree("active", repo, null, repo);
        var stale = manager.createOrResume("stale", repo, null);

        assertTrue(manager.removeWorktree(stale.worktreePath(), stale.worktreeBranch(), repo));

        assertNotNull(manager.getCurrentSession(), "清理别处的 worktree 不应清空当前会话");
        assertEquals(active.worktreePath(), manager.getCurrentSession().worktreePath());

        // 当前会话仍然可以正常收尾
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

    /**
     * 子 Agent 拿到的是各自的子上下文，写入只落在本地。WorktreeManager 若放在
     * ToolContext 上，A 进入的工作树 B 看不到，B 调 ExitWorktree 会报「不在工作树里」。
     * 状态必须挂在沙箱上 —— 那是同一会话真正共享的对象。
     */
    @Test
    void worktreeStateIsSharedAcrossChildContexts(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git not installed");
        Path repo = initRepo(temp);
        Sandbox sandbox = new Sandbox(repo);

        ToolContext sessionCtx = ToolContext.defaultContext();
        sessionCtx.set(Sandbox.STATE_KEY, sandbox);
        ToolContext agentA = ToolContext.childOf(sessionCtx);
        ToolContext agentB = ToolContext.childOf(sessionCtx);

        String entered = new EnterWorktreeTool().execute(Map.of("name", "w-split"), agentA);
        assertFalse(entered.startsWith("Error:"), entered);

        String exited = new ExitWorktreeTool().execute(Map.of("action", "keep"), agentB);
        assertFalse(exited.startsWith("Error:"),
                "另一个上下文必须看到同一份工作树状态，实际: " + exited);
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
