package com.inspirationi.loop.util;

/**
 * 系统语言检测器 —— 在 HMS 前置加载时检测操作系统语言（支持 Windows / Linux）。
 * <p>
 * 检测优先级：
 * <ol>
 *   <li>环境变量 {@code LANG} / {@code LC_ALL} / {@code LC_MESSAGES} / {@code LANGUAGE}（Linux 常见，格式如 {@code zh_CN.UTF-8}、{@code en_US.utf8}）</li>
 *   <li>JVM 属性 {@code user.language} / {@code user.country}（Windows 常见，如 {@code zh} / {@code CN}）</li>
 * </ol>
 * 通过 {@link #normalize(String)} 归一化为基础语言代码（{@code en_US.UTF-8 → en}、{@code zh-CN → zh}）。
 */
public final class SystemLanguageDetector {

    /** 无法检测到系统语言时的回退默认语言代码（英文）。 */
    private static final String DEFAULT_LANGUAGE = "en";

    private SystemLanguageDetector() {
    }

    /** 操作系统语言环境变量（按优先级排序）。 */
    private static final String[] LANG_ENV_VARS = {"LANG", "LC_ALL", "LC_MESSAGES", "LANGUAGE"};

    /**
     * 检测系统语言，返回归一化的基础语言代码（如 {@code zh}、{@code en}、{@code ja}）。
     * 无法检测时回退为 {@code en}。
     */
    public static String detectBaseLanguage() {
        return normalize(detectRawLanguageTag());
    }

    /**
     * 检测原始语言标签（如 {@code zh_CN.UTF-8}、{@code en_US}、{@code zh}、{@code en-US}）。
     */
    public static String detectRawLanguageTag() {
        // Linux：优先读取环境变量
        for (String envVar : LANG_ENV_VARS) {
            String value = System.getenv(envVar);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        // Windows / JVM：user.language + user.country
        String lang = System.getProperty("user.language");
        String country = System.getProperty("user.country");
        if (lang != null && !lang.isBlank()) {
            return (country == null || country.isBlank()) ? lang : lang + "_" + country;
        }
        return DEFAULT_LANGUAGE;
    }

    /**
     * 归一化为基础语言代码。
     * <p>
     * 去除编码与区域修饰：{@code zh_CN.UTF-8 → zh}、{@code zh-Hant-TW → zh}、{@code en_US.utf8 → en}。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        String tag = raw.trim();
        // 去掉编码部分：zh_CN.UTF-8 → zh_CN
        int dot = tag.indexOf('.');
        if (dot > 0) {
            tag = tag.substring(0, dot);
        }
        // 去掉区域修饰（下划线 / 连字符）：zh_CN → zh、en-US → en
        int underscore = tag.indexOf('_');
        if (underscore > 0) {
            tag = tag.substring(0, underscore);
        }
        int dash = tag.indexOf('-');
        if (dash > 0) {
            tag = tag.substring(0, dash);
        }
        return tag.toLowerCase();
    }

    /** 是否为中文系统（zh、zh-CN、zh-TW、zh-HK 等）。 */
    public static boolean isChinese(String baseLanguage) {
        return baseLanguage != null && baseLanguage.toLowerCase().startsWith("zh");
    }

    /** 是否 Windows 操作系统。 */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 是否 Linux 操作系统。 */
    public static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("nux");
    }

    /**
     * 将基础语言代码转换为面向大模型的英文语言名称（用于翻译指令）。
     * 支持常见语言；未知语言直接返回原代码。
     */
    public static String toEnglishName(String baseLanguage) {
        if (baseLanguage == null) {
            return "English";
        }
        return switch (baseLanguage.toLowerCase()) {
            case "zh" -> "Simplified Chinese (简体中文)";
            case "zh-tw", "zh-hk", "zh-mo" -> "Traditional Chinese (繁體中文)";
            case "en" -> "English";
            case "ja", "jp" -> "Japanese (日本語)";
            case "ko", "kr" -> "Korean (한국어)";
            case "fr" -> "French (Français)";
            case "de" -> "German (Deutsch)";
            case "es" -> "Spanish (Español)";
            case "ru" -> "Russian (Русский)";
            case "pt" -> "Portuguese (Português)";
            case "it" -> "Italian (Italiano)";
            case "nl" -> "Dutch (Nederlands)";
            case "ar" -> "Arabic (العربية)";
            case "hi" -> "Hindi (हिन्दी)";
            case "vi" -> "Vietnamese (Tiếng Việt)";
            case "th" -> "Thai (ไทย)";
            default -> baseLanguage;
        };
    }

    /** 显示用语言名称（如 {@code zh → 简体中文}、{@code en → English}）。 */
    public static String displayName(String baseLanguage) {
        if (baseLanguage == null) {
            return "Unknown";
        }
        return switch (baseLanguage.toLowerCase()) {
            case "zh" -> "简体中文";
            case "en" -> "English";
            case "ja" -> "日本語";
            case "ko" -> "한국어";
            case "fr" -> "Français";
            case "de" -> "Deutsch";
            case "es" -> "Español";
            case "ru" -> "Русский";
            case "pt" -> "Português";
            case "it" -> "Italiano";
            case "nl" -> "Nederlands";
            case "ar" -> "العربية";
            case "hi" -> "हिन्दी";
            case "vi" -> "Tiếng Việt";
            case "th" -> "ไทย";
            default -> baseLanguage;
        };
    }
}
