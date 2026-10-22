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
        Path repo = initRepo(temp);

        WorktreeManager manager = new WorktreeManager();
        var result = manager.createOrResume("w1", repo, null);

        assertTrue(Files.isDirectory(result.worktreePath()));
        assertTrue(result.worktreePath().startsWith(repo), "工作树必须位于沙箱仓库内");
        assertEquals("worktrees", result.worktreePath().getParent().getFileName().toString());
        assertTrue(manager.removeWorktree(result.worktreePath(), result.worktreeBranch(), repo));
        assertFalse(Files.exists(result.worktreePath()));
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
