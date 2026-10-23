package com.inspirationi.loop.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolValidatorTest {

    @Test
    void requireStringRejectsNonStringValues() {
        // 模型偶尔会把数字/布尔值塞进字符串参数。若在这里放行，调用方的
        // (String) 强转会抛 ClassCastException 并中止整轮 Agent。
        assertNotNull(ToolValidator.requireString(Map.of("path", 123), "path"),
                "整数必须被拒绝，而不是放行后由调用方强转");
        assertNotNull(ToolValidator.requireString(Map.of("path", true), "path"));
        assertNotNull(ToolValidator.requireString(Map.of("path", Map.of()), "path"));
    }

    @Test
    void requireStringAcceptsNonBlankString() {
        assertNull(ToolValidator.requireString(Map.of("path", "a.txt"), "path"));
        assertNotNull(ToolValidator.requireString(Map.of("path", "  "), "path"));
        assertNotNull(ToolValidator.requireString(Map.of(), "path"));
    }

    /**
     * 缺参时必须告诉模型「它实际传了什么」。
     * <p>
     * 实测本地小模型把 {@code pattern} 写成 {@code regex}，只回一句
     * {@code 'pattern' is required.} 它会换个名字再猜一次；把收到的参数名列出来，
     * 它才能看出是键名错了而不是值错了。
     */
    @Test
    void missingParameterListsReceivedKeys() {
        String err = ToolValidator.requireString(Map.of("regex", "TODO"), "pattern");

        assertNotNull(err);
        assertTrue(err.contains("pattern"), "错误信息要指明缺少的参数，实际: " + err);
        assertTrue(err.contains("regex"), "错误信息要列出实际收到的参数名，实际: " + err);
    }

    /** 一个参数都没传时不必列出空列表，但要有可执行的提示。 */
    @Test
    void missingParameterWithoutAnyInputStillHints() {
        String err = ToolValidator.requireString(Map.of(), "pattern");

        assertNotNull(err);
        assertTrue(err.toLowerCase().contains("required"), "实际: " + err);
    }

    // ==================== schema 类型校验 ====================

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string" },
                "limit": { "type": "integer" },
                "ratio": { "type": "number" },
                "raw": { "type": "boolean" },
                "items": { "type": "array" },
                "options": { "type": "object" }
              }
            }""";

    @Test
    void schemaTypeMismatchIsReportedWithParamAndExpectedType() {
        String err = ToolValidator.validateValueTypes(Map.of("path", 123), SCHEMA);

        assertNotNull(err);
        assertTrue(err.contains("'path'") && err.contains("must be a string"), "实际: " + err);
        assertTrue(err.contains("123"), "应说明实际收到的值，实际: " + err);
    }

    @Test
    void schemaAcceptsEachDeclaredType() {
        assertNull(ToolValidator.validateValueTypes(Map.of("path", "a.txt"), SCHEMA));
        assertNull(ToolValidator.validateValueTypes(Map.of("limit", 5), SCHEMA));
        assertNull(ToolValidator.validateValueTypes(Map.of("ratio", 1.5), SCHEMA));
        assertNull(ToolValidator.validateValueTypes(Map.of("raw", true), SCHEMA));
        assertNull(ToolValidator.validateValueTypes(Map.of("items", List.of("a")), SCHEMA));
        assertNull(ToolValidator.validateValueTypes(Map.of("options", Map.of("k", "v")), SCHEMA));
    }

    /** 数值/布尔放宽到字符串形式：模型经常这么写，getInt/getBoolean 本来就接受。 */
    @Test
    void schemaAcceptsStringFormsOfScalars() {
        assertNull(ToolValidator.validateValueTypes(Map.of("limit", "5"), SCHEMA));
        assertNull(ToolValidator.validateValueTypes(Map.of("ratio", "1.5"), SCHEMA));
        assertNull(ToolValidator.validateValueTypes(Map.of("raw", "false"), SCHEMA));

        assertNotNull(ToolValidator.validateValueTypes(Map.of("limit", "five"), SCHEMA),
                "非数字字符串不是整数");
    }

    @Test
    void schemaCheckIsSkippedWhenItCannotHelp() {
        // 没有 schema / schema 非法 / 参数未声明 / 值为 null —— 全部放行。
        // 这是防御性检查，不该因为 schema 写得随意或工具接受扩展参数而把调用堵死。
        assertNull(ToolValidator.validateValueTypes(Map.of("path", 1), null));
        assertNull(ToolValidator.validateValueTypes(Map.of("path", 1), "  "));
        assertNull(ToolValidator.validateValueTypes(Map.of("path", 1), "not json"));
        assertNull(ToolValidator.validateValueTypes(Map.of("path", 1), "{\"type\":\"object\"}"));
        assertNull(ToolValidator.validateValueTypes(Map.of("undeclared", 1), SCHEMA));
        assertNull(ToolValidator.validateValueTypes(Map.of(), SCHEMA));
        java.util.Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("path", null);
        assertNull(ToolValidator.validateValueTypes(withNull, SCHEMA));
    }
}
