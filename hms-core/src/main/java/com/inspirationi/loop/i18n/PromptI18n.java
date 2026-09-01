package com.inspirationi.loop.i18n;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词国际化资源持有器。
 * <p>
 * HMS 内置提示词（系统提示词 + 工具描述）默认使用优化后的中文版本。
 * 当检测到系统语言（Windows / Linux）非中文时，{@link PromptTranslationService}
 * 在 HMS 前置加载阶段通过大模型将中文提示词翻译为目标语言，并写入本缓存。
 * 此后工具 {@code description()} / 系统提示词通过 {@link #t(String, String)} 统一取值：
 * 翻译可用则返回翻译结果，否则回退为中文默认值。
 * <p>
 * 该机制保证：中文系统零额外开销（直接使用内置中文）；非中文系统自动获得
 * 与操作系统一致语言的提示词，无需修改任何工具代码。
 */
public final class PromptI18n {

    /** 全局系统提示词 key。 */
    public static final String KEY_GLOBAL_PROMPT = "global.prompt";
    /** 默认会话提示词 key。 */
    public static final String KEY_SESSION_PROMPT = "session.prompt";
    /** 子 Agent 系统提示词 key。 */
    public static final String KEY_SUBAGENT_PROMPT = "subagent.prompt";
    /** 子 Agent 会话级 systemPrompt key（创建子 Agent 时使用的简短提示词）。 */
    public static final String KEY_SUBAGENT_SESSION_PROMPT = "subagent.sessionPrompt";
    /** 协调者系统提示词 key。 */
    public static final String KEY_COORDINATOR_PROMPT = "coordinator.prompt";
    /** 协调者用户上下文 key。 */
    public static final String KEY_COORDINATOR_USER_CONTEXT = "coordinator.userContext";
    /** 全量压缩摘要提示词 key。 */
    public static final String KEY_FULL_COMPACT_PROMPT = "compact.full.prompt";
    /** 会话（Session Memory）压缩摘要提示词 key。 */
    public static final String KEY_SESSION_COMPACT_PROMPT = "compact.session.prompt";
    /** 微压缩截断占位符 key。 */
    public static final String KEY_MICRO_COMPACT_TRUNCATE_MARKER = "compact.micro.truncateMarker";
    /** 工具被用户取消结果文本 key。 */
    public static final String KEY_TOOL_CANCELLED = "tool.cancelled";
    /** 工具被 Hook 中止结果文本 key。 */
    public static final String KEY_TOOL_ABORTED_BY_HOOK = "tool.abortedByHook";
    /** 未知工具错误文本 key（含 %s 工具名占位符）。 */
    public static final String KEY_TOOL_UNKNOWN = "tool.unknown";
    /** 工具权限被拒绝结果文本 key。 */
    public static final String KEY_TOOL_PERMISSION_DENIED = "tool.permissionDenied";
    /** Agent 循环被用户中断标记 key。 */
    public static final String KEY_LOOP_INTERRUPTED = "loop.interrupted";
    /** Agent 循环达到最大迭代次数警告标记 key。 */
    public static final String KEY_LOOP_MAX_ITERATIONS = "loop.maxIterations";

    /** 工具描述 key：tool.{name}.description。 */
    public static String toolDescriptionKey(String toolName) {
        return "tool." + toolName + ".description";
    }

    /** 工具输入 Schema key：tool.{name}.schema。 */
    public static String toolSchemaKey(String toolName) {
        return "tool." + toolName + ".schema";
    }

    /** 是否启用提示词翻译（禁用时直接返回中文默认文本）。 */
    private static volatile boolean enabled = false;
    /** 目标语言基础代码（默认中文 "zh"）。 */
    private static volatile String targetLanguage = "zh";
    /** 翻译结果缓存：提示词 key → 翻译后文本。 */
    private static final Map<String, String> translations = new ConcurrentHashMap<>();

    private PromptI18n() {
    }

    /**
     * 取当前语言下的提示词文本。
     *
     * @param key      提示词标识（与翻译缓存对应）
     * @param zhText   中文默认文本
     * @return 翻译后的文本；未启用翻译或翻译缺失时返回中文默认文本
     */
    public static String t(String key, String zhText) {
        if (!enabled) {
            return zhText;
        }
        String translated = translations.get(key);
        return (translated != null && !translated.isBlank()) ? translated : zhText;
    }

    /** 设置目标语言（由翻译服务在启动时调用）。 */
    public static void setTargetLanguage(String language) {
        targetLanguage = (language == null || language.isBlank()) ? "zh" : language;
    }

    /** 获取目标语言。 */
    public static String getTargetLanguage() {
        return targetLanguage;
    }

    /** 启用 / 禁用翻译（禁用时全部使用中文默认文本）。 */
    public static void setEnabled(boolean flag) {
        enabled = flag;
    }

    /** 是否已启用翻译。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 应用翻译结果（合并进缓存）。 */
    public static void applyTranslations(Map<String, String> map) {
        if (map != null) {
            translations.putAll(map);
        }
    }

    /** 重置为初始状态（中文、未启用、清空缓存）。 */
    public static void reset() {
        translations.clear();
        enabled = false;
        targetLanguage = "zh";
    }
}
