package com.inspirationi.loop.tool;

import org.junit.jupiter.api.Test;

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
}
