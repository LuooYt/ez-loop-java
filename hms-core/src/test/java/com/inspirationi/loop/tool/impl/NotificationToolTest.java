package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.NotificationSink;
import com.inspirationi.loop.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NotificationToolTest {

    private static SandboxProperties.Notification props(boolean systemCommand) {
        SandboxProperties.Notification props = new SandboxProperties.Notification();
        props.setSystemCommand(systemCommand);
        return props;
    }

    @Test
    void delegatesToInjectedSink() {
        ToolContext ctx = ToolContext.defaultContext();
        AtomicReference<String> title = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        AtomicReference<String> level = new AtomicReference<>();
        NotificationSink sink = (t, m, l, sound) -> {
            title.set(t);
            message.set(m);
            level.set(l);
        };
        ctx.set(NotificationSink.CONTEXT_KEY, sink);

        String result = new NotificationTool(props(false)).execute(
                Map.of("title", "构建完成", "message", "全部测试通过", "level", "warning"), ctx);

        assertFalse(result.startsWith("Error:"), result);
        assertEquals("构建完成", title.get());
        assertEquals("全部测试通过", message.get());
        assertEquals("warning", level.get());
    }

    @Test
    void failsWhenNoSinkAndSystemCommandDisabled() {
        String result = new NotificationTool(props(false)).execute(
                Map.of("title", "t", "message", "m"), ToolContext.defaultContext());

        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No notification sink"));
    }

    @Test
    void validatesRequiredFields() {
        NotificationTool tool = new NotificationTool(props(false));
        assertTrue(tool.execute(Map.of("message", "m"), ToolContext.defaultContext())
                .startsWith("Error:"));
        assertTrue(tool.execute(Map.of("title", "t"), ToolContext.defaultContext())
                .startsWith("Error:"));
    }

    /** sound=true 必须真正传给系统通知命令，而不是只收下参数就丢掉。 */
    @Test
    void systemCommandHonoursSoundFlag() {
        assertTrue(NotificationTool.macScript("t", "m", true).contains("sound name"),
                "macOS 开启声音时必须带 sound name");
        assertFalse(NotificationTool.macScript("t", "m", false).contains("sound name"),
                "macOS 未开启声音时不得播放声音");

        assertTrue(String.join(" ", NotificationTool.linuxCommand("t", "m", "info", true))
                .contains("sound"), "Linux 开启声音时必须带 sound hint");
        assertFalse(String.join(" ", NotificationTool.linuxCommand("t", "m", "info", false))
                .contains("sound"), "Linux 未开启声音时不得带 sound hint");

        assertTrue(NotificationTool.windowsScript(true).contains("SystemSounds"),
                "Windows 开启声音时必须播放提示音");
        assertFalse(NotificationTool.windowsScript(false).contains("SystemSounds"),
                "Windows 未开启声音时不得播放提示音");
    }

    @Test
    void truncatesLongTitleToEightyChars() {
        ToolContext ctx = ToolContext.defaultContext();
        AtomicReference<String> sent = new AtomicReference<>();
        NotificationSink sink = (t, m, l, sound) -> sent.set(t);
        ctx.set(NotificationSink.CONTEXT_KEY, sink);

        new NotificationTool(props(false)).execute(
                Map.of("title", "x".repeat(200), "message", "m"), ctx);

        assertEquals(80, sent.get().length(), "title 必须截断到 80 字符");
    }
}
