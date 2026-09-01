package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.i18n.PromptI18n;
import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;

import java.util.Map;

/**
 * Sleep 工具 —— 等待指定时长，用于暂停操作、等待外部进程或用户要求休眠。
 * 支持通过中断取消等待。
 */
public class SleepTool implements Tool {

    /** 最大等待时长：300000 毫秒（5 分钟） */
    private static final long MAX_DURATION_MS = 300_000; // 5 minutes max

    /**
     * 返回工具名称（"Sleep"），供 LLM 调用时识别。
     */
    @Override
    public String name() {
        return "Sleep";
    }

    /**
     * 返回工具描述，说明等待指定时长及最大时长限制的用途。
     */
    @Override
    public String description() {
        return PromptI18n.t(PromptI18n.toolDescriptionKey(name()), """
            等待指定的时长（毫秒）。用户可以在任何时候中断等待。适用于：
            - 用户让你休眠或休息
            - 你无事可做，正在等待某件事
            - 你需要等待外部进程完成
            最大时长：300000 毫秒（5 分钟）。
            """);
    }

    /**
     * 返回输入 JSON Schema，定义 duration_ms（必填）参数。
     */
    @Override
    public String inputSchema() {
        return PromptI18n.t(PromptI18n.toolSchemaKey(name()), """
            {
              "type": "object",
              "properties": {
                "duration_ms": {
                  "type": "integer",
                  "description": "要等待的毫秒数（最大：300000）"
                }
              },
              "required": ["duration_ms"]
            }""");
    }

    /**
     * 该工具仅等待、无任何外部副作用，标记为只读。
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 执行等待：校验时长参数并将超限值限制在最大时长内，睡眠期间可被中断，
     * 返回实际睡眠时长或中断提示。
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        Number durationNum = (Number) input.get("duration_ms");
        if (durationNum == null) {
            return "Error: 'duration_ms' is required";
        }

        long durationMs = durationNum.longValue();
        if (durationMs <= 0) {
            return "Error: duration_ms must be positive";
        }
        if (durationMs > MAX_DURATION_MS) {
            durationMs = MAX_DURATION_MS;
        }

        long startTime = System.currentTimeMillis();
        try {
            Thread.sleep(durationMs);
            long elapsed = System.currentTimeMillis() - startTime;
            return String.format("Slept for %d ms", elapsed);
        } catch (InterruptedException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            Thread.currentThread().interrupt();
            return String.format("Sleep interrupted after %d ms (requested %d ms)", elapsed, durationMs);
        }
    }

    /**
     * 生成用于界面展示的执行摘要，将毫秒数格式化为秒。
     */
    @Override
    public String activityDescription(Map<String, Object> input) {
        Number ms = (Number) input.getOrDefault("duration_ms", 0);
        return "💤 Sleeping " + (ms.longValue() / 1000.0) + "s";
    }
}
