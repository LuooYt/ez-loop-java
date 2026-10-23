package com.inspirationi.loop.config;

import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.sandbox.Sandbox;
import com.inspirationi.loop.sandbox.SandboxManager;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SandboxToolRegistrationTest {

    @TempDir Path temp;

    /** 建一个可用沙箱的配置与配套管理器。 */
    private SandboxProperties usableProps() throws IOException {
        SandboxProperties props = new SandboxProperties();
        props.setEnabled(true);
        props.setRoot(temp.resolve("root"));
        Files.createDirectories(props.getRoot());
        return props;
    }

    @Test
    void doesNotRegisterPathToolsWhenSandboxDisabled() {
        ToolRegistry registry = new ToolRegistry();
        SandboxProperties props = new SandboxProperties(); // enabled=false
        ToolConfiguration.registerSandboxTools(registry, new SandboxManager(props), props,
                new PermissionRuleEngine(new PermissionSettings()));
        assertFalse(registry.getToolNames().contains("Read"));
        assertFalse(registry.getToolNames().contains("Bash"));
    }

    @Test
    void registersPathToolsWhenSandboxEnabled() throws IOException {
        SandboxProperties props = usableProps();
        ToolRegistry registry = new ToolRegistry();
        ToolConfiguration.registerSandboxTools(registry, new SandboxManager(props), props,
                new PermissionRuleEngine(new PermissionSettings()));
        assertTrue(registry.getToolNames().containsAll(
                Set.of("Read", "Write", "Edit", "Glob", "Grep", "ListFiles", "Bash",
                        "NotebookEdit", "EnterWorktree", "ExitWorktree")));
        assertFalse(registry.getToolNames().contains("LSP")); // 未配置服务器
    }

    /**
     * 配置写对了、但根目录实际建不出来（路径被普通文件占住、无权限等）时，
     * 判断必须依据 {@link SandboxManager#enabled()} 的真实结果，而不是配置本身。
     * 否则注册出去的每个工具在运行时都只会返回「沙箱未配置」。
     */
    @Test
    void doesNotRegisterPathToolsWhenRootCannotBeCreated() throws IOException {
        Path blocker = temp.resolve("blocker");
        Files.writeString(blocker, "not a directory");

        SandboxProperties props = new SandboxProperties();
        props.setEnabled(true);
        props.setRoot(blocker.resolve("root"));

        SandboxManager manager = new SandboxManager(props);
        assertFalse(manager.enabled(), "根目录建不出来时管理器必须报告未启用");

        ToolRegistry registry = new ToolRegistry();
        ToolConfiguration.registerSandboxTools(registry, manager, props,
                new PermissionRuleEngine(new PermissionSettings()));

        assertFalse(registry.getToolNames().contains("Read"),
                "沙箱实际不可用时不得注册路径类工具");
        assertFalse(registry.getToolNames().contains("Bash"));
    }

    /**
     * Edit 的上限必须来自配置。此前注册时用的是无参构造器，{@code max-file-bytes}
     * 对编辑工具完全无效 —— 配了上限也不生效。
     */
    @Test
    void editToolHonoursConfiguredMaxFileBytes() throws IOException {
        SandboxProperties props = usableProps();
        props.setMaxFileBytes(16);

        SandboxManager manager = new SandboxManager(props);
        ToolRegistry registry = new ToolRegistry();
        ToolConfiguration.registerSandboxTools(registry, manager, props,
                new PermissionRuleEngine(new PermissionSettings()));

        Sandbox sandbox = manager.create("edit-limit-session");
        Files.writeString(sandbox.root().resolve("a.txt"), "x".repeat(200));

        ToolContext ctx = ToolContext.defaultContext();
        ctx.set(Sandbox.STATE_KEY, sandbox);

        Tool edit = registry.findByName("Edit").orElseThrow();
        String result = edit.execute(Map.of(
                "file_path", "a.txt", "old_string", "x", "new_string", "y"), ctx);

        assertTrue(result.contains("too large"),
                "Edit 应使用配置的上限（16 字节）拒绝 200 字节的文件，实际：" + result);
    }

    @Test
    void registersLspOnlyWhenServersConfigured() throws IOException {
        SandboxProperties props = usableProps();
        SandboxProperties.Server server = new SandboxProperties.Server();
        server.setCommand("jdtls");
        server.getExtensions().put(".java", "java");
        props.getLsp().getServers().put("java", server);
        ToolRegistry registry = new ToolRegistry();
        ToolConfiguration.registerSandboxTools(registry, new SandboxManager(props), props,
                new PermissionRuleEngine(new PermissionSettings()));
        assertTrue(registry.getToolNames().contains("LSP"));
    }

    @Test
    void permissionModesFollowToolRiskLevels() throws IOException {
        SandboxProperties props = usableProps();

        PermissionSettings settings = new PermissionSettings();
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        ToolConfiguration.registerSandboxTools(new ToolRegistry(),
                new SandboxManager(props), props, engine);

        // READ_ONLY 在 STRICT 放行
        settings.setCurrentMode(PermissionMode.STRICT);
        assertTrue(engine.evaluate("Read", Map.of(), Tool.RiskLevel.READ_ONLY).isAllowed());

        // MEDIUM 在 SAFE 不自动放行：权限引擎的既有语义是 ASK（不是 deny）
        settings.setCurrentMode(PermissionMode.SAFE);
        assertTrue(engine.evaluate("Write", Map.of(), Tool.RiskLevel.MEDIUM).needsAsk());

        // HIGH 在 DEFAULT 触发确认，且命令提取器已注册（input 带 command 不报错）
        settings.setCurrentMode(PermissionMode.DEFAULT);
        assertTrue(engine.evaluate("Bash", Map.of("command", "mvn -q test"),
                Tool.RiskLevel.HIGH).needsAsk());
        assertEquals("mvn",
                engine.extractCommandPrefixForTool("Bash", Map.of("command", "mvn -q test")));
    }
}
