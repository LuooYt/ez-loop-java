package com.inspirationi.loop.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.inspirationi.loop.api.DefaultHmsSessionManager;
import com.inspirationi.loop.api.DefaultPromptManager;
import com.inspirationi.loop.api.PromptManager;
import com.inspirationi.loop.core.CoordinatorMode;
import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.core.AgentToolExecutor;
import com.inspirationi.loop.core.compact.FullCompact;
import com.inspirationi.loop.core.compact.MicroCompact;
import com.inspirationi.loop.core.compact.SessionMemoryCompact;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolRegistry;
import com.inspirationi.loop.tool.impl.AgentTool;
import com.inspirationi.loop.util.SystemLanguageDetector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词翻译服务 —— 在 HMS 前置加载阶段检测系统语言（Windows / Linux），
 * 若系统语言非中文，则通过大模型（ChatModel）将内置中文提示词批量翻译为目标语言。
 * <p>
 * 翻译流程：
 * <ol>
 *   <li>检测系统语言并设置 {@link PromptI18n} 目标语言</li>
 *   <li>系统为中文 → 直接使用内置中文提示词，无需翻译</li>
 *   <li>系统非中文 → 收集全部内置提示词（系统提示词 + 工具描述/Schema），
 *       分块调用大模型翻译，解析结果写入 {@link PromptI18n} 缓存</li>
 *   <li>更新 {@link PromptManager} 中的全局系统提示词</li>
 * </ol>
 * 工具描述通过 {@link PromptI18n#t(String, String)} 在下次 {@code toCallbacks()} 时自动生效，
 * 无需重建任何对象。
 */
public class PromptTranslationService {

    private static final Logger log = LoggerFactory.getLogger(PromptTranslationService.class);
    /** JSON 序列化 / 反序列化器，用于构造翻译请求与解析模型返回。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 每块最多翻译的提示词条目数（避免单次输出超长）。 */
    private static final int CHUNK_SIZE = 8;

    /**
     * 开关：系统属性 hms-core.i18n.enabled 或环境变量 HMS_CORE_I18N_ENABLED。
     * <p>
     * Spring 装配下默认<b>关闭</b>（见 {@code AppConfig.promptTranslationService}）——
     * 启动时的串行大模型调用会把启动拖到数十秒，容器健康检查等不到。这两个开关只在
     * {@code enabledByConfig} 已为 true 时才有意义，用于运行时临时禁用。
     */
    private static final String ENABLED_PROPERTY = "hms-core.i18n.enabled";
    private static final String ENABLED_ENV = "HMS_CORE_I18N_ENABLED";

    /** 大模型实例，用于执行提示词翻译。 */
    private final ChatModel chatModel;
    /** 提示词管理器，翻译完成后用于更新全局系统提示词。 */
    private final PromptManager promptManager;
    /** 工具注册中心，用于收集全部工具描述 / Schema 提示词。 */
    private final ToolRegistry toolRegistry;
    /** 是否通过配置开启翻译（false 时强制关闭）。 */
    private final boolean enabledByConfig;

    /**
     * 创建翻译服务并<b>启用</b>翻译 —— 供直接编程使用（不经 Spring 装配）。
     * <p>
     * 注意启用即意味着 {@link #translateAllIfNeeded()} 会同步发起多次大模型调用。
     * Spring 自动装配走的是四参数版本，且默认关闭。
     *
     * @param chatModel     大模型实例
     * @param promptManager 提示词管理器
     * @param toolRegistry  工具注册中心
     */
    public PromptTranslationService(ChatModel chatModel, PromptManager promptManager, ToolRegistry toolRegistry) {
        this(chatModel, promptManager, toolRegistry, true);
    }

    /**
     * 创建翻译服务。
     *
     * @param chatModel       大模型实例
     * @param promptManager   提示词管理器
     * @param toolRegistry    工具注册中心
     * @param enabledByConfig 是否通过配置启用翻译（false 时强制关闭）
     */
    public PromptTranslationService(ChatModel chatModel, PromptManager promptManager, ToolRegistry toolRegistry,
                                    boolean enabledByConfig) {
        this.chatModel = chatModel;
        this.promptManager = promptManager;
        this.toolRegistry = toolRegistry;
        this.enabledByConfig = enabledByConfig;
    }

    /**
     * 前置加载入口：检测系统语言，若非中文则通过大模型翻译全部内置提示词。
     * 该方法应在应用启动（Bean 就绪）后调用。
     */
    public void translateAllIfNeeded() {
        if (!isEnabled()) {
            log.info("[PromptI18n] 提示词翻译已通过配置关闭（{} / {}）", ENABLED_PROPERTY, ENABLED_ENV);
            return;
        }

        String baseLanguage = SystemLanguageDetector.detectBaseLanguage();
        PromptI18n.setTargetLanguage(baseLanguage);

        if (SystemLanguageDetector.isChinese(baseLanguage)) {
            PromptI18n.setEnabled(false);
            log.info("[PromptI18n] 系统语言为中文（{}），直接使用内置中文提示词", baseLanguage);
            return;
        }

        String osType = SystemLanguageDetector.isWindows() ? "Windows" : SystemLanguageDetector.isLinux() ? "Linux" : "Unknown";
        log.info("[PromptI18n] 系统语言为 {}（{}），开始通过大模型翻译内置提示词...",
                SystemLanguageDetector.displayName(baseLanguage), osType);

        Map<String, String> texts = collectAllPromptTexts();
        Map<String, String> translated = translate(texts, baseLanguage);

        if (translated.isEmpty()) {
            log.warn("[PromptI18n] 大模型翻译未返回结果，回退使用内置中文提示词");
            PromptI18n.setEnabled(false);
            return;
        }

        PromptI18n.applyTranslations(translated);
        PromptI18n.setEnabled(true);

        // 更新全局系统提示词（PromptManager 持有，新会话生效）
        String global = translated.get(PromptI18n.KEY_GLOBAL_PROMPT);
        if (global != null && !global.isBlank()) {
            try {
                promptManager.updateGlobalPrompt(global);
                log.info("[PromptI18n] 全局系统提示词已更新为目标语言（{} 字符）", global.length());
            } catch (Exception e) {
                log.warn("[PromptI18n] 更新全局提示词失败: {}", e.getMessage());
            }
        }

        log.info("[PromptI18n] 内置提示词翻译完成（{} 条，目标语言 {}）", translated.size(),
                SystemLanguageDetector.displayName(baseLanguage));
    }

    /** 收集全部内置提示词（key → 中文文本）。 */
    private Map<String, String> collectAllPromptTexts() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put(PromptI18n.KEY_GLOBAL_PROMPT, DefaultPromptManager.DEFAULT_GLOBAL_PROMPT);
        texts.put(PromptI18n.KEY_SESSION_PROMPT, DefaultHmsSessionManager.DEFAULT_SESSION_PROMPT);
        texts.put(PromptI18n.KEY_SUBAGENT_SESSION_PROMPT, DefaultHmsSessionManager.DEFAULT_SUBAGENT_SYSTEM_PROMPT);
        texts.put(PromptI18n.KEY_SUBAGENT_PROMPT, AgentTool.DEFAULT_SUBAGENT_SYSTEM_PROMPT);
        texts.put(PromptI18n.KEY_COORDINATOR_PROMPT, CoordinatorMode.getCoordinatorSystemPrompt());
        texts.put(PromptI18n.KEY_COORDINATOR_USER_CONTEXT, CoordinatorMode.getCoordinatorUserContext());
        texts.put(PromptI18n.KEY_FULL_COMPACT_PROMPT, FullCompact.FULL_COMPACT_PROMPT);
        texts.put(PromptI18n.KEY_SESSION_COMPACT_PROMPT, SessionMemoryCompact.SUMMARY_PROMPT);
        texts.put(PromptI18n.KEY_MICRO_COMPACT_TRUNCATE_MARKER, MicroCompact.TRUNCATED_MARKER);
        texts.put(PromptI18n.KEY_TOOL_CANCELLED, AgentToolExecutor.DEFAULT_TOOL_CANCELLED);
        texts.put(PromptI18n.KEY_TOOL_ABORTED_BY_HOOK, AgentToolExecutor.DEFAULT_TOOL_ABORTED);
        texts.put(PromptI18n.KEY_TOOL_UNKNOWN, AgentToolExecutor.DEFAULT_UNKNOWN_TOOL);
        texts.put(PromptI18n.KEY_TOOL_PERMISSION_DENIED, AgentToolExecutor.DEFAULT_PERMISSION_DENIED);
        texts.put(PromptI18n.KEY_LOOP_INTERRUPTED, AgentLoop.DEFAULT_LOOP_INTERRUPTED);
        texts.put(PromptI18n.KEY_LOOP_MAX_ITERATIONS, AgentLoop.DEFAULT_LOOP_MAX_ITERATIONS);

        if (toolRegistry != null) {
            for (Tool tool : toolRegistry.getTools()) {
                texts.put(PromptI18n.toolDescriptionKey(tool.name()), tool.description());
                texts.put(PromptI18n.toolSchemaKey(tool.name()), tool.inputSchema());
            }
        }
        return texts;
    }

    /**
     * 分块调用大模型，将中文提示词批量翻译为目标语言。
     * <p>
     * 纯文本（系统提示词 + 工具描述）直接批量翻译；
     * JSON Schema（工具输入参数描述）采用字段级翻译，
     * 仅翻译其中的 description 字段值，保持 JSON 结构完全不变。
     *
     * @return 翻译结果映射（key → 翻译后文本）；失败或异常时返回空 Map（调用方回退中文）
     */
    private Map<String, String> translate(Map<String, String> texts, String targetLanguage) {
        String languageName = SystemLanguageDetector.toEnglishName(targetLanguage);

        // 分离 JSON Schema 与纯文本
        Map<String, String> plainTexts = new LinkedHashMap<>();
        Map<String, String> schemaTexts = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : texts.entrySet()) {
            if (e.getKey().endsWith(".schema")) {
                schemaTexts.put(e.getKey(), e.getValue());
            } else {
                plainTexts.put(e.getKey(), e.getValue());
            }
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.putAll(translatePlain(plainTexts, languageName));
        result.putAll(translateSchemas(schemaTexts, languageName));
        return result;
    }

    /** 纯文本批量翻译（系统提示词 + 工具描述）。 */
    private Map<String, String> translatePlain(Map<String, String> texts, String languageName) {
        List<String> keys = new ArrayList<>(texts.keySet());
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i += CHUNK_SIZE) {
            Map<String, String> chunk = new LinkedHashMap<>();
            for (String key : keys.subList(i, Math.min(i + CHUNK_SIZE, keys.size()))) {
                chunk.put(key, texts.get(key));
            }
            try {
                Map<String, String> translatedChunk = translateChunk(chunk, languageName);
                if (translatedChunk != null) {
                    result.putAll(translatedChunk);
                }
            } catch (Exception e) {
                log.warn("[PromptI18n] 纯文本翻译失败（第 {}/{} 块）: {}", i / CHUNK_SIZE + 1,
                        (keys.size() + CHUNK_SIZE - 1) / CHUNK_SIZE, e.getMessage());
            }
        }
        return result;
    }

    /**
     * JSON Schema 字段级翻译：仅提取各 {@code description} 字段的值进行翻译，
     * 保持 JSON 结构完全不变，避免模型破坏 schema 语法。
     */
    private Map<String, String> translateSchemas(Map<String, String> schemaTexts, String languageName) {
        if (schemaTexts.isEmpty()) {
            return Map.of();
        }

        // 1. 解析所有 schema，收集 description 字段（父节点 + 字段名 + 原文）
        Map<String, JsonNode> roots = new LinkedHashMap<>();
        List<SchemaDesc> descriptions = new ArrayList<>();
        int[] counter = {0};
        for (Map.Entry<String, String> e : schemaTexts.entrySet()) {
            try {
                JsonNode root = MAPPER.readTree(e.getValue());
                roots.put(e.getKey(), root);
                collectSchemaDescriptions(root, descriptions, e.getKey(), counter);
            } catch (Exception ex) {
                log.debug("[PromptI18n] Schema 解析失败（保持中文）: {} - {}", e.getKey(), ex.getMessage());
            }
        }
        if (descriptions.isEmpty()) {
            return Map.of();
        }

        // 2. 批量翻译所有 description 片段
        Map<String, String> descTexts = new LinkedHashMap<>();
        for (SchemaDesc d : descriptions) {
            JsonNode node = d.parent().get(d.fieldName());
            if (node != null && node.isTextual()) {
                descTexts.put(d.key(), node.asText());
            }
        }
        Map<String, String> translated = translatePlain(descTexts, languageName);

        // 3. 填回翻译结果（保持 JSON 结构）
        for (SchemaDesc d : descriptions) {
            String t = translated.get(d.key());
            if (t != null && !t.isBlank()) {
                d.parent().set(d.fieldName(), TextNode.valueOf(t));
            }
        }

        // 4. 序列化回 schema 字符串
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : roots.entrySet()) {
            try {
                result.put(e.getKey(), MAPPER.writeValueAsString(e.getValue()));
            } catch (Exception ex) {
                log.debug("[PromptI18n] Schema 序列化失败（保持中文）: {} - {}", e.getKey(), ex.getMessage());
            }
        }
        return result;
    }

    /** 递归收集 JSON 树中的所有 {@code description} 字段。 */
    private static void collectSchemaDescriptions(JsonNode node, List<SchemaDesc> out,
                                                  String schemaKey, int[] counter) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if ("description".equals(entry.getKey()) && entry.getValue().isTextual()
                        && !entry.getValue().asText().isBlank()) {
                    out.add(new SchemaDesc((ObjectNode) node, entry.getKey(), schemaKey, counter[0]++));
                } else {
                    collectSchemaDescriptions(entry.getValue(), out, schemaKey, counter);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectSchemaDescriptions(child, out, schemaKey, counter);
            }
        }
    }

    /** 调用大模型翻译单个分块（纯文本），失败时自动重试一次。 */
    private Map<String, String> translateChunk(Map<String, String> chunk, String languageName) throws Exception {
        String json = MAPPER.writeValueAsString(chunk);
        String systemPrompt = """
                你是一个严谨的机器翻译引擎，只能输出 JSON，严禁输出任何其它内容。
                请将用户提供的 JSON 对象中的所有「值」翻译成 %s 语言。
                规则：
                1. 保持 JSON 对象的键（key）完全不变。
                2. 每个值都是一段普通文本，请完整翻译其含义，保持原有的 Markdown 标记、换行与格式。
                3. 你的整个回复必须是一个有效的 JSON 对象，包含与输入完全相同的键。
                   不要输出任何解释、问候语、前言、总结或代码块标记（如 ```）。
                """.formatted(languageName);

        String userMessage = "请将以下 JSON 对象中的所有中文文本翻译为 " + languageName + "：\n" + json;

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)));
                ChatResponse response = chatModel.call(prompt);
                String content = response.getResult().getOutput().getText();
                if (content == null || content.isBlank()) {
                    return null;
                }
                String cleaned = stripJsonFence(content);
                Map<String, String> parsed = MAPPER.readValue(cleaned, new TypeReference<Map<String, String>>() {});
                log.debug("[PromptI18n] 分块翻译返回 {} 条", parsed != null ? parsed.size() : 0);
                return parsed;
            } catch (Exception e) {
                if (attempt == 1) {
                    log.warn("[PromptI18n] 分块翻译解析失败（重试中）: {}", e.getMessage());
                } else {
                    throw e;
                }
            }
        }
        return null;
    }

    /** Schema 中的一个 {@code description} 字段引用。 */
    private record SchemaDesc(ObjectNode parent, String fieldName, String schemaKey, int index) {
        /** 翻译缓存 key：{schemaKey}.desc.{index}。 */
        String key() {
            return schemaKey + ".desc." + index;
        }
    }

    /** 是否启用提示词翻译（配置开关 → 系统属性 → 环境变量，均未关闭才启用）。 */
    private boolean isEnabled() {
        if (!enabledByConfig) {
            return false;
        }
        String prop = System.getProperty(ENABLED_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return !"false".equalsIgnoreCase(prop) && !"0".equals(prop);
        }
        String env = System.getenv(ENABLED_ENV);
        if (env != null && !env.isBlank()) {
            return !"false".equalsIgnoreCase(env) && !"0".equals(env);
        }
        return true;
    }

    /** 去除大模型输出中可能包裹的 Markdown 代码块标记。 */
    private static String stripJsonFence(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        // 兜底：截取首个 { 到最后一个 } 之间的内容
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return text;
    }
}
