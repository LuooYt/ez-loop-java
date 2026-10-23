package com.inspirationi.loop.tool;

import java.nio.file.Path;

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
}
