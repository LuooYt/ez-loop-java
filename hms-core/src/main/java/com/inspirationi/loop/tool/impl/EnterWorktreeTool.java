package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxViolationException;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;
import com.inspirationi.loop.worktree.WorktreeManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 进入 git worktree —— 在沙箱仓库内创建（或恢复）一个工作树，并把沙箱的
 * 逻辑当前目录切换过去。
 * <p>
 * 沙箱根必须<b>本身</b>是 git 仓库根：只认根目录那一层，避免模型通过
 * 任意子目录里的 git 仓库把工作树写到沙箱之外。
 */
public class EnterWorktreeTool implements Tool {

    @Override
    public String name() {
        return "EnterWorktree";
    }

    @Override
    public String description() {
        return """
            Create (or resume) a git worktree inside the session sandbox and switch the \
            sandbox's current directory into it. The sandbox root must itself be a git \
            repository. Worktrees live under <sandbox>/worktrees/<name>. Only one worktree \
            can be active at a time — call ExitWorktree before entering another.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Worktree name; segments may contain [a-zA-Z0-9._-] and be separated by /"
                },
                "base_branch": {
                  "type": "string",
                  "description": "Branch or commit to base the worktree on (default: current HEAD)"
                }
              },
              "required": ["name"]
            }""";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        Sandbox sandbox = Sandbox.from(context);
        if (sandbox == null) {
            return "Error: Sandbox is not configured for this session.";
        }
        String err = ToolValidator.requireString(input, "name");
        if (err != null) return err;
        String name = (String) input.get("name");
        String baseBranch = ToolValidator.getString(input, "base_branch", null);

        try {
            WorktreeManager.validateSlug(name);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        Path gitRoot = WorktreeManager.findGitRoot(sandbox.root());
        if (gitRoot == null) {
            return "Error: The session sandbox is not a git repository. "
                    + "Run `git init` in the sandbox first.";
        }
        try {
            if (!gitRoot.toRealPath().equals(sandbox.root())) {
                return "Error: Worktrees can only be created from a git repository "
                        + "at the sandbox root.";
            }
        } catch (IOException e) {
            return "Error: " + Tool.describeError(e);
        }

        // 挂在沙箱上而不是上下文：子 Agent 的写入只落在自己的子上下文里，
        // 放上下文会让「进入」与「退出」看到两个不同的管理器。
        WorktreeManager manager =
                sandbox.shared(WorktreeManager.CONTEXT_KEY, WorktreeManager::new);
        if (manager.getCurrentSession() != null) {
            return "Error: This session is already inside a worktree. Call ExitWorktree first.";
        }

        Path originalCwd = sandbox.currentDir();
        try {
            WorktreeManager.WorktreeSession session =
                    manager.enterWorktree(name, gitRoot, baseBranch, originalCwd);
            sandbox.enter(session.worktreePath());
            return "Entered worktree '" + session.slug() + "'\n"
                    + "  path: " + sandbox.display(session.worktreePath()) + "\n"
                    + "  branch: " + session.worktreeBranch() + "\n"
                    + "  head: " + (session.headCommit() != null
                            ? session.headCommit().substring(0, Math.min(8, session.headCommit().length()))
                            : "unknown");
        } catch (SandboxViolationException e) {
            manager.keepWorktree();
            return "Error: " + e.getMessage();
        } catch (IOException | IllegalStateException e) {
            return "Error: " + Tool.describeError(e);
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🌿 Enter worktree: " + input.getOrDefault("name", "");
    }
}
