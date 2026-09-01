package com.inspirationi.loop.plugin;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 输出样式插件 —— 提供输出样式切换。
 * <p>
 * SDK 模式下不含 /style 命令（无 CLI），仅提供编程式 API。
 *
 * <h3>可用样式</h3>
 * <ul>
 *   <li><b>default</b> —— 默认彩色输出</li>
 *   <li><b>minimal</b> —— 精简输出</li>
 *   <li><b>verbose</b> —— 详细输出</li>
 *   <li><b>markdown</b> —— 纯 Markdown 输出</li>
 * </ul>
 */
public class OutputStylePlugin implements Plugin {

    /** 支持的输出样式集合（default / minimal / verbose / markdown）。 */
    private static final Set<String> SUPPORTED_STYLES = Set.of(
            "default", "minimal", "verbose", "markdown"
    );

    /** 当前输出样式，初始为 "default"，使用 AtomicReference 保证线程安全。 */
    private final AtomicReference<String> currentStyle = new AtomicReference<>("default");
    /** 插件上下文（初始化时注入），用于获取日志器等核心能力。 */
    private PluginContext context;

    @Override
    public String id() { return "output-style"; }

    @Override
    public String name() { return "Output Style"; }

    @Override
    public String version() { return "1.0.0"; }

    @Override
    public String description() { return "Custom output styles"; }

    /**
     * 初始化插件：保存上下文引用并记录当前样式日志。
     *
     * @param context 插件上下文，提供访问应用核心功能的接口
     */
    @Override
    public void initialize(PluginContext context) {
        this.context = context;
        context.getLogger().info("Output style plugin initialized, current style: {}", currentStyle.get());
    }

    public String getCurrentStyle() { return currentStyle.get(); }

    /**
     * 切换输出样式。
     * <p>
     * 入参不区分大小写；仅当样式属于受支持的样式集合时才生效。
     *
     * @param style 目标样式名称（如 "default"、"minimal"）
     * @return 切换成功返回 true，样式不受支持或为 null 时返回 false
     */
    public boolean setStyle(String style) {
        if (style != null && SUPPORTED_STYLES.contains(style.toLowerCase())) {
            currentStyle.set(style.toLowerCase());
            return true;
        }
        return false;
    }

    /**
     * 销毁插件：记录销毁日志（上下文非空时）。
     */
    @Override
    public void destroy() {
        if (context != null) {
            context.getLogger().info("Output style plugin destroyed");
        }
    }
}
