package com.inspirationi.loop.worktree;

import com.inspirationi.loop.tool.util.ProcessExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Git Worktree 生命周期管理 —— 对应 claude-code/src/utils/worktree.ts。
 * <p>
 * 与本项目沙箱的约定：<b>沙箱根目录本身就是 git 仓库</b>，工作树统一落在
 * {@code <仓库根>/worktrees/<slug>} 下，因此不会跑到沙箱之外。原参考实现把它们
 * 放在 {@code .claude/worktrees} 下，这里去掉了 {@code .claude} 这一层。
 * <p>
 * git 调用统一走 {@link ProcessExecutor}：它用虚拟线程读输出、超时后杀整棵进程树，
 * 避免参考实现「先读完 stdout 再读 stderr」在 stderr 撑满管道时的死锁。
 */
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    /** 上下文键 —— 管理器实例存放在会话 {@code ToolContext} 上。 */
    public static final String CONTEXT_KEY = "WORKTREE_MANAGER";

    /** Slug 验证：每个段只允许字母数字和 ._- */
    private static final Pattern VALID_SLUG_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final int MAX_SLUG_LENGTH = 64;
    private static final String WORKTREE_BRANCH_PREFIX = "worktree-";
    private static final String WORKTREE_DIR_NAME = "worktrees";

    /** git 命令超时（毫秒）。 */
    private static final long GIT_TIMEOUT_MS = 30_000;

    /** 当前活跃的 worktree 会话（每个实例最多一个） */
    private volatile WorktreeSession currentSession;

    // ==================== Worktree 会话数据 ====================

    public record WorktreeSession(
            String slug,
            Path worktreePath,
            String worktreeBranch,
            String headCommit,
            Path originalCwd,
            Path gitRoot,
            Instant createdAt
    ) {}

    public record WorktreeCreateResult(
            Path worktreePath,
            String worktreeBranch,
            String headCommit,
            Path gitRoot,
            boolean existed
    ) {}

    // ==================== Slug 验证 ====================

    /**
     * 验证 worktree slug 格式和安全性。
     * <p>
     * 规则：最大 64 字符；每段只允许 {@code [a-zA-Z0-9._-]}；不允许 {@code .} / {@code ..}
     * 段（防路径遍历）；不允许前导/尾随 {@code /}。
     *
     * @throws IllegalArgumentException 验证失败时
     */
    public static void validateSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Worktree slug cannot be empty");
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException(
                    "Worktree slug too long (max " + MAX_SLUG_LENGTH + "): " + slug.length());
        }
        if (slug.startsWith("/") || slug.endsWith("/")) {
            throw new IllegalArgumentException("Worktree slug cannot start/end with /");
        }

        String[] segments = slug.split("/");
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Worktree slug has empty segment");
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Worktree slug cannot contain . or .. segments");
            }
            if (!VALID_SLUG_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(
                        "Invalid characters in slug segment '" + segment
                                + "': only [a-zA-Z0-9._-] allowed");
            }
        }
    }

    /** 将 slug 中的 / 映射为 +，防止目录/文件同名冲突。 */
    public static String flattenSlug(String slug) {
        return slug.replace('/', '+');
    }

    /** 从 slug 生成分支名。 */
    public static String branchName(String slug) {
        return WORKTREE_BRANCH_PREFIX + flattenSlug(slug);
    }

    // ==================== Worktree 创建 ====================

    /**
     * 为会话创建或恢复 worktree。
     *
     * @param slug       worktree 名称
     * @param gitRoot    git 仓库根目录（沙箱根）
     * @param baseBranch 基准分支（null 时使用当前 HEAD）
     */
    public WorktreeCreateResult createOrResume(String slug, Path gitRoot, String baseBranch)
            throws IOException {
        validateSlug(slug);

        String flat = flattenSlug(slug);
        String branch = branchName(slug);
        Path worktreeDir = gitRoot.resolve(WORKTREE_DIR_NAME).resolve(flat);

        // 快速恢复：已存在则直接返回。但「目录存在」不等于「worktree 存在」：
        // 普通目录或指向仓库外的链接也会命中这里，之后所有 git 操作都跑在错误的位置。
        if (Files.isDirectory(worktreeDir)) {
            Path realDir;
            try {
                realDir = worktreeDir.toRealPath();
            } catch (IOException e) {
                throw new IOException("Cannot resolve worktree path for '" + slug + "'", e);
            }
            if (!realDir.startsWith(gitRoot.toRealPath())) {
                throw new IOException("Worktree path escapes the repository: " + worktreeDir);
            }
            if (!Files.exists(worktreeDir.resolve(".git"))) {
                throw new IOException("Directory exists but is not a git worktree: " + worktreeDir);
            }
            String headCommit = readWorktreeHead(worktreeDir);
            log.info("Resuming existing worktree: {} at {}", slug, worktreeDir);
            try {
                Files.setLastModifiedTime(worktreeDir, FileTime.from(Instant.now()));
            } catch (IOException ignored) {
                // mtime 刷新失败不影响使用
            }
            return new WorktreeCreateResult(worktreeDir, branch, headCommit, gitRoot, true);
        }

        Files.createDirectories(worktreeDir.getParent());

        if (baseBranch == null || baseBranch.isBlank()) {
            baseBranch = execGit(gitRoot, "rev-parse", "--abbrev-ref", "HEAD").trim();
        }

        execGit(gitRoot, "worktree", "add", "-B", branch, worktreeDir.toString(), baseBranch);
        log.info("Created worktree: {} → {} (branch: {})", slug, worktreeDir, branch);

        return new WorktreeCreateResult(worktreeDir, branch, readWorktreeHead(worktreeDir),
                gitRoot, false);
    }

    /** 为 Agent 创建轻量级 worktree（不修改全局状态）。 */
    public WorktreeCreateResult createAgentWorktree(String slug, Path gitRoot) throws IOException {
        validateSlug(slug);
        return createOrResume(slug, gitRoot, null);
    }

    // ==================== Worktree 退出 ====================

    /** 退出 worktree 但保留在磁盘上。 */
    public void keepWorktree() {
        if (currentSession == null) return;
        log.info("Keeping worktree: {} at {}", currentSession.slug, currentSession.worktreePath);
        currentSession = null;
    }

    /**
     * 退出 worktree 并清理（删除 worktree + 分支）。
     *
     * @param discardChanges true 时强制删除（即使有未提交的修改）
     */
    public boolean cleanupWorktree(boolean discardChanges) throws IOException {
        if (currentSession == null) return false;

        WorktreeSession session = currentSession;
        if (!discardChanges && hasUncommittedChanges(session.worktreePath)) {
            throw new IllegalStateException(
                    "Worktree has uncommitted changes. Use discard_changes=true to force removal.");
        }
        return removeWorktree(session.worktreePath, session.worktreeBranch, session.gitRoot);
    }

    /** 删除指定的 worktree 和分支。 */
    public boolean removeWorktree(Path worktreePath, String branch, Path gitRoot) {
        try {
            execGit(gitRoot, "worktree", "remove", "--force", worktreePath.toString());

            // 等待 git 锁释放
            Thread.sleep(100);

            if (branch != null) {
                try {
                    execGit(gitRoot, "branch", "-D", branch);
                } catch (IOException e) {
                    log.debug("Branch deletion failed (may not exist): {}", e.getMessage());
                }
            }

            currentSession = null;
            log.info("Removed worktree: {} (branch: {})", worktreePath, branch);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("Failed to remove worktree: {}", worktreePath, e);
            return false;
        }
    }

    /** 删除 Agent 的轻量级 worktree。 */
    public boolean removeAgentWorktree(Path worktreePath, String branch, Path gitRoot) {
        return removeWorktree(worktreePath, branch, gitRoot);
    }

    // ==================== 会话管理 ====================

    /**
     * 进入 worktree（设置为当前会话）。
     *
     * @param originalCwd 进入前的工作目录 —— 由调用方传入，这里不读 {@code user.dir}
     *                    （服务端进程的 user.dir 与本会话沙箱无关）
     */
    public WorktreeSession enterWorktree(String slug, Path gitRoot, String baseBranch,
                                         Path originalCwd) throws IOException {
        if (currentSession != null) {
            throw new IllegalStateException("Already in a worktree session: " + currentSession.slug);
        }

        WorktreeCreateResult result = createOrResume(slug, gitRoot, baseBranch);
        currentSession = new WorktreeSession(
                slug, result.worktreePath(), result.worktreeBranch(),
                result.headCommit(), originalCwd, gitRoot, Instant.now());
        return currentSession;
    }

    /** 恢复之前的 worktree 会话（--resume 时使用）。 */
    public void restoreSession(WorktreeSession session) {
        this.currentSession = session;
    }

    public WorktreeSession getCurrentSession() {
        return currentSession;
    }

    // ==================== Symlink 大目录 ====================

    /** 为大型目录创建符号链接（如 node_modules、dist）；目标仍在沙箱仓库内。 */
    public void symlinkDirectories(Path repoRoot, Path worktreePath, List<String> dirs) {
        if (dirs == null || dirs.isEmpty()) return;

        for (String dir : dirs) {
            if (dir.contains("..") || dir.startsWith("/")) {
                log.warn("Skipping unsafe symlink directory: {}", dir);
                continue;
            }

            Path source = repoRoot.resolve(dir);
            Path target = worktreePath.resolve(dir);
            try {
                if (!Files.isDirectory(source) || Files.exists(target)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.createSymbolicLink(target, source);
                log.info("Symlinked: {} → {}", target, source);
            } catch (IOException e) {
                log.warn("Failed to create symlink {} → {}: {}", target, source, e.getMessage());
            }
        }
    }

    // ==================== 过期清理（GC） ====================

    /** Agent worktree 的临时命名模式 */
    private static final Pattern EPHEMERAL_PATTERN = Pattern.compile(
            "^(agent-a[0-9a-f]{7}|wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+|bridge-.+|job-.+-[0-9a-f]{8})$");

    /**
     * 清理过期的 Agent worktree。
     *
     * @param cutoff mtime 早于此时间的才会被清理
     * @return 清理的 worktree 数量
     */
    public int cleanupStaleWorktrees(Path gitRoot, Instant cutoff) {
        Path worktreeBase = gitRoot.resolve(WORKTREE_DIR_NAME);
        if (!Files.isDirectory(worktreeBase)) return 0;

        int cleaned = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(worktreeBase)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) continue;

                String name = entry.getFileName().toString();
                if (!EPHEMERAL_PATTERN.matcher(name).matches()) continue;
                if (currentSession != null && entry.equals(currentSession.worktreePath)) continue;

                Instant mtime = Files.getLastModifiedTime(entry).toInstant();
                if (mtime.isAfter(cutoff)) continue;
                if (hasUncommittedChanges(entry)) {
                    log.debug("Skipping stale worktree with changes: {}", name);
                    continue;
                }

                if (removeWorktree(entry, WORKTREE_BRANCH_PREFIX + name, gitRoot)) {
                    cleaned++;
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan worktree directory: {}", worktreeBase, e);
        }

        if (cleaned > 0) {
            log.info("Cleaned up {} stale agent worktrees", cleaned);
        }
        return cleaned;
    }

    // ==================== Git 工具方法 ====================

    /** 读取 worktree 的 HEAD commit SHA。 */
    private String readWorktreeHead(Path worktreePath) {
        try {
            Path gitFile = worktreePath.resolve(".git");
            if (Files.isRegularFile(gitFile)) {
                String content = Files.readString(gitFile).trim();
                if (content.startsWith("gitdir:")) {
                    Path gitDir = Path.of(content.substring("gitdir:".length()).trim());
                    if (!gitDir.isAbsolute()) {
                        gitDir = worktreePath.resolve(gitDir);
                    }
                    Path headFile = gitDir.resolve("HEAD");
                    if (Files.isRegularFile(headFile)) {
                        String headContent = Files.readString(headFile).trim();
                        if (headContent.startsWith("ref:")) {
                            String ref = headContent.substring(4).trim();
                            Path refFile = gitDir.resolve(ref);
                            if (Files.isRegularFile(refFile)) {
                                return Files.readString(refFile).trim();
                            }
                        } else {
                            return headContent;
                        }
                    }
                }
            }
            return execGit(worktreePath, "rev-parse", "HEAD").trim();
        } catch (Exception e) {
            log.debug("Failed to read HEAD for worktree {}: {}", worktreePath, e.getMessage());
            return null;
        }
    }

    /** 检查 worktree 是否有未提交的修改。git 失败时 fail-closed。 */
    public boolean hasUncommittedChanges(Path worktreePath) {
        try {
            return !execGit(worktreePath, "status", "--porcelain").isBlank();
        } catch (IOException e) {
            return true;
        }
    }

    /** 检测 git 仓库根目录；不是 git 仓库时返回 {@code null}。 */
    public static Path findGitRoot(Path dir) {
        try {
            return Path.of(execGit(dir, "rev-parse", "--show-toplevel").trim());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 执行 git 命令并返回 stdout；非零退出码转成 IOException。 */
    private static String execGit(Path workDir, String... args) throws IOException {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("git");
        command.addAll(List.of(args));

        ProcessExecutor.Result result = ProcessExecutor.execute(command, workDir, GIT_TIMEOUT_MS,
                java.util.Map.of("GIT_TERMINAL_PROMPT", "0"));
        if (!result.isSuccess()) {
            throw new IOException("Git command failed (exit " + result.exitCode() + "): "
                    + String.join(" ", args) + "\n" + result.output());
        }
        return result.output().trim();
    }
}
