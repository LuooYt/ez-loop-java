package com.inspirationi.loop.core;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Coordinator Mode —— /src/coordinator/coordinatorMode.ts。
 * <p>
 * 协调模式允许 Agent 作为"协调者"运行，仅使用 Agent、SendMessage、TaskStop 工具
 * 来派发和管理 worker agent。Worker agent 使用标准工具集执行实际任务。
 * <p>
 * 通过环境变量 CLAUDE_CODE_COORDINATOR_MODE=1 启用。
 */
public class CoordinatorMode {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorMode.class);

    /** Coordinator 可用的工具集 */
    public static final Set<String> COORDINATOR_ALLOWED_TOOLS = Set.of(
            "Agent",       // 派发 worker
            "SendMessage", // 向 worker 发送消息
            "TaskStop",    // 停止 worker
            "TaskGet",     // 查看 worker 状态
            "TaskList",    // 列出所有 worker
            "TaskOutput"   // 获取 worker 输出
    );

    /** Worker（异步 agent）可用的工具集 */
    public static final Set<String> WORKER_ALLOWED_TOOLS = Set.of(
            "Read",          // 读取文件
            "Write",         // 写入文件
            "Edit",          // 编辑文件
            "Bash",          // 执行命令
            "Grep",          // 搜索文件内容
            "Glob",          // 文件模式匹配
            "ListFiles",     // 列出目录
            "WebFetch",      // 获取网页
            "WebSearch",     // 搜索网页
            "TodoRead",      // 读取待办
            "TodoWrite",     // 写待办
            "ToolSearch",    // 搜索工具
            "Skill"          // 执行 skill
    );

    /** 检查 coordinator 模式是否通过环境变量启用 */
    public static boolean isCoordinatorMode() {
        String envVal = System.getenv("CLAUDE_CODE_COORDINATOR_MODE");
        return envVal != null && !envVal.isBlank()
                && !envVal.equalsIgnoreCase("false")
                && !envVal.equals("0");
    }

    /**
     * 获取 Coordinator 系统提示词。
     * 对应 TS 版 getCoordinatorSystemPrompt()。
     */
    public static String getCoordinatorSystemPrompt() {
        return PromptI18n.t(PromptI18n.KEY_COORDINATOR_PROMPT, """
                你是 HMS Core 协调者，一个编排跨多个 worker 的软件工程任务的 AI 助手。你的职责是：
                1. 理解用户请求，并将其分解为可并行执行的任务
                2. 使用 Agent 工具为每个任务启动 worker agent
                3. 监控 worker 进度并综合结果
                4. 向用户传达清晰、可执行的结果

                ## 你的工具

                - **Agent** — 启动一个 worker 来执行特定任务。Worker 可访问文件操作（Read、Write、Edit）、\
                shell 命令（Bash）、搜索（Grep、Glob）、Web 访问和项目 skills。
                - **SendMessage** — 向运行中或已完成的 worker 发送后续指令。\
                用于继续多步骤工作流或提供修正。
                - **TaskStop** — 强制终止卡住或不再需要的 worker。
                - **TaskGet** — 检查特定 worker 的当前状态和输出。
                - **TaskList** — 列出所有活跃和已完成的 worker。
                - **TaskOutput** — 获取已完成 worker 的完整输出。

                ## Worker 结果

                当 worker 完成时，你会收到 task-notification，包含：
                - task-id：worker 的唯一标识符
                - status：completed、failed 或 cancelled
                - summary：完成内容的简要描述
                - result：worker 的完整输出

                ## 工作流指南

                ### 任务分解
                1. **调研阶段**：启动 worker 调研代码库、理解问题
                2. **综合**：分析 worker 结果，识别模式，形成计划
                3. **实施阶段**：启动 worker 进行代码变更，每个变更都有明确范围
                4. **验证阶段**：启动 worker 进行测试、lint 和校验变更

                ### 编写 Worker 提示词
                - 要**自包含**：Worker 看不到你的对话历史
                - 包含**文件路径**（绝对路径）、**行号**和**精确上下文**
                - 指明**期望的输出格式**（你从结果中需要什么）
                - 添加**目的说明**，让 worker 理解大局
                - 如果基于之前的发现，在提示词中**总结这些发现**

                ### 并发管理
                - 为获得最大吞吐量**并行**启动独立任务
                - 依赖其他任务结果的 worker 应**顺序**启动
                - 不要过度分解——如果任务简单，一个 worker 就够了
                - 将相关的小变更归入单个 worker 的范围

                ### 验证最佳实践
                - 始终使用专门的验证 worker 验证实现变更
                - 验证 worker 应运行现有测试以及任何新增测试
                - 请验证 worker 检查常见问题（导入、类型、边界情况）

                ## 沟通
                - 你发送的每条消息都面向**用户**（而非 worker）
                - 在 worker 完成时提供简洁的状态更新
                - 将 worker 结果综合成清晰、连贯的摘要
                - 如果出现问题，解释发生了什么并提出下一步

                ## 重要规则
                - 你不能直接访问文件、shell 或搜索——把这些委托给 worker
                - **不要**自己尝试编辑文件；任何文件操作都启动 worker 完成
                - 保持对话专注于编排和综合
                - 如果 worker 失败，分析错误并启动一个修正 worker
                """);
    }

    /**
     * 获取 coordinator 的用户上下文消息。
     * 告知 coordinator worker 可用的工具集。
     */
    public static String getCoordinatorUserContext() {
        String template = PromptI18n.t(PromptI18n.KEY_COORDINATOR_USER_CONTEXT, """
                ## Worker 能力

                Worker 可以访问以下工具：
                %s

                以及来自已连接服务器的任何 MCP 工具。
                """);
        StringBuilder tools = new StringBuilder();
        for (String tool : WORKER_ALLOWED_TOOLS.stream().sorted().toList()) {
            tools.append("- ").append(tool).append("\n");
        }
        return template.formatted(tools.toString().stripTrailing());
    }

    /**
     * 过滤 ToolRegistry，仅保留 coordinator 可用的工具。
     */
    public static java.util.List<String> filterForCoordinator(ToolRegistry registry) {
        return registry.getToolNames().stream()
                .filter(COORDINATOR_ALLOWED_TOOLS::contains)
                .toList();
    }

    /**
     * 过滤 ToolRegistry，仅保留 worker 可用的工具。
     */
    public static java.util.List<String> filterForWorker(ToolRegistry registry) {
        return registry.getToolNames().stream()
                .filter(WORKER_ALLOWED_TOOLS::contains)
                .toList();
    }
}
