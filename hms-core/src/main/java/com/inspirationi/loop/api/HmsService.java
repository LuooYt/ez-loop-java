package com.inspirationi.loop.api;

import com.inspirationi.loop.core.AgentLoop;

/**
 *  对外集成的核心门面接口。
 * <p>
 * 封装 {@link AgentLoop} 的完整生命周期，
 * 供其他 Java 应用以程序化方式调用  的 AI 能力。
 * <p>
 * 使用方式：
 * <pre>{@code
 * @Autowired
 * private HmsService hmsService;
 *
 * // 同步调用
 * HmsResponse response = hmsService.send("分析项目结构");
 * System.out.println(response.content());
 *
 * // 流式调用
 * hmsService.sendStreaming("列出所有文件", token -> System.out.print(token));
 * }</pre>
 */
public interface HmsService {

    /**
     * 同步调用 —— 发送用户消息，等待完整 AI 回复后返回。
     *
     * @param userMessage 用户输入文本
     * @return 完整响应，包含回复内容、工具调用次数和 Token 统计
     */
    HmsResponse send(String userMessage);

    /**
     * 流式调用 —— 发送用户消息，每个文本 token 实时通过回调输出。
     *
     * @param userMessage 用户输入文本
     * @param onToken     每个 token 的实时回调
     * @return 完整响应（流结束后总结）
     */
    HmsResponse sendStreaming(String userMessage, java.util.function.Consumer<String> onToken);

    /**
     * 带完整回调的调用 —— 支持 token 流、工具事件、thinking、权限请求等所有回调。
     *
     * @param userMessage 用户输入文本
     * @param callbacks   回调集合（按需覆写感兴趣的方法）
     * @return 完整响应
     */
    HmsResponse send(String userMessage, HmsCallbacks callbacks);

    /**
     * 取消当前正在执行的 Agent 循环。
     * 非阻塞，调用后当前请求会尽快中断。
     */
    void cancel();

    /**
     * 获取当前会话的 Token 使用统计。
     *
     * @return Token 统计信息
     */
    TokenStats getTokenStats();

    /**
     * 重置会话（清除消息历史）。
     * 通常在开始新话题时调用。
     */
    void reset();

    /**
     * 获取服务是否正在处理中。
     *
     * @return true 表示当前有请求正在执行
     */
    boolean isBusy();
}
