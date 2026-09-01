package com.inspirationi.loop.api;

/**
 * 中立的历史消息记录 —— 将 Spring AI 的 Message 转换成对 Web/集成方友好的 DTO。
 * <p>
 * 供 {@link HmsSessionManager#getSessionMessages(String)} 返回，前端据此回显历史对话。
 *
 * @param role          "user" | "assistant" | "tool" | "system"
 * @param content       文本内容（user/assistant/system；tool 记录为 null）
 * @param toolName      工具名（role=tool 时非空）
 * @param toolArguments 工具入参 JSON 字符串（tool 记录来自 AssistantMessage.toolCalls）
 * @param toolResult    工具执行结果文本（tool 记录来自 ToolResponseMessage.responses）
 */
public record ChatMessage(
        /** 消息角色：user / assistant / tool / system */
        String role,
        /** 文本内容（tool 记录为 null） */
        String content,
        /** 工具名（仅 tool 记录非空） */
        String toolName,
        /** 工具入参 JSON 字符串 */
        String toolArguments,
        /** 工具执行结果文本 */
        String toolResult) {
}
