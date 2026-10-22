package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;
import com.inspirationi.loop.worktree.WorktreeManager;

import java.io.IOException;
import java.util.Map;

/**
 * 退出 git worktree —— 把沙箱的逻辑当前目录恢复到沙箱根，可选删除工作树与其分支。
 * <p>
 * 删除是破坏性操作：有未提交修改时必须显式传 {@code discard_changes=true}。
 */
public class ExitWorktreeTool implements Tool {

    @Override
    public String name() {
        return "ExitWorktree";
    }

    @Override
    public String description() {
        return """
            Leave the active git worktree and return the sandbox's current directory to \
            the sandbox root. action=keep leaves the worktree on disk; action=remove deletes \
            it along with its branch. Removal refuses when the worktree has uncommitted \
            changes unless discard_changes=true is passed.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["keep", "remove"],
                  "description": "keep (default) leaves the worktree on disk; remove deletes it"
                },
                "discard_changes": {
                  "type": "boolean",
                  "description": "Required to remove a worktree with uncommitted changes (default: false)"
                }
              },
              "required": ["action"]
            }""";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        Sandbox sandbox = Sandbox.from(context);
        if (sandbox == null) {
            return "Error: Sandbox is not configured for this session.";
        }
        String action = ToolValidator.getString(input, "action", "keep");
        if (action == null || action.isBlank()) action = "keep";
        if (!"keep".equals(action) && !"remove".equals(action)) {
            return "Error: Unknown action '" + action + "'. Use: keep, remove";
        }
        boolean discardChanges = ToolValidator.getBoolean(input, "discard_changes", false);

        // 与 EnterWorktree 一样从沙箱取：上下文里的状态不跨子 Agent 共享。
        WorktreeManager manager =
                sandbox.shared(WorktreeManager.CONTEXT_KEY, WorktreeManager::new);
        WorktreeManager.WorktreeSession session = manager.getCurrentSession();
        if (session == null) {
            return "Error: This session is not inside a worktree.";
        }

        if ("keep".equals(action)) {
            manager.keepWorktree();
            sandbox.exit();
            return "Left worktree '" + session.slug() + "' (kept at "
                    + sandbox.display(session.worktreePath()) + ", branch "
                    + session.worktreeBranch() + ")";
        }

        if (!discardChanges && manager.hasUncommittedChanges(session.worktreePath())) {
            return "Error: Worktree '" + session.slug() + "' has uncommitted changes. "
                    + "Commit or stash them, or pass discard_changes=true to force removal.";
        }

        boolean removed;
        try {
            removed = manager.cleanupWorktree(discardChanges);
        } catch (IOException | IllegalStateException e) {
            return "Error: " + Tool.describeError(e);
        }
        if (!removed) {
            return "Error: Failed to remove worktree '" + session.slug() + "'.";
        }
        sandbox.exit();
        return "Removed worktree '" + session.slug() + "' (branch " + session.worktreeBranch() + ")";
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🌿 Exit worktree (" + input.getOrDefault("action", "keep") + ")";
    }
}
