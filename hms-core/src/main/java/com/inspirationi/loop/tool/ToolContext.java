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
 * <p>
 * 支持父子结构（见 {@link #childOf}）：读取时本地缺失的键回落到父级，
 * 写入始终落在本地。会话级上下文借此拿到全局共享对象
 * （TaskManager、McpManager、PermissionSettings…），又不会污染全局。
 */
public class ToolContext {

    /** 工作目录（可能为 null，表示无文件系统依赖） */
    private final Path workDir;
    /** 模型名称（可能为 null） */
    private final String model;
    /** 线程安全的共享状态容器，供工具间传递数据 */
    private final ConcurrentHashMap<String, Object> state;
    /**
     * 父级上下文（可能为 null）—— 仅用于读取回落。
     * <p>
     * 必须是读透而非创建时快照复制：SDK 使用方可能在会话创建之后才向全局
     * 上下文注册共享对象（如自定义 SearchProvider），快照会让这些注册对
     * 已存在的会话永久不可见。
     */
    private final ToolContext parent;
    private volatile Consumer<String> progressCallback; // 工具执行进度回调（流式输出行）

    /** 创建工具执行上下文 */
    public ToolContext(Path workDir, String model) {
        this(workDir, model, null);
    }

    /** 创建带父级的工具执行上下文（父级仅参与读取回落）。 */
    public ToolContext(Path workDir, String model, ToolContext parent) {
        this.workDir = workDir;
        this.model = model;
        this.parent = parent;
        this.state = new ConcurrentHashMap<>();
    }

    /** 创建纯内存上下文（无文件系统依赖），适用于 Web/数据库等 SDK 场景 */
    public static ToolContext defaultContext() {
        return new ToolContext(null, null);
    }

    /**
     * 创建继承自 {@code parent} 的子上下文 —— 沿用父级的 workDir / model，
     * 并在读取时回落到父级的共享状态。
     *
     * @param parent 父级上下文；为 {@code null} 时等价于 {@link #defaultContext()}
     */
    public static ToolContext childOf(ToolContext parent) {
        if (parent == null) {
            return defaultContext();
        }
        return new ToolContext(parent.workDir, parent.model, parent);
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

    /** 获取共享状态值（本地缺失时回落到父级）。 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Object value = state.get(key);
        if (value == null && parent != null) {
            return parent.get(key);
        }
        return (T) value;
    }

    /** 设置共享状态值（始终写入本地，不影响父级）。 */
    public void set(String key, Object value) {
        state.put(key, value);
    }

    /**
     * 移除本地共享状态值（不影响父级）。
     * <p>
     * 请求级回调必须在请求结束后移除：它们捕获了本次请求的 {@code HmsCallbacks}，
     * 而上下文是<b>会话级</b>的。残留下来会让下一次不带回调的调用把提问发给上一个
     * 请求的回调 —— 在 SSE 等场景下那个接收端早已关闭，提问只能空等到超时。
     * <p>
     * 只删本地键，因此不会误删父级（全局）注册的共享对象。
     *
     * @return 被移除的值；本地不存在该键时为 {@code null}
     */
    public Object remove(String key) {
        return state.remove(key);
    }

    /** 获取共享状态值，本地与父级均不存在时返回默认值。 */
    public <T> T getOrDefault(String key, T defaultValue) {
        T value = get(key);
        return value != null ? value : defaultValue;
    }

    /** 判断共享状态中是否已存在指定键（含父级）。 */
    public boolean has(String key) {
        return state.containsKey(key) || (parent != null && parent.has(key));
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
