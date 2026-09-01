package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

/**
 * 用户提问工具 —— AI 在执行过程中向用户提问并获取回答。
 * <p>
 * 对应 的 AskUserQuestionTool，允许 AI 在需要澄清信息时
 * 暂停执行并向用户提问。用户的回答会作为工具返回值传回 AI。
 * <p>
 * 依赖 ToolContext 中注册的 {@code USER_INPUT_CALLBACK} 回调函数，
 * 该回调由 ReplSession 在启动时设置，用于读取终端用户输入。
 */
public class AskUserQuestionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);

    /** ToolContext 中用于读取用户输入的回调 Key */
    public static final String USER_INPUT_CALLBACK = "ask_user_input_callback";

    /** ToolContext 中用于结构化 AskUser 的回调 Key（question, options → answer） */
    public static final String ASK_USER_STRUCTURED_CALLBACK = "ask_user_structured_callback";

    /**
     * 返回工具名称（"AskUserQuestion"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "AskUserQuestion";
    }

    /**
     * 返回工具描述，说明在需要澄清信息时向用户提问并等待回答的用途。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()),
                "向用户提问并等待回答。当你需要澄清、确认或获取额外信息以推进任务时使用。问题应当清晰、具体、可执行。");
    }

    /**
     * 返回输入 JSON Schema，定义 question（必填）与 options（可选）两个参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
                {
                  "type": "object",
                  "properties": {
                    "question": {
                      "type": "string",
                      "description": "要问用户的问题。应当清晰且具体。"
                    },
                    "options": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "可选的选项列表，供用户选择"
                    }
                  },
                  "required": ["question"]
                }
                """);
    }

    /**
     * 该工具只读取用户输入、不修改外部状态，标记为只读。
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 生成用于界面展示的执行摘要，固定返回"Asking user a question..."。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        return "Asking user a question...";
    }

    /**
     * 执行提问：校验 question 参数，优先使用结构化回调（支持选项交互选择），
     * 失败时回退到简单文本回调读取终端用户输入。
     */
    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> input, ToolContext context) {
        String question = (String) input.get("question");
        if (question == null || question.isBlank()) {
            return "Error: question parameter is required";
        }

        // 解析选项
        java.util.List<String> options = null;
        if (input.containsKey("options")) {
            options = (java.util.List<String>) input.get("options");
        }

        // 优先使用结构化回调（支持交互式选择）
        Object structuredCb = context.get(ASK_USER_STRUCTURED_CALLBACK);
        log.debug("Structured callback type: {}", structuredCb != null ? structuredCb.getClass().getName() : "null");
        if (structuredCb instanceof java.util.function.BiFunction<?, ?, ?> biFn) {
            try {
                var askFn = (java.util.function.BiFunction<String, java.util.List<String>, String>) biFn;
                String userResponse = askFn.apply(question, options);
                if (userResponse == null || userResponse.isBlank()) {
                    return "(User provided no response)";
                }
                return "User response: " + userResponse;
            } catch (Exception e) {
                log.debug("Structured callback failed, falling back", e);
            }
        } else {
            log.debug("Structured callback not a BiFunction, got: {}",
                    structuredCb != null ? structuredCb.getClass().getInterfaces()[0] : "null");
        }

        // 回退到简单文本回调
        Object callback = context.get(USER_INPUT_CALLBACK);
        if (callback == null) {
            log.warn("User input callback not registered, returning default response");
            return "Error: User input not available in current environment";
        }

        if (!(callback instanceof Function<?, ?> inputFn)) {
            return "Error: Invalid user input callback type";
        }

        try {
            Function<String, String> askUser = (Function<String, String>) inputFn;

            // 构建提问文本
            StringBuilder prompt = new StringBuilder();
            prompt.append("\n  🤔 AI is asking you a question:\n");
            prompt.append("  ").append("─".repeat(50)).append("\n");
            prompt.append("  ").append(question).append("\n");

            if (options != null && !options.isEmpty()) {
                prompt.append("\n  Options:\n");
                for (int i = 0; i < options.size(); i++) {
                    prompt.append("    ").append(i + 1).append(". ").append(options.get(i)).append("\n");
                }
            }

            prompt.append("  ").append("─".repeat(50)).append("\n");

            String userResponse = askUser.apply(prompt.toString());
            if (userResponse == null || userResponse.isBlank()) {
                return "(User provided no response)";
            }
            return "User response: " + userResponse;

        } catch (Exception e) {
            log.debug("Failed to get user input", e);
            return "Error: Failed to get user input - " + e.getMessage();
        }
    }
}
