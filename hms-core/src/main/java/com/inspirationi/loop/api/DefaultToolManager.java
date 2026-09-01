package com.inspirationi.loop.api;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link ToolManager} 的默认实现。
 */
public class DefaultToolManager implements ToolManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolManager.class);

    /** 全局工具注册中心（两级工具中的全局级，所有会话共享）。 */
    private final ToolRegistry globalToolRegistry;
    /** 会话管理器，用于访问会话级工具注册中心。 */
    private final HmsSessionManager sessionManager;

    /**
     * 构造工具管理器。
     *
     * @param globalToolRegistry 全局工具注册中心
     * @param sessionManager     会话管理器
     */
    public DefaultToolManager(ToolRegistry globalToolRegistry, HmsSessionManager sessionManager) {
        this.globalToolRegistry = globalToolRegistry;
        this.sessionManager = sessionManager;
        log.info("ToolManager initialized (global tools: {})", globalToolRegistry.getToolNames());
    }

    // ==================== 全局 ====================

    /** 获取全局工具注册中心。 */
    @Override
    public ToolRegistry getGlobalToolRegistry() {
        return globalToolRegistry;
    }

    /** 注册全局工具（新创建的会话将包含该工具）。 */
    @Override
    public void registerGlobalTool(Tool tool) {
        globalToolRegistry.register(tool);
        log.info("Global tool registered: {}", tool.name());
    }

    /** 移除全局工具。 */
    @Override
    public void removeGlobalTool(String toolName) {
        globalToolRegistry.remove(toolName);
        log.info("Global tool removed: {}", toolName);
    }

    /** 获取全局工具名称列表（返回不可变副本）。 */
    @Override
    public List<String> getGlobalToolNames() {
        return List.copyOf(globalToolRegistry.getToolNames());
    }

    // ==================== 会话 ====================

    /** 获取指定会话的工具名称列表（会话不存在时返回空列表）。 */
    @Override
    public List<String> getSessionToolNames(String sessionId) {
        var info = sessionManager.getSessionInfo(sessionId);
        return info != null ? info.toolNames() : List.of();
    }

    /** 为指定会话添加工具（只影响该会话）。 */
    @Override
    public void addSessionTool(String sessionId, Tool tool) {
        // 通过会话管理器获取会话级工具注册中心（在实现类内部访问 LoopSession）
        sessionManager.getSessionToolRegistry(sessionId).register(tool);
        log.info("Session {} added tool: {}", sessionId, tool.name());
    }

    /** 按名称从全局注册中心查找工具并添加到指定会话。 */
    @Override
    public void addSessionToolByName(String sessionId, String toolName) {
        Tool tool = globalToolRegistry.findByName(toolName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tool not found in global registry: " + toolName));
        addSessionTool(sessionId, tool);
    }

    /** 从指定会话移除工具（只影响该会话）。 */
    @Override
    public void removeSessionTool(String sessionId, String toolName) {
        sessionManager.getSessionToolRegistry(sessionId).remove(toolName);
        log.info("Session {} removed tool: {}", sessionId, toolName);
    }
}
