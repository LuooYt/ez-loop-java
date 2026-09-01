package com.inspirationi.loop.i18n;

import com.inspirationi.loop.api.DefaultHmsSessionManager;
import com.inspirationi.loop.api.DefaultPromptManager;
import com.inspirationi.loop.core.AgentLoop;
import com.inspirationi.loop.core.AgentToolExecutor;
import com.inspirationi.loop.core.CoordinatorMode;
import com.inspirationi.loop.core.compact.FullCompact;
import com.inspirationi.loop.core.compact.MicroCompact;
import com.inspirationi.loop.core.compact.SessionMemoryCompact;
import com.inspirationi.loop.tool.impl.AgentTool;
import com.inspirationi.loop.tool.impl.AskUserQuestionTool;
import com.inspirationi.loop.tool.impl.ConfigTool;
import com.inspirationi.loop.tool.impl.EnterPlanModeTool;
import com.inspirationi.loop.tool.impl.ExitPlanModeTool;
import com.inspirationi.loop.tool.impl.ListMcpResourcesTool;
import com.inspirationi.loop.tool.impl.ReadMcpResourceTool;
import com.inspirationi.loop.tool.impl.SendMessageTool;
import com.inspirationi.loop.tool.impl.SkillTool;
import com.inspirationi.loop.tool.impl.SleepTool;
import com.inspirationi.loop.tool.impl.TaskCreateTool;
import com.inspirationi.loop.tool.impl.TaskGetTool;
import com.inspirationi.loop.tool.impl.TaskListTool;
import com.inspirationi.loop.tool.impl.TaskOutputTool;
import com.inspirationi.loop.tool.impl.TaskStopTool;
import com.inspirationi.loop.tool.impl.TaskUpdateTool;
import com.inspirationi.loop.tool.impl.TodoWriteTool;
import com.inspirationi.loop.tool.impl.ToolSearchTool;
import com.inspirationi.loop.tool.impl.WebFetchTool;
import com.inspirationi.loop.tool.impl.WebSearchTool;
import com.inspirationi.loop.util.SystemLanguageDetector;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词国际化验证 —— 系统语言检测、PromptI18n 翻译机制、内置提示词中文性。
 */
class PromptI18nTest {

    // ==================== 系统语言检测 ====================

    @Test
    void testSystemLanguageDetection() {
        String lang = SystemLanguageDetector.detectBaseLanguage();
        assertNotNull(lang);
        assertFalse(lang.isBlank());
        // 中文系统（本机 zh_CN）应识别为 zh
        assertEquals("zh", lang);
        assertTrue(SystemLanguageDetector.isChinese(lang));
    }

    @Test
    void testLanguageNormalization() {
        assertEquals("zh", SystemLanguageDetector.normalize("zh_CN.UTF-8"));
        assertEquals("zh", SystemLanguageDetector.normalize("zh-CN"));
        assertEquals("en", SystemLanguageDetector.normalize("en_US.utf8"));
        assertEquals("ja", SystemLanguageDetector.normalize("ja_JP"));
        assertEquals("ko", SystemLanguageDetector.normalize("ko_KR"));
        assertEquals("en", SystemLanguageDetector.normalize(""));
    }

    @Test
    void testIsChinese() {
        assertTrue(SystemLanguageDetector.isChinese("zh"));
        assertTrue(SystemLanguageDetector.isChinese("zh-cn"));
        assertFalse(SystemLanguageDetector.isChinese("en"));
        assertFalse(SystemLanguageDetector.isChinese("ja"));
    }

    // ==================== PromptI18n 翻译机制 ====================

    @Test
    void testPromptI18nReturnsChineseWhenDisabled() {
        PromptI18n.reset();
        assertEquals("你好，世界", PromptI18n.t("test.key", "你好，世界"));
    }

    @Test
    void testPromptI18nReturnsTranslationWhenEnabled() {
        PromptI18n.reset();
        PromptI18n.setTargetLanguage("en");
        PromptI18n.applyTranslations(Map.of("test.key", "Hello, World"));
        PromptI18n.setEnabled(true);
        assertEquals("Hello, World", PromptI18n.t("test.key", "你好，世界"));
        // 缺失的 key 回退中文
        assertEquals("缺失文本", PromptI18n.t("missing.key", "缺失文本"));
        PromptI18n.reset();
    }

    // ==================== 工具描述中文性 ====================

