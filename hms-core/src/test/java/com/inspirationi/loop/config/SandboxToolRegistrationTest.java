package com.inspirationi.loop.config;

import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.permission.PermissionSettings;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.tool.Tool;
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

    @Test
    void doesNotRegisterPathToolsWhenSandboxDisabled() {
        ToolRegistry registry = new ToolRegistry();
        SandboxProperties props = new SandboxProperties(); // enabled=false
        ToolConfiguration.registerSandboxTools(registry, props,
                new PermissionRuleEngine(new PermissionSettings()));
        assertFalse(registry.getToolNames().contains("Read"));
        assertFalse(registry.getToolNames().contains("Bash"));
    }

    @Test
    void registersPathToolsWhenSandboxEnabled() throws IOException {
        ToolRegistry registry = new ToolRegistry();
        SandboxProperties props = new SandboxProperties();
        props.setEnabled(true);
        props.setRoot(temp.resolve("root"));
        Files.createDirectories(props.getRoot());
        ToolConfiguration.registerSandboxTools(registry, props,
                new PermissionRuleEngine(new PermissionSettings()));
        assertTrue(registry.getToolNames().containsAll(
                Set.of("Read", "Write", "Edit", "Glob", "Grep", "ListFiles", "Bash",
                        "NotebookEdit", "EnterWorktree", "ExitWorktree")));
        assertFalse(registry.getToolNames().contains("LSP")); // 未配置服务器
    }

    @Test
    void registersLspOnlyWhenServersConfigured() throws IOException {
        SandboxProperties props = new SandboxProperties();
        props.setEnabled(true);
        props.setRoot(temp.resolve("root"));
        Files.createDirectories(props.getRoot());
        SandboxProperties.Server server = new SandboxProperties.Server();
        server.setCommand("jdtls");
        server.getExtensions().put(".java", "java");
        props.getLsp().getServers().put("java", server);
        ToolRegistry registry = new ToolRegistry();
        ToolConfiguration.registerSandboxTools(registry, props,
                new PermissionRuleEngine(new PermissionSettings()));
        assertTrue(registry.getToolNames().contains("LSP"));
    }

    @Test
    void permissionModesFollowToolRiskLevels() throws IOException {
        SandboxProperties props = new SandboxProperties();
        props.setEnabled(true);
        props.setRoot(temp.resolve("root"));
        Files.createDirectories(props.getRoot());

        PermissionSettings settings = new PermissionSettings();
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        ToolConfiguration.registerSandboxTools(new ToolRegistry(), props, engine);

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
