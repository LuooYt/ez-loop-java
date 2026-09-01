package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config 工具 —— 获取或设置配置值，纯内存管理。
 * <p>
 * SDK 场景：不再读写 ~/.hms-core/config.json 文件。
 * 配置存储在 ToolContext 的 CONFIG_STORE 中，SDK 调用方可随时从 ToolContext 读取。
 * </p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li><b>action</b>（必填）—— "get"、"set" 或 "list"</li>
 *   <li><b>key</b>（get/set 时必填）—— 配置键名</li>
 *   <li><b>value</b>（set 时必填）—— 配置值</li>
 * </ul>
 */
public class ConfigTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ConfigTool.class);

    /** ToolContext 中配置存储的键名 */
    private static final String CONFIG_STORE_KEY = "CONFIG_STORE";

    /**
     * 返回工具名称（"Config"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "Config";
    }

    /**
     * 返回工具描述，说明配置的获取、设置与列出能力（纯内存存储）。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            获取、设置或列出配置值。配置存储在内存中。

            可用设置包括：
             - language：首选响应语言（例如 "zh-CN"、"en"）
             - theme：颜色主题（light/dark）
             - model：使用的 AI 模型
             - verbose：启用详细输出（true/false）
             - timeout：默认命令超时时间（秒）
             - permissions：权限模式（ask/auto/deny）

            使用 'get' 读取、'set' 修改、'list' 查看所有设置。
            """);
    }

    /**
     * 返回输入 JSON Schema，定义 action（必填）、key、value 参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "description": "操作类型：get、set 或 list",
                      "enum": ["get", "set", "list"]
                    },
                    "key": {
                      "type": "string",
                      "description": "配置键名（get/set 操作必填）"
                    },
                    "value": {
                      "type": "string",
                      "description": "配置值（set 操作必填）"
                    }
                  },
                  "required": ["action"]
                }""");
    }

    /**
     * 该工具会修改内存配置，标记为非只读。
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * 执行配置操作：校验 action 参数并分发到 get/set/list 三种操作，
     * 配置值存储在 ToolContext 的纯内存存储中。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String action = (String) input.get("action");
        if (action == null || action.isBlank()) {
            return errorJson("Parameter 'action' is required, valid values: get, set, list");
        }
        action = action.trim().toLowerCase();

        // 获取或初始化配置存储（纯内存）
        ConcurrentHashMap<String, String> configStore = getOrInitStore(context);

        return switch (action) {
            case "get" -> {
                String key = (String) input.get("key");
                if (key == null || key.isBlank()) {
                    yield errorJson("'get' action requires 'key' parameter");
                }
                yield executeGet(key, configStore);
            }
            case "set" -> {
                String key = (String) input.get("key");
                if (key == null || key.isBlank()) {
                    yield errorJson("'set' action requires 'key' parameter");
                }
                yield executeSet(key, input, configStore);
            }
            case "list" -> executeList(configStore);
            default -> errorJson("Invalid action: '" + action + "'. Valid values: get, set, list");
        };
    }

    /**
     * 生成用于界面展示的执行摘要，标识正在进行的配置操作（获取/设置/列出）。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        String action = (String) input.getOrDefault("action", "?");
        String key = (String) input.getOrDefault("key", "?");
        if ("list".equalsIgnoreCase(action)) {
            return "⚙️ Listing all config";
        }
        if ("set".equalsIgnoreCase(action)) {
            return "⚙️ Setting config: " + key;
        }
        return "⚙️ Getting config: " + key;
    }

    /* ------------------------------------------------------------------ */
    /*  get / set / list 具体实现                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 执行 get 操作：优先从内存存储读取，其次回退到系统属性，未找到时返回错误信息。
     */
    private String executeGet(String key, ConcurrentHashMap<String, String> configStore) {
        String value = configStore.get(key);
        if (value == null) {
            value = System.getProperty(key);
        }

        if (value == null) {
            return """
                    {"action": "get", "key": "%s", "value": null, "found": false, \
                    "message": "Config key '%s' not found"}"""
                    .formatted(escapeJson(key), escapeJson(key));
        }

        return """
                {"action": "get", "key": "%s", "value": "%s", "found": true}"""
                .formatted(escapeJson(key), escapeJson(value));
    }

    /**
     * 执行 set 操作：校验 value 参数并将配置写入内存存储，返回更新前后的值。
     */
    private String executeSet(String key, Map<String, Object> input,
                              ConcurrentHashMap<String, String> configStore) {
        String value = (String) input.get("value");
        if (value == null) {
            return errorJson("'set' action requires 'value' parameter");
        }

        String oldValue = configStore.get(key);
        configStore.put(key, value);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"action\": \"set\", \"key\": \"").append(escapeJson(key));
        sb.append("\", \"value\": \"").append(escapeJson(value)).append("\"");
        if (oldValue != null) {
            sb.append(", \"previous_value\": \"").append(escapeJson(oldValue)).append("\"");
        }
        sb.append(", \"success\": true}");
        return sb.toString();
    }

    /**
     * 执行 list 操作：将内存中的全部配置项按键排序后以 JSON 对象形式列出。
     */
    private String executeList(ConcurrentHashMap<String, String> configStore) {
        if (configStore.isEmpty()) {
            return "{\"action\": \"list\", \"count\": 0, \"message\": \"No configuration set\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"action\": \"list\", \"count\": ").append(configStore.size());
        sb.append(", \"settings\": {");
        boolean first = true;
        for (var entry : new java.util.TreeMap<>(configStore).entrySet()) {
            if (!first) sb.append(", ");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\": \"")
                    .append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /*  内存存储初始化                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * 从 ToolContext 获取配置存储，不存在时初始化一个新的纯内存存储并写回上下文。
     */
    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, String> getOrInitStore(ToolContext context) {
        ConcurrentHashMap<String, String> store = context.getOrDefault(CONFIG_STORE_KEY, null);
        if (store != null) {
            return store;
        }

        // 纯内存初始化，不读文件
        store = new ConcurrentHashMap<>();
        context.set(CONFIG_STORE_KEY, store);
        return store;
    }

    /* ------------------------------------------------------------------ */
    /*  JSON 转义工具                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * 转义字符串中的 JSON 特殊字符（反斜杠、引号、换行等），防止破坏 JSON 格式。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 构建统一的错误响应 JSON 字符串。
     */
    private String errorJson(String message) {
        return "{\"error\": true, \"message\": \"%s\"}".formatted(escapeJson(message));
    }
}