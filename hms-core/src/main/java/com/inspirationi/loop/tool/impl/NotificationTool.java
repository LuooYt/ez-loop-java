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

        // 超时必须单独报：它的 exitCode 同样是 -1，若和「服务不存在」混在一起，
        // 模型会被告知「环境不支持，别重试」，而实际可能是通知服务卡住了。
        if (result.timedOut()) {
            return "Error: Notification command timed out after " + COMMAND_TIMEOUT_MS
                    + " ms — the desktop notification service did not respond.";
        }

        // 通知命令根本起不来（容器里没有 notify-send、无 GUI 的服务器没有 powershell）：
        // 这是环境限制，不是调用错误。回 Error 会让模型反复重试，再把它写进「失败清单」。
        String unavailable = unavailableReason(result);
        if (unavailable != null) {
            return "Notification skipped: no desktop notification service in this environment ("
                    + unavailable + "). The task continues — do not retry.";
        }

        if (result.isSuccess()) {
            return "Notification sent: " + title;
        }
        return "Error: Failed to send notification: " + result.output();
    }

    /**
     * 判断通知命令是否「根本没能启动」。
     * <p>
     * {@code exitCode == -1} 在 {@link ProcessExecutor} 里表示三种截然不同的事：
     * 启动失败（{@code IOException}）、超时（{@code timedOut}）、线程被中断。
     * 只有第一种是「这个环境没有通知服务」，其余两种是执行失败 —— 混为一谈会让
     * 模型得到「别重试」的错误结论。超时由调用方先行判断，这里再看输出前缀。
     *
     * @return 为 null 表示命令确实执行过（成功或失败都算）；否则返回不可用的原因
     */
    static String unavailableReason(ProcessExecutor.Result result) {
        if (result == null) {
            return "unsupported platform";
        }
        if (result.timedOut()) {
            return null;
        }
        return result.exitCode() == -1 && result.output() != null
                && result.output().startsWith("IOException") ? result.output() : null;
    }

    // ==================== 系统通知命令 ====================

    private ProcessExecutor.Result sendViaSystemCommand(String title, String message,
                                                        String level, boolean sound) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return sendWindows(title, message, sound);
        }
        if (os.contains("mac")) {
            return sendMac(title, message, sound);
        }
        return sendLinux(title, message, level, sound);
    }

    /** Windows：优先 PowerShell 气泡通知，失败回退 {@code msg}。 */
    private ProcessExecutor.Result sendWindows(String title, String message, boolean sound) {
        String script = windowsScript(sound);
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

    /** Windows 通知脚本；sound 决定是否额外播放系统提示音。 */
    static String windowsScript(boolean sound) {
        StringBuilder script = new StringBuilder("""
            $ErrorActionPreference='Stop'
            [void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms')
            [void][System.Reflection.Assembly]::LoadWithPartialName('System.Drawing')
            $n = New-Object System.Windows.Forms.NotifyIcon
            $n.Icon = [System.Drawing.SystemIcons]::Information
            $n.Visible = $true
            $n.ShowBalloonTip(8000, $env:HMS_NOTIFY_TITLE, $env:HMS_NOTIFY_MESSAGE, 'None')
            """);
        if (sound) {
            script.append("[System.Media.SystemSounds]::Asterisk.Play()\n");
        }
        script.append("""
            Start-Sleep -Seconds 2
            $n.Dispose()
            """);
        return script.toString();
    }

    /** macOS：osascript 的脚本正文需要转义引号与反斜杠。 */
    private ProcessExecutor.Result sendMac(String title, String message, boolean sound) {
        String script = macScript(title, message, sound);
        return ProcessExecutor.execute(List.of("osascript", "-e", script), null, COMMAND_TIMEOUT_MS);
    }

    static String macScript(String title, String message, boolean sound) {
        String script = "display notification \"" + escapeAppleScript(message)
                + "\" with title \"" + escapeAppleScript(title) + "\"";
        return sound ? script + " sound name \"default\"" : script;
    }

    /** Linux：notify-send，level 映射为 urgency。 */
    private ProcessExecutor.Result sendLinux(String title, String message, String level, boolean sound) {
        return ProcessExecutor.execute(linuxCommand(title, message, level, sound),
                null, COMMAND_TIMEOUT_MS);
    }

    static List<String> linuxCommand(String title, String message, String level, boolean sound) {
        String urgency = "error".equals(level) ? "critical" : "normal";
        List<String> command = new java.util.ArrayList<>(List.of("notify-send", "--urgency=" + urgency));
        if (sound) {
            // freedesktop 通知规范的声音 hint；由通知守护进程决定是否真的播放
            command.add("--hint=string:sound-name:message-new-instant");
        }
        command.add(title);
        command.add(message);
        return command;
    }

    private static String escapeAppleScript(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔔 " + input.getOrDefault("title", "notification");
    }
}
