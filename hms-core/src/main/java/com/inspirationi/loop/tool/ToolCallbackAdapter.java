package com.inspirationi.loop.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Tool → Spring AI ToolCallback 适配器。
 * <p>
 * 将自定义 Tool 协议适配为 Spring AI 的 ToolCallback 接口，
 * 在调用时处理 JSON 解析、权限检查和异常捕获。
 * <p>
 * Spring AI ToolCallback 协议适配层。
 */
public class ToolCallbackAdapter implements ToolCallback {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(ToolCallbackAdapter.class);
    /** JSON 解析器，用于将调用入参从 JSON 字符串解析为 Map */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 被适配封装的基础工具 */
    private final Tool tool;
    /** Spring AI 工具定义（名称、描述、输入 Schema），构建时由 tool 派生 */
    private final ToolDefinition toolDefinition;
    /** 工具执行上下文，透传给被封装工具 */
    private final ToolContext context;

    /** 构造适配器：根据工具的名称/描述/输入 Schema 构建 Spring AI 工具定义 */
    public ToolCallbackAdapter(Tool tool, ToolContext context) {
        this.tool = tool;
        this.context = context;
        this.toolDefinition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
    }

    /** 返回 Spring AI 工具定义 */
    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    /**
     * 工具调用入口：将 JSON 入参解析为 Map，依次执行权限前置检查、工具执行，并统一捕获异常。
     * <p>
     * 处理流程：
     * <ul>
     *   <li>解析失败或执行异常时返回以 "Error:" 开头的错误文本</li>
     *   <li>权限被拒绝时返回拒绝原因，不执行工具</li>
     * </ul>
     */
    @Override
    @SuppressWarnings("unchecked")
    public String call(String jsonInput) {
        try {
            Map<String, Object> input = MAPPER.readValue(jsonInput, Map.class);

            // 权限前置检查
            PermissionResult perm = tool.checkPermission(input, context);
            if (!perm.allowed()) {
                log.warn("[{}] Permission denied: {}", tool.name(), perm.message());
                return "Permission denied: " + perm.message();
            }

            log.info("[TOOL] Executing: {}, input={}", tool.name(), jsonInput);
            String result = tool.execute(input, context);
            log.info("[TOOL] Result for {} ({} chars): {}",
                    tool.name(), result != null ? result.length() : 0,
                    result != null ? result.substring(0, Math.min(200, result.length())) : "null");
            return result;
        } catch (JsonProcessingException e) {
            log.warn("[{}] JSON parse failed: {}", tool.name(), e.getMessage());
            return "Error: Invalid JSON input: " + describe(e);
        } catch (Exception e) {
            log.warn("[{}] Execution exception: {}", tool.name(), e.getMessage(), e);
            return "Error: " + describe(e);
        }
    }

    /** 提取面向模型的错误描述，见 {@link Tool#describeError}。 */
    private static String describe(Throwable error) {
        return Tool.describeError(error);
    }

    /** 返回被适配封装的基础工具 */
    public Tool getTool() {
        return tool;
    }
}
