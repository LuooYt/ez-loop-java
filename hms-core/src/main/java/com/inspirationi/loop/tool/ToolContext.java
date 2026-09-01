package com.inspirationi.loop.tool;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 工具执行上下文 —— 通用 SDK 场景的共享状态容器。
 * <p>
 * 提供工具执行时所需的环境信息和共享状态。
 * <b>workDir 和 model 均为可选</b>，不依赖文件系统或 CLI 环境。
 */
public class ToolContext {

    /** 工作目录（可能为 null，表示无文件系统依赖） */
    private final Path workDir;
    /** 模型名称（可能为 null） */
    private final String model;
    /** 线程安全的共享状态容器，供工具间传递数据 */
    private final ConcurrentHashMap<String, Object> state;
    private volatile Consumer<String> progressCallback; // 工具执行进度回调（流式输出行）

    /** 创建工具执行上下文 */
    public ToolContext(Path workDir, String model) {
        this.workDir = workDir;
        this.model = model;
        this.state = new ConcurrentHashMap<>();
    }

    /** 创建纯内存上下文（无文件系统依赖），适用于 Web/数据库等 SDK 场景 */
    public static ToolContext defaultContext() {
        return new ToolContext(null, null);
    }

    /** 获取工作目录（可能为 null，表示无文件系统依赖） */
    public Path getWorkDir() {
        return workDir;
    }

    /** 获取工作目录的 Optional 包装（推荐用法） */
    public Optional<Path> getWorkDirOpt() {
        return Optional.ofNullable(workDir);
    }

    /** 获取模型名称（可能为 null） */
    public String getModel() {
        return model;
    }

    /** 获取共享状态值 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) state.get(key);
    }

    /** 设置共享状态值 */
    public void set(String key, Object value) {
        state.put(key, value);
    }

    /** 获取共享状态值，不存在时返回默认值 */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) state.getOrDefault(key, defaultValue);
    }

    /** 判断共享状态中是否已存在指定键 */
    public boolean has(String key) {
        return state.containsKey(key);
    }

    /** 设置进度回调（工具可在执行过程中报告输出行） */
    public void setProgressCallback(Consumer<String> progressCallback) {
        this.progressCallback = progressCallback;
    }

    /** 报告进度（如果有回调注册） */
    public void reportProgress(String line) {
        Consumer<String> cb = progressCallback;
        if (cb != null) {
            cb.accept(line);
        }
    }
}
