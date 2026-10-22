package com.inspirationi.loop.api;

import com.inspirationi.loop.permission.PermissionRuleEngine;
import com.inspirationi.loop.sandbox.SandboxManager;
import com.inspirationi.loop.sandbox.SandboxProperties;
import com.inspirationi.loop.telemetry.TokenPricing;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.springframework.ai.chat.model.ChatModel;

import java.util.concurrent.ScheduledExecutorService;

/**
 * 装配一个 {@code AgentRuntime} 所需的全部<b>进程级</b>依赖与默认值。
 * <p>
 * 「进程级」是重点：这里的每一项都是所有 Agent 共享的那一份（同一个 ChatModel、
 * 同一个全局工具注册中心、同一个全局工具上下文、同一个调度池）。各 Agent 之间
 * 有差异的部分（提示词、工具选择、权限、记忆）全部来自 {@code AgentDefinition}，
 * 不在这里 —— 本配置对象在多智能体场景下是<b>只读共享</b>的，一旦混入 Agent 私有的
 * 东西，就会在多个 Agent 之间串味。
 * <p>
 * <b>为什么放在 {@code api} 包而不是 {@code agent} 包</b>：它持有 {@link ChatModel}，
 * 而 {@code agent} 包刻意保持对 Spring / Spring AI 的零依赖（纯内存领域层）。
 * 装配层依赖具体技术栈，领域层不依赖 —— 这条分界比「文件放在哪个目录」重要。
 *
 * @param chatModel              全进程共享的聊天模型
 * @param globalToolRegistry     全局工具注册中心 —— 各 Agent 从中按需筛选
 * @param globalPermissionEngine 全局权限引擎 —— 供各 Agent 继承危险检测与命令提取策略
 * @param globalToolContext      全局工具上下文 —— 各 Agent 上下文的父级
 * @param promptManager          提示词管理器（全局实例，三级拼接由它完成）
 * @param tokenPricing           计费策略（可为 {@code null}）
 * @param sharedScheduler        共享调度池 —— 空闲清理与主动 Agent 心跳共用
 * @param defaultMaxSessions     会话数上限默认值（Agent 未指定时）
 * @param defaultMaxIterations   单轮最大迭代默认值
 * @param contextWindow          上下文窗口（token）
 * @param reservedTokens         预留 token 数
 * @param userResponseTimeoutSeconds 等待用户回答的上限（秒）
 * @param cleanupIntervalSeconds 空闲清理执行间隔（秒）
 * @param defaultIdleTimeoutSeconds  空闲回收阈值默认值（秒）
 * @param sandboxManager         会话沙箱管理器（可为 {@code null}，表示不启用沙箱）
 * @param sandboxProperties      沙箱配置（可为 {@code null}）；会话创建时据此装配 LSP 服务器
 */
public record AgentRuntimeConfig(
        ChatModel chatModel,
        ToolRegistry globalToolRegistry,
        PermissionRuleEngine globalPermissionEngine,
        ToolContext globalToolContext,
        PromptManager promptManager,
        TokenPricing tokenPricing,
        ScheduledExecutorService sharedScheduler,
        int defaultMaxSessions,
        int defaultMaxIterations,
        long contextWindow,
        long reservedTokens,
        long userResponseTimeoutSeconds,
        long cleanupIntervalSeconds,
        long defaultIdleTimeoutSeconds,
        SandboxManager sandboxManager,
        SandboxProperties sandboxProperties
) {

    /** 紧凑构造器：校验必需依赖。 */
    public AgentRuntimeConfig {
        if (chatModel == null) {
            throw new IllegalArgumentException("chatModel must not be null");
        }
        if (globalToolRegistry == null) {
            throw new IllegalArgumentException("globalToolRegistry must not be null");
        }
        if (sharedScheduler == null) {
            throw new IllegalArgumentException("sharedScheduler must not be null");
        }
    }
}
