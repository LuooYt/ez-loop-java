package com.inspirationi.loop.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基础指标收集器 —— 纯内存收集，不写磁盘。
 * <p>
 * SDK 场景下，指标数据保留在内存中，调用方通过
 * {@link #toMap()} / {@link #summary()} 获取后自行决定如何持久化或上报。
 * <p>
 * 收集的指标：
 * <ul>
 *   <li>会话时长</li>
 *   <li>工具使用次数（按工具名）</li>
 *   <li>命令使用次数（按命令名）</li>
 *   <li>API 调用次数和 token 用量</li>
 *   <li>错误次数（按类型）</li>
 *   <li>自动压缩次数</li>
 * </ul>
 */
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    /** 当前会话的唯一标识（默认取随机 UUID 前 8 位）。 */
    private final String sessionId;
    /** 会话开始时间，用于计算会话时长。 */
    private final Instant sessionStart;

    // ==================== 计数器 ====================

    /** 工具使用次数: toolName → count */
    private final ConcurrentHashMap<String, AtomicLong> toolUsage = new ConcurrentHashMap<>();

    /** 命令使用次数: commandName → count */
    private final ConcurrentHashMap<String, AtomicLong> commandUsage = new ConcurrentHashMap<>();

    /** 错误次数: errorType → count */
    private final ConcurrentHashMap<String, AtomicLong> errorCounts = new ConcurrentHashMap<>();

    /** API 调用次数 */
    private final AtomicLong apiCallCount = new AtomicLong(0);

    /** 总 token 使用量 */
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);

    /** 自动压缩次数 */
    private final AtomicLong autoCompactCount = new AtomicLong(0);

    /** 用户消息数 */
    private final AtomicLong userMessageCount = new AtomicLong(0);

    /** 助手消息数 */
    private final AtomicLong assistantMessageCount = new AtomicLong(0);

    // ==================== 追踪 ====================

    /** 最近的请求追踪 ID 列表（保留最近 50 个） */
    private final List<String> recentRequestIds = Collections.synchronizedList(new ArrayList<>());

    /** 请求次数 */
    private final AtomicLong requestCount = new AtomicLong(0);

    /**
     * 创建指标收集器，自动生成随机会话 ID（UUID 前 8 位）。
     */
    public MetricsCollector() {
        this(UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * 创建指标收集器，使用指定的会话 ID 并记录会话开始时间。
     *
     * @param sessionId 会话唯一标识
     */
    public MetricsCollector(String sessionId) {
        this.sessionId = sessionId;
        this.sessionStart = Instant.now();
    }

    // ==================== 记录方法 ====================

    /**
     * 记录一次工具使用。
     *
     * @param toolName 工具名称
     */
    public void recordToolUse(String toolName) {
        toolUsage.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 记录一次命令使用。
     *
     * @param commandName 命令名称
     */
    public void recordCommand(String commandName) {
        commandUsage.computeIfAbsent(commandName, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 记录一次错误。
     *
     * @param errorType 错误类型
     */
    public void recordError(String errorType) {
        errorCounts.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 记录一次 API 调用及其 token 用量。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    public void recordApiCall(long inputTokens, long outputTokens) {
        apiCallCount.incrementAndGet();
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
    }

    /** 记录一次自动压缩。 */
    public void recordAutoCompact() {
        autoCompactCount.incrementAndGet();
    }

    /** 记录一条用户消息。 */
    public void recordUserMessage() {
        userMessageCount.incrementAndGet();
    }

    /** 记录一条助手消息。 */
    public void recordAssistantMessage() {
        assistantMessageCount.incrementAndGet();
    }

    /** 记录请求追踪 ID */
    public void recordRequestId(String requestId) {
        requestCount.incrementAndGet();
        if (requestId != null && !requestId.isBlank()) {
            recentRequestIds.add(requestId);
            // 保留最近 50 个
            if (recentRequestIds.size() > 50) {
                recentRequestIds.remove(0);
            }
        }
    }

    /** 获取最近的请求追踪 ID 列表 */
    public List<String> getRecentRequestIds() {
        return List.copyOf(recentRequestIds);
    }

    // ==================== 获取指标 ====================

    /**
     * 获取会话已持续时长（秒）。
     *
     * @return 从会话开始到当前的秒数
     */
    public long getSessionDurationSeconds() {
        return Instant.now().getEpochSecond() - sessionStart.getEpochSecond();
    }

    /**
     * 获取各工具使用次数（按键排序）。
     *
     * @return 工具名 → 使用次数
     */
    public Map<String, Long> getToolUsage() {
        Map<String, Long> result = new TreeMap<>();
        toolUsage.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    /**
     * 获取各命令使用次数（按键排序）。
     *
     * @return 命令名 → 使用次数
     */
    public Map<String, Long> getCommandUsage() {
        Map<String, Long> result = new TreeMap<>();
        commandUsage.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    /**
     * 获取各类型错误次数（按键排序）。
     *
     * @return 错误类型 → 次数
     */
    public Map<String, Long> getErrorCounts() {
        Map<String, Long> result = new TreeMap<>();
        errorCounts.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    // ==================== 快照（供 SDK 调用方序列化） ====================

    /**
     * 将指标转为 Map（供 SDK 调用方自行持久化或上报）。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("session_id", sessionId);
        map.put("start_time", sessionStart.toString());
        map.put("duration_seconds", getSessionDurationSeconds());
        map.put("api_calls", apiCallCount.get());
        map.put("input_tokens", totalInputTokens.get());
        map.put("output_tokens", totalOutputTokens.get());
        map.put("user_messages", userMessageCount.get());
        map.put("assistant_messages", assistantMessageCount.get());
        map.put("auto_compacts", autoCompactCount.get());
        map.put("tool_usage", getToolUsage());
        map.put("command_usage", getCommandUsage());
        map.put("errors", getErrorCounts());
        map.put("request_count", requestCount.get());
        map.put("recent_request_ids", getRecentRequestIds());
        return map;
    }

    /**
     * 清空所有计数（SDK 没有 flush()，调用方可自行决定何时消费指标后重置）。
     */
    public void clear() {
        toolUsage.clear();
        commandUsage.clear();
        errorCounts.clear();
        apiCallCount.set(0);
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        autoCompactCount.set(0);
        userMessageCount.set(0);
        assistantMessageCount.set(0);
        requestCount.set(0);
        recentRequestIds.clear();
    }

    /**
     * 获取指标摘要文本（用于 /doctor 或 /session 命令）。
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Session: ").append(sessionId).append("\n");
        sb.append("Duration: ").append(formatDuration(getSessionDurationSeconds())).append("\n");
        sb.append("API Calls: ").append(apiCallCount.get()).append("\n");
        sb.append("Tokens: ").append(totalInputTokens.get()).append(" in / ")
                .append(totalOutputTokens.get()).append(" out\n");
        sb.append("Messages: ").append(userMessageCount.get()).append(" user / ")
                .append(assistantMessageCount.get()).append(" assistant\n");
        sb.append("Auto-compacts: ").append(autoCompactCount.get()).append("\n");

        if (!toolUsage.isEmpty()) {
            sb.append("Top tools: ");
            toolUsage.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(AtomicLong::get).reversed()))
                    .limit(5)
                    .forEach(e -> sb.append(e.getKey()).append("(").append(e.getValue().get()).append(") "));
            sb.append("\n");
        }

        if (!errorCounts.isEmpty()) {
            sb.append("Errors: ");
            errorCounts.forEach((k, v) -> sb.append(k).append("(").append(v.get()).append(") "));
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 将秒数格式化为人类可读的时长文本（如 "90s"、"5m 30s"、"1h 5m"）。
     *
     * @param seconds 秒数
     * @return 格式化后的时长字符串
     */
    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    public String getSessionId() { return sessionId; }
    public Instant getSessionStart() { return sessionStart; }
}