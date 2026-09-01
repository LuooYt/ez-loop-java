package com.inspirationi.loop.api;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolRegistry;

import java.util.List;

/**
 * 工具管理器 —— 两级工具体系：全局 + 会话。
 * <p>
 * 全局工具在 Bean 启动时注册到全局 ToolRegistry，所有会话共享基础工具集。
 * 会话工具是全局工具的副本 + 会话专属增删。
 * <p>
 * 不与文件系统交互，全部通过编程式 API 管理。
 */
public interface ToolManager {

    // ==================== 全局工具 ====================

    /** 获取全局工具注册中心 */
    ToolRegistry getGlobalToolRegistry();

    /** 注册全局工具（新会话将包含该工具） */
    void registerGlobalTool(Tool tool);

    /** 移除全局工具 */
    void removeGlobalTool(String toolName);

    /** 获取全局工具名称列表 */
    List<String> getGlobalToolNames();

    // ==================== 会话工具 ====================

    /** 获取指定会话的工具名称列表 */
    List<String> getSessionToolNames(String sessionId);

    /** 为指定会话添加工具（只影响该会话） */
    void addSessionTool(String sessionId, Tool tool);

    /**
     * 按名称从全局注册中心查找工具并添加到指定会话（只影响该会话）。
     *
     * @param sessionId 会话 ID
     * @param toolName  全局工具名称
     * @throws IllegalArgumentException 会话不存在，或全局注册中心中不存在该名称的工具
     */
    void addSessionToolByName(String sessionId, String toolName);

    /** 从指定会话移除工具（只影响该会话） */
    void removeSessionTool(String sessionId, String toolName);
}
