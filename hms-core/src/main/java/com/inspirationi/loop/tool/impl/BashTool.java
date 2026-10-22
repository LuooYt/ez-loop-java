package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.permission.DangerousPatterns;
import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;
import com.inspirationi.loop.tool.util.ProcessExecutor;

import java.nio.file.Path;
import java.util.Map;

/**
 * Shell 工具 —— 对应 claude-code/src/tools/bash/BashTool.ts。
 * <p>
 * 在会话沙箱的当前目录执行 shell 命令。危险命令检查复用底座已有的
 * {@link DangerousPatterns#detectShellDanger(String)}，不另建黑名单。
 * <p>
 * <b>Bash 只是缓解，不是安全边界</b>：子进程仍可通过绝对路径或 {@code cd}
 * 访问沙箱外的文件。
 */
public class BashTool implements Tool {

    /** 危险命令被拒时的统一前缀。 */
    private static final String DANGEROUS_PREFIX = "Error: Dangerous command blocked: ";

    private final SandboxProperties.Bash props;

    public BashTool(SandboxProperties.Bash props) {
        this.props = props != null ? props : new SandboxProperties.Bash();
    }

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        String shell = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "cmd.exe" : "sh";
        return """
            Execute a shell command (via %s) in the current directory of the session sandbox. \
            Commands run in a subprocess with timeout protection; shell state does not persist \
            between calls. Prefer the dedicated Read/Write/Edit/Glob/Grep tools when they fit.

            IMPORTANT: Bash 在沙箱目录中执行，但不是安全边界；绝对路径与 cd 仍可访问沙箱外文件。 \
            Destructive commands are blocked, but this is a mitigation, not an isolation guarantee.
            """.formatted(shell);
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "The shell command to execute"
                },
                "timeout_seconds": {
                  "type": "integer",
                  "description": "Timeout in seconds (capped by hms-core.sandbox.bash.timeout-seconds)"
                },
                "description": {
                  "type": "string",
                  "description": "Short human-readable description of what the command does"
                }
              },
              "required": ["command"]
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
        String err = ToolValidator.requireString(input, "command");
        if (err != null) return err;
        String command = (String) input.get("command");

        String danger = DangerousPatterns.detectShellDanger(command);
        if (danger != null) {
            return DANGEROUS_PREFIX + danger;
        }

        int configured = props.getTimeoutSeconds() > 0 ? props.getTimeoutSeconds() : 120;
        int requested = ToolValidator.getInt(input, "timeout_seconds", configured);
        int seconds = Math.max(1, Math.min(requested, configured));

        int maxChars = props.getMaxOutputChars() > 0 ? props.getMaxOutputChars() : 40_000;

        Path cwd = sandbox.currentDir();
        // 字节上限按 4 倍字符上限给（UTF-8 单字符最多 4 字节），保证按字符截断时
        // 需要的那部分一定还在缓冲里，同时仍然给刷屏命令一个硬边界。
        ProcessExecutor.Result result = ProcessExecutor.executeShell(
                command, cwd, seconds * 1000L, maxChars * 4);

        String output = result.output();
        boolean truncated = output.length() > maxChars;
        if (truncated) {
            output = output.substring(0, maxChars);
        }
        output = output.stripTrailing();

        StringBuilder sb = new StringBuilder();
        if (result.timedOut()) {
            sb.append("Error: Command timed out after ").append(seconds)
                    .append("s and was killed.\n");
        }
        if (!output.isEmpty()) {
            sb.append(output).append('\n');
        }
        if (truncated) {
            sb.append("... (output truncated at ").append(maxChars).append(" chars)\n");
        }
        sb.append(result.timedOut() ? "[Exit code: -1 (timed out)]" : "[Exit code: " + result.exitCode() + "]");
        return sb.toString();
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String command = String.valueOf(input.getOrDefault("command", ""));
        return "⚡ " + (command.length() > 60 ? command.substring(0, 60) + "…" : command);
    }
}
