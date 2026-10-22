package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.NotificationSink;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolValidator;
import com.inspirationi.loop.tool.util.ProcessExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面通知工具 —— 对应 claude-code 的 Notification 工具。
 * <p>
 * 优先级：宿主注入的 {@link NotificationSink} → 系统通知命令（可由
 * {@code hms-core.sandbox.notification.system-command} 关闭）→ 明确报错。
 * <p>
 * 系统命令一律以<b>参数列表</b>方式执行、不经过 shell；会进入脚本正文的
 * 标题/正文通过环境变量传递并转义，避免命令注入。
 */
public class NotificationTool implements Tool {

    /** 标题最大长度：过长的标题在系统通知里会被截断，这里先截干净。 */
    private static final int MAX_TITLE_LENGTH = 80;
    /** 系统命令超时（毫秒）。 */
    private static final long COMMAND_TIMEOUT_MS = 10_000;

    private static final Set<String> LEVELS = Set.of("info", "warning", "error");

    private final SandboxProperties.Notification props;

    public NotificationTool(SandboxProperties.Notification props) {
        this.props = props != null ? props : new SandboxProperties.Notification();
    }

    @Override
    public String name() {
        return "Notification";
    }

    @Override
    public String description() {
        return """
            Send a desktop notification to the user (e.g. when a long task finishes). \
            level is one of info, warning, error. Use sparingly — notifications interrupt \
            the user.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "title": {
                  "type": "string",
                  "description": "Notification title (truncated to 80 characters)"
                },
                "message": {
                  "type": "string",
                  "description": "Notification body"
                },
                "level": {
                  "type": "string",
                  "enum": ["info", "warning", "error"],
                  "description": "Severity (default: info)"
                },
                "sound": {
                  "type": "boolean",
                  "description": "Play a sound (default: false)"
                }
              },
              "required": ["title", "message"]
            }""";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.READ_ONLY;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String err = ToolValidator.requireString(input, "title");
        if (err != null) return err;
        err = ToolValidator.requireString(input, "message");
        if (err != null) return err;

        String title = (String) input.get("title");
        String message = (String) input.get("message");
        String level = ToolValidator.getString(input, "level", "info");
        if (!LEVELS.contains(level)) {
            return "Error: Invalid level '" + level + "'. Use: info, warning, error";
        }
        boolean sound = ToolValidator.getBoolean(input, "sound", false);

        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }

        NotificationSink sink = context != null ? context.get(NotificationSink.CONTEXT_KEY) : null;
        if (sink != null) {
            try {
                sink.send(title, message, level, sound);
                return "Notification sent: " + title;
            } catch (RuntimeException e) {
                return "Error: Notification sink failed: " + Tool.describeError(e);
            }
        }

        if (!props.isSystemCommand()) {
            return "Error: No notification sink is available. Inject a NotificationSink "
                    + "or enable hms-core.sandbox.notification.system-command.";
        }

        ProcessExecutor.Result result = sendViaSystemCommand(title, message, level, sound);
        if (result != null && result.isSuccess()) {
            return "Notification sent: " + title;
        }
        String detail = result == null ? "unsupported platform" : result.output();
        return "Error: Failed to send notification: " + detail;
    }

    // ==================== 系统通知命令 ====================

    private ProcessExecutor.Result sendViaSystemCommand(String title, String message,
                                                        String level, boolean sound) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return sendWindows(title, message, sound);
        }
        if (os.contains("mac")) {
            return sendMac(title, message);
        }
        return sendLinux(title, message, level);
    }

    /** Windows：优先 PowerShell 气泡通知，失败回退 {@code msg}。 */
    private ProcessExecutor.Result sendWindows(String title, String message, boolean sound) {
        String script = """
            $ErrorActionPreference='Stop'
            [void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms')
            [void][System.Reflection.Assembly]::LoadWithPartialName('System.Drawing')
            $n = New-Object System.Windows.Forms.NotifyIcon
            $n.Icon = [System.Drawing.SystemIcons]::Information
            $n.Visible = $true
            $n.ShowBalloonTip(8000, $env:HMS_NOTIFY_TITLE, $env:HMS_NOTIFY_MESSAGE, 'None')
            Start-Sleep -Seconds 2
            $n.Dispose()
            """;
        ProcessExecutor.Result result = ProcessExecutor.execute(
                List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script),
                null, COMMAND_TIMEOUT_MS,
                Map.of("HMS_NOTIFY_TITLE", title, "HMS_NOTIFY_MESSAGE", message));
        if (result.isSuccess()) {
            return result;
        }
        // 回退：msg 直接接收参数，不经过 shell
        return ProcessExecutor.execute(
                List.of("msg", "*", title + ": " + message), null, COMMAND_TIMEOUT_MS);
    }

    /** macOS：osascript 的脚本正文需要转义引号与反斜杠。 */
    private ProcessExecutor.Result sendMac(String title, String message) {
        String script = "display notification \"" + escapeAppleScript(message)
                + "\" with title \"" + escapeAppleScript(title) + "\"";
        return ProcessExecutor.execute(List.of("osascript", "-e", script), null, COMMAND_TIMEOUT_MS);
    }

    /** Linux：notify-send，level 映射为 urgency。 */
    private ProcessExecutor.Result sendLinux(String title, String message, String level) {
        String urgency = "error".equals(level) ? "critical" : "normal";
        return ProcessExecutor.execute(
                List.of("notify-send", "--urgency=" + urgency, title, message),
                null, COMMAND_TIMEOUT_MS);
    }

    private static String escapeAppleScript(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔔 " + input.getOrDefault("title", "notification");
    }
}
