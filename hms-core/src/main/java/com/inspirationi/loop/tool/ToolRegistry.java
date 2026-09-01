package com.inspirationi.loop.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;

/**
 * 工具注册中心 —— 管理 Tool 的注册、查找和到 Spring AI ToolCallback 的转换。
 * <p>
 * <b>线程安全。</b>全局实例是单例 Bean，会被多类线程同时访问：请求线程经
 * {@code ToolManager} 增删工具、会话创建线程遍历复制、Agent 执行线程构建回调。
 * 因此内部 map 必须同步，且所有返回集合的方法都返回快照而非视图 ——
 * 否则调用方遍历快照期间的并发修改会破坏迭代。
 */
public class ToolRegistry {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * 已注册工具集合，key 为工具名称。
     * <p>
     * 同步包装的 LinkedHashMap：既保证并发安全，又保留注册顺序 —— 顺序会影响
     * 提示词中工具清单的稳定性，故不能换成 ConcurrentHashMap。
     * 所有读写都必须持有 {@link #lock}（同步包装只保护单次操作，
     * 迭代仍需外部加锁）。
     */
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 保护 {@link #tools} 的读写与迭代。 */
    private final Object lock = new Object();

    /**
     * 注册工具。若工具 isEnabled() 返回 false 则跳过。
     */
    public void register(Tool tool) {
        if (!tool.isEnabled()) {
            log.debug("Tool [{}] not enabled, skipping registration", tool.name());
            return;
        }
        boolean overridden;
        synchronized (lock) {
            overridden = tools.put(tool.name(), tool) != null;
        }
        if (overridden) {
            log.warn("Tool [{}] already registered, will be overridden", tool.name());
        }
        log.debug("Registered tool: [{}]", tool.name());
    }

    /** 批量注册 */
    public void registerAll(Tool... toolArray) {
        for (Tool t : toolArray) {
            register(t);
        }
    }

    /** 按名称查找 */
    public Optional<Tool> findByName(String name) {
        synchronized (lock) {
            return Optional.ofNullable(tools.get(name));
        }
    }

    /** 移除工具 */
    public boolean remove(String name) {
        Tool removed;
        synchronized (lock) {
            removed = tools.remove(name);
        }
        if (removed != null) {
            log.debug("Removed tool: [{}]", name);
            return true;
        }
        return false;
    }

    /** 获取所有已注册工具（快照，按注册顺序）。 */
    public List<Tool> getTools() {
        synchronized (lock) {
            return List.copyOf(tools.values());
        }
    }

    /** 获取所有工具名称（快照）。 */
    public Set<String> getToolNames() {
        synchronized (lock) {
            return Set.copyOf(tools.keySet());
        }
    }

    /** 转换为 Spring AI ToolCallback 列表（基于调用时刻的快照）。 */
    public List<ToolCallback> toCallbacks(ToolContext context) {
        List<Tool> snapshot = getTools();
        List<ToolCallback> callbacks = new ArrayList<>(snapshot.size());
        for (Tool tool : snapshot) {
            callbacks.add(new ToolCallbackAdapter(tool, context));
        }
        return callbacks;
    }

    /** 已注册工具数量 */
    public int size() {
        synchronized (lock) {
            return tools.size();
        }
    }
}
