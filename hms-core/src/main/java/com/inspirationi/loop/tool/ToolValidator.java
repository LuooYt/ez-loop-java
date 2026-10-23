package com.inspirationi.loop.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具输入验证器 —— 消除 Tool 实现中的重复验证代码。
 * <p>
 * 所有 validate* 方法：返回 null 表示验证通过，返回 String 表示错误消息。
 * 典型用法：
 * <pre>
 *   String err = ToolValidator.requireString(input, "file_path");
 *   if (err != null) return err;
 * </pre>
 */
public final class ToolValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolValidator.class);

    /** 解析 inputSchema 用 —— 复用同一个实例，避免每次调用都新建。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * schema 文本 → 「参数名 → 声明类型」。
     * <p>
     * 工具实例是单例、schema 是常量，按文本缓存即可省掉每次工具调用的 JSON 解析。
     * 设上限是因为 schema 也可能来自 MCP 服务端或翻译后的提示词，没有天然的有限性。
     */
    private static final ConcurrentHashMap<String, Map<String, String>> TYPE_CACHE =
            new ConcurrentHashMap<>();
    private static final int TYPE_CACHE_LIMIT = 256;

    private ToolValidator() {}

    /**
     * 验证必填 String 参数（必须是 String，且非空白）。
     * <p>
     * 类型检查不是多余的一道：调用方在通过校验后都会直接 {@code (String) input.get(...)}，
     * 模型把数字或布尔值塞进来时会抛 {@code ClassCastException}，而工具执行层的异常
     * 会中止整轮 Agent。宁可在参数校验处退回一条可读的错误文本。
     *
     * @return null 表示通过，否则返回错误消息
     */
    public static String requireString(java.util.Map<String, Object> input, String paramName) {
        Object value = input.get(paramName);
        if (value == null) {
            // 列出实际收到的参数名：小模型常把参数改名（pattern → regex、file_path →
            // path），只说「缺 pattern」它会换个名字接着猜，把迭代次数耗在试错上。
            return "Error: '" + paramName + "' is required." + receivedKeys(input);
        }
        if (!(value instanceof String s)) {
            return "Error: '" + paramName + "' must be a string.";
        }
        if (s.isBlank()) {
            return "Error: '" + paramName + "' must not be empty.";
        }
        return null;
    }

    /** 渲染「本次实际收到的参数名」，空输入时不追加（避免噪声）。 */
    private static String receivedKeys(java.util.Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return " Received parameters: " + input.keySet() + ".";
    }

    /**
     * 获取 String 参数（带默认值）。
     */
    public static String getString(java.util.Map<String, Object> input, String paramName, String defaultValue) {
        Object value = input.get(paramName);
        if (value instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    /**
     * 安全获取 int 参数（带默认值，防止 ClassCastException）。
     */
    public static int getInt(java.util.Map<String, Object> input, String paramName, int defaultValue) {
        Object value = input.get(paramName);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }

    /**
     * 安全获取 boolean 参数（带默认值）。
     */
    public static boolean getBoolean(java.util.Map<String, Object> input, String paramName, boolean defaultValue) {
        Object value = input.get(paramName);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    /**
     * 验证文件路径在工作目录内（防止路径遍历）。
     * @return null 表示通过，否则返回错误消息
     */
    public static String validatePathInWorkDir(String filePath, Path workDir) {
        if (filePath == null || filePath.isBlank()) {
            return "Error: file path is required.";
        }
        Path resolved = workDir.resolve(filePath).normalize();
        if (!resolved.startsWith(workDir.normalize())) {
            return "Error: Path traversal not allowed. Path must be within the working directory.";
        }
        return null;
    }

    /**
     * 解析并验证路径（合并 resolve + normalize + traversal check）。
     * @return 解析后的路径，如验证失败则为 null（错误通过 errorHolder 返回）
     */
    public static Path resolveSafePath(String filePath, Path workDir) {
        if (filePath == null || filePath.isBlank()) return null;
        Path resolved = workDir.resolve(filePath).normalize();
        if (!resolved.startsWith(workDir.normalize())) return null;
        return resolved;
    }

    // ==================== 按工具自述的 schema 校验参数类型 ====================

    /**
     * 按工具自己声明的 {@link Tool#inputSchema()} 校验参数类型。
     * <p>
     * <b>为什么要在入口统一做，而不是每个工具各自 {@link #requireString}</b>：
     * 模型给的参数会先经过好几处直接强转 —— 权限确认里的
     * {@code activityDescription(input)}、工具实现里的 {@code (String) input.get(...)}。
     * 任何一处遇上错类型就抛 {@code ClassCastException}，而这条异常不在工具调用的
     * try/catch 之内，会中止整轮 Agent，模型连「改一下参数再试」的机会都没有。
     * 逐个工具加校验既漏得掉（新工具不会记得加），也护不住 schema 之外的路径；
     * 在参数解析之后、任何强转之前统一拦一道，才是一次性收敛。
     * <p>
     * 只校验<b>传了且非 null</b> 的参数：缺参交给各工具自己的必填提示（它们往往
     * 比这里更清楚该怎么补），本方法只回答「这个值能不能按声明类型用」。
     * schema 解析失败、参数未在 schema 中声明、类型声明无法识别，一律放行 ——
     * 这是防御性检查，不该因为 schema 写得随意就把工具变成不可用。
     * <p>
     * 数值/布尔允许字符串形式（{@code "5"}、{@code "false"}）：这是模型常见的写法，
     * 且 {@link #getInt} / {@link #getBoolean} 本来就接受，没必要收窄。
     *
     * @return null 表示通过，否则返回可直接回传给模型的错误文本
     */
    public static String validateValueTypes(Map<String, Object> input, String jsonSchema) {
        if (input == null || input.isEmpty() || jsonSchema == null || jsonSchema.isBlank()) {
            return null;
        }
        Map<String, String> declared = declaredTypes(jsonSchema);
        if (declared.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String type = declared.get(entry.getKey());
            Object value = entry.getValue();
            if (type == null || value == null) {
                continue;
            }
            if (!accepts(type, value)) {
                return "Error: '" + entry.getKey() + "' must be " + typePhrase(type)
                        + ", but received " + describe(value) + ".";
            }
        }
        return null;
    }

    /** 「参数名 → 声明类型」；schema 非法或没有 properties 时为空表（= 不做任何校验）。 */
    private static Map<String, String> declaredTypes(String jsonSchema) {
        Map<String, String> cached = TYPE_CACHE.get(jsonSchema);
        if (cached != null) {
            return cached;
        }
        Map<String, String> parsed = parseDeclaredTypes(jsonSchema);
        if (TYPE_CACHE.size() < TYPE_CACHE_LIMIT) {
            TYPE_CACHE.putIfAbsent(jsonSchema, parsed);
        }
        return parsed;
    }

    private static Map<String, String> parseDeclaredTypes(String jsonSchema) {
        try {
            JsonNode properties = MAPPER.readTree(jsonSchema).path("properties");
            if (!properties.isObject()) {
                return Map.of();
            }
            Map<String, String> types = new HashMap<>();
            properties.fields().forEachRemaining(field -> {
                JsonNode type = field.getValue().path("type");
                // 只认 "type": "string" 这种简单声明；["string","null"]、
                // oneOf/$ref 等复杂形式留给工具自己判断，这里不做猜测。
                if (type.isTextual()) {
                    types.put(field.getKey(), type.asText());
                }
            });
            return Map.copyOf(types);
        } catch (Exception e) {
            log.debug("Ignoring unparsable tool input schema: {}", e.toString());
            return Map.of();
        }
    }

    private static boolean accepts(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Number || isNumericString(value, true);
            case "number" -> value instanceof Number || isNumericString(value, false);
            case "boolean" -> value instanceof Boolean || isBooleanString(value);
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true;   // 未识别的类型声明不拦
        };
    }

    private static boolean isNumericString(Object value, boolean integerOnly) {
        if (!(value instanceof String s)) {
            return false;
        }
        try {
            if (integerOnly) {
                Integer.parseInt(s.trim());
            } else {
                Double.parseDouble(s.trim());
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isBooleanString(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        return "true".equalsIgnoreCase(s.trim()) || "false".equalsIgnoreCase(s.trim());
    }

    /** 「a string」这类冠词，让错误文本读起来是句子。 */
    private static String typePhrase(String type) {
        return switch (type) {
            case "string" -> "a string";
            case "integer" -> "an integer";
            case "number" -> "a number";
            case "boolean" -> "a boolean";
            case "array" -> "an array";
            case "object" -> "an object";
            default -> "of type " + type;
        };
    }

    /** 描述实际收到的值，让模型看得出是自己传错了类型。 */
    private static String describe(Object value) {
        String text = String.valueOf(value);
        if (text.length() > 40) {
            text = text.substring(0, 40) + "…";
        }
        return value.getClass().getSimpleName() + " (" + text + ")";
    }
}
