package com.inspirationi.loop.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "hms-core.sandbox")
public class SandboxProperties {

    private boolean enabled = false;
    private Path root;
    private boolean cleanupOnDestroy = false;
    private long maxFileBytes = 10L * 1024 * 1024;
    private Bash bash = new Bash();
    private Lsp lsp = new Lsp();
    private Notification notification = new Notification();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getRoot() { return root; }
    public void setRoot(Path root) { this.root = root; }
    public boolean isCleanupOnDestroy() { return cleanupOnDestroy; }
    public void setCleanupOnDestroy(boolean cleanupOnDestroy) { this.cleanupOnDestroy = cleanupOnDestroy; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public Bash getBash() { return bash; }
    public void setBash(Bash bash) { this.bash = bash; }
    public Lsp getLsp() { return lsp; }
    public void setLsp(Lsp lsp) { this.lsp = lsp; }
    public Notification getNotification() { return notification; }
    public void setNotification(Notification notification) { this.notification = notification; }

    /** 是否真正可用：开关打开且根路径已配置。 */
    public boolean isUsable() {
        return enabled && root != null && !root.toString().isBlank();
    }

    public static class Bash {
        private int timeoutSeconds = 120;
        private int maxOutputChars = 40_000;
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public int getMaxOutputChars() { return maxOutputChars; }
        public void setMaxOutputChars(int v) { this.maxOutputChars = v; }
    }

    public static class Lsp {
        private Map<String, Server> servers = new LinkedHashMap<>();
        public Map<String, Server> getServers() { return servers; }
        public void setServers(Map<String, Server> servers) {
            this.servers = servers != null ? servers : new LinkedHashMap<>();
        }
    }

    public static class Server {
        private String command;
        private java.util.List<String> args = new java.util.ArrayList<>();
        private Map<String, String> extensions = new LinkedHashMap<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private long startupTimeout = 30_000;
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public java.util.List<String> getArgs() { return args; }
        public void setArgs(java.util.List<String> args) { this.args = args != null ? args : new java.util.ArrayList<>(); }
        public Map<String, String> getExtensions() { return extensions; }
        public void setExtensions(Map<String, String> extensions) {
            this.extensions = extensions != null ? extensions : new LinkedHashMap<>();
        }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env != null ? env : new LinkedHashMap<>(); }
        public long getStartupTimeout() { return startupTimeout; }
        public void setStartupTimeout(long startupTimeout) { this.startupTimeout = startupTimeout; }
    }

    public static class Notification {
        private boolean systemCommand = true;
        public boolean isSystemCommand() { return systemCommand; }
        public void setSystemCommand(boolean systemCommand) { this.systemCommand = systemCommand; }
    }
}
