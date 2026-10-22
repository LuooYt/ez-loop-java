package com.inspirationi.loop.tool;

/**
 * 通知投递出口 —— 宿主集成方可通过它把通知接到自己的通道（WebSocket、桌面客户端等）。
 * <p>
 * 未注入时，Notification 工具会回退到系统通知命令（可配置关闭）。
 */
public interface NotificationSink {

    /** ToolContext 共享状态键。 */
    String CONTEXT_KEY = "NOTIFICATION_SINK";

    /**
     * 发送一条通知。
     *
     * @param title   标题（已截断到 80 字符）
     * @param message 正文
     * @param level   {@code info} / {@code warning} / {@code error}
     * @param sound   是否播放提示音
     */
    void send(String title, String message, String level, boolean sound);
}