    @Test
    void testToolDescriptionsAreChinese() {
        assertTrue(new AgentTool().description().contains("子 Agent"), "Agent 工具");
        assertTrue(new AskUserQuestionTool().description().contains("提问"), "AskUserQuestion 工具");
        assertTrue(new ConfigTool().description().contains("配置"), "Config 工具");
        assertTrue(new EnterPlanModeTool().description().contains("计划模式"), "EnterPlanMode 工具");
        assertTrue(new ExitPlanModeTool().description().contains("计划"), "ExitPlanMode 工具");
        assertTrue(new ListMcpResourcesTool().description().contains("资源"), "ListMcpResources 工具");
        assertTrue(new ReadMcpResourceTool().description().contains("资源"), "ReadMcpResource 工具");
        assertTrue(new SendMessageTool().description().contains("消息"), "SendMessage 工具");
        assertTrue(new SkillTool().description().contains("skill"), "Skill 工具");
        assertTrue(new SleepTool().description().contains("等待"), "Sleep 工具");
        assertTrue(new TaskCreateTool().description().contains("任务"), "TaskCreate 工具");
        assertTrue(new TaskGetTool().description().contains("任务"), "TaskGet 工具");
        assertTrue(new TaskListTool().description().contains("任务"), "TaskList 工具");
        assertTrue(new TaskUpdateTool().description().contains("状态"), "TaskUpdate 工具");
        assertTrue(new TaskStopTool().description().contains("任务"), "TaskStop 工具");
        assertTrue(new TaskOutputTool().description().contains("输出"), "TaskOutput 工具");
        assertTrue(new TodoWriteTool().description().contains("待办"), "TodoWrite 工具");
        assertTrue(new ToolSearchTool().description().contains("搜索"), "ToolSearch 工具");
        assertTrue(new WebFetchTool().description().contains("获取"), "WebFetch 工具");
        assertTrue(new WebSearchTool().description().contains("搜索"), "WebSearch 工具");
    }

    // ==================== 系统提示词中文性 ====================

    @Test
    void testSystemPromptsAreChinese() {
        assertTrue(DefaultPromptManager.DEFAULT_GLOBAL_PROMPT.contains("HMS Core AI 助手"),
                "全局系统提示词");
        assertTrue(DefaultHmsSessionManager.DEFAULT_SESSION_PROMPT.contains("会话"),
                "默认会话提示词");
        assertTrue(DefaultHmsSessionManager.DEFAULT_SUBAGENT_SYSTEM_PROMPT.contains("子 Agent"),
                "子 Agent 会话提示词");
        assertTrue(AgentTool.DEFAULT_SUBAGENT_SYSTEM_PROMPT.contains("子 Agent"),
                "子 Agent 完整提示词");
        assertTrue(CoordinatorMode.getCoordinatorSystemPrompt().contains("协调者"),
                "协调者系统提示词");
        assertTrue(CoordinatorMode.getCoordinatorUserContext().contains("Worker 可以访问"),
                "协调者用户上下文");
    }

    // ==================== 压缩提示词中文性 ====================

    @Test
    void testCompactPromptsAreChinese() {
        assertTrue(FullCompact.FULL_COMPACT_PROMPT.contains("压缩"), "全量压缩摘要提示词");
        assertTrue(SessionMemoryCompact.SUMMARY_PROMPT.contains("总结"), "会话压缩摘要提示词");
        assertTrue(MicroCompact.TRUNCATED_MARKER.contains("截断"), "微压缩截断占位符");
        assertTrue(MicroCompact.TRUNCATED_MARKER.contains("%d"), "微压缩截断占位符保留 %d 占位符");
    }

    // ==================== 工具结果 / 循环状态文本中文性 ====================

    @Test
    void testToolResultAndLoopStatusTextsAreChinese() {
        assertTrue(AgentToolExecutor.DEFAULT_TOOL_CANCELLED.contains("取消"), "工具取消");
        assertTrue(AgentToolExecutor.DEFAULT_TOOL_ABORTED.contains("中止"), "Hook 中止");
        assertTrue(AgentToolExecutor.DEFAULT_UNKNOWN_TOOL.contains("未知工具"), "未知工具");
        assertTrue(AgentToolExecutor.DEFAULT_UNKNOWN_TOOL.contains("%s"), "未知工具模板保留 %s");
        assertTrue(AgentToolExecutor.DEFAULT_PERMISSION_DENIED.contains("拒绝"), "权限被拒绝");
        assertTrue(AgentLoop.DEFAULT_LOOP_INTERRUPTED.contains("中断"), "用户中断标记");
        assertTrue(AgentLoop.DEFAULT_LOOP_MAX_ITERATIONS.contains("迭代"), "最大迭代警告标记");
    }
}
