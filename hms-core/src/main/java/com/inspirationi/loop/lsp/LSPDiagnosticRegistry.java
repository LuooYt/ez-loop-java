package com.inspirationi.loop.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * LSP 诊断注册表 —— 对应 claude-code/src/services/lsp/LSPDiagnosticRegistry.ts。
 * <p>
 * 管理从 LSP 服务器收到的诊断信息：
 * <ul>
 *   <li>批内去重（同一轮次内的重复诊断）</li>
 *   <li>跨轮去重（已交付过的诊断不再重复）</li>
 *   <li>数量限制（每文件 10 条，总计 30 条），超出的留到下一轮而非丢弃</li>
 *   <li>严重性排序（Error > Warning > Info > Hint）</li>
 * </ul>
 */
public class LSPDiagnosticRegistry {

    private static final Logger log = LoggerFactory.getLogger(LSPDiagnosticRegistry.class);

    public static final int MAX_DIAGNOSTICS_PER_FILE = 10;
    public static final int MAX_TOTAL_DIAGNOSTICS = 30;
    private static final int MAX_DELIVERED_FILES_CACHE = 500;

    // ==================== 数据类型 ====================

    public record Diagnostic(
            String message,
            String severity,  // "Error", "Warning", "Info", "Hint"
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            String source,
            String code
    ) {
        public String key() {
            return message + "|" + severity + "|" +
                    startLine + ":" + startChar + "-" + endLine + ":" + endChar + "|" +
                    (source != null ? source : "") + "|" +
                    (code != null ? code : "");
        }

        /** 严重性排序权重（越小越严重） */
        public int severityWeight() {
            return switch (severity) {
                case "Error" -> 0;
                case "Warning" -> 1;
                case "Info" -> 2;
                case "Hint" -> 3;
                default -> 4;
            };
        }
    }

    public record DiagnosticFile(
            String filePath,
            List<Diagnostic> diagnostics
    ) {}

    public record PendingBatch(
            String serverName,
            List<DiagnosticFile> files,
            long timestamp
    ) {}

    // ==================== 状态 ====================

    /** 待处理的诊断批次 */
    private final ConcurrentLinkedDeque<PendingBatch> pendingBatches = new ConcurrentLinkedDeque<>();

    /** 已交付的诊断键（跨轮去重）：filePath → Set<diagnosticKey> */
    private final ConcurrentHashMap<String, Set<String>> deliveredDiagnostics = new ConcurrentHashMap<>();

    /** LRU 顺序追踪（配合 deliveredDiagnostics 做容量限制） */
    private final ConcurrentLinkedDeque<String> deliveredFilesOrder = new ConcurrentLinkedDeque<>();

    // ==================== 注册诊断 ====================

    /**
     * 注册一批诊断信息（由 LSP 服务器通知触发）。
     */
    public void registerDiagnostics(String serverName, String filePath, List<Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return;

        var file = new DiagnosticFile(filePath, diagnostics);
        pendingBatches.addLast(new PendingBatch(serverName, List.of(file), System.currentTimeMillis()));
    }

    // ==================== 检查和提取 ====================

    /**
     * 检查并提取待处理的诊断信息（去重 + 按严重性排序 + 限量）。
     * <p>
     * <b>不</b>把结果记为已交付 —— 调用方在结果真正写进上下文之后再调
     * {@link #commitDelivered(List)}。若在这里就标记，而调用方因故没把内容交出去
     * （抛异常、被取消），这些诊断就永久消失了。
     * <p>
     * 超出每文件上限或总量上限的部分<b>退回待处理队列</b>，下一轮继续返回，
     * 而不是静默丢弃 —— 40 条诊断分两轮取完，好过丢掉后 10 条让模型无从得知。
     *
     * @return 去重后的诊断文件列表（为空时返回空列表）
     */
    public List<DiagnosticFile> checkAndExtract() {
        if (pendingBatches.isEmpty()) return List.of();

        // 收集所有待处理批次
        List<PendingBatch> batches = new ArrayList<>();
        PendingBatch batch;
        while ((batch = pendingBatches.pollFirst()) != null) {
            batches.add(batch);
        }

        if (batches.isEmpty()) return List.of();

        // 按文件分组并合并（顺便记住文件来自哪个服务器，退队时要用）
        Map<String, List<Diagnostic>> fileGroups = new LinkedHashMap<>();
        Map<String, String> serverByFile = new HashMap<>();
        for (PendingBatch b : batches) {
            for (DiagnosticFile df : b.files) {
                fileGroups.computeIfAbsent(df.filePath(), k -> new ArrayList<>())
                        .addAll(df.diagnostics());
                serverByFile.putIfAbsent(df.filePath(), b.serverName());
            }
        }

        // 去重 + 限制
        List<DiagnosticFile> result = new ArrayList<>();
        List<DiagnosticFile> leftover = new ArrayList<>();
        int totalCount = 0;

        for (var entry : fileGroups.entrySet()) {
            String filePath = entry.getKey();
            List<Diagnostic> diagnostics = entry.getValue();

            // 批内去重
            Set<String> seenKeys = new HashSet<>();
            List<Diagnostic> deduped = new ArrayList<>();
            for (Diagnostic d : diagnostics) {
                if (seenKeys.add(d.key())) {
                    deduped.add(d);
                }
            }

            // 跨轮去重
            Set<String> delivered = deliveredDiagnostics.get(filePath);
            if (delivered != null) {
                deduped.removeIf(d -> delivered.contains(d.key()));
            }

            if (deduped.isEmpty()) continue;

            // 按严重性排序
            deduped.sort(Comparator.comparingInt(Diagnostic::severityWeight));

            // 每文件限制：超出的留到下一轮
            if (deduped.size() > MAX_DIAGNOSTICS_PER_FILE) {
                leftover.add(new DiagnosticFile(filePath,
                        new ArrayList<>(deduped.subList(MAX_DIAGNOSTICS_PER_FILE, deduped.size()))));
                deduped = new ArrayList<>(deduped.subList(0, MAX_DIAGNOSTICS_PER_FILE));
            }

            // 总量限制：本轮放不下的同样留到下一轮
            int remaining = MAX_TOTAL_DIAGNOSTICS - totalCount;
            if (remaining <= 0 || deduped.size() > remaining) {
                int keep = Math.max(0, remaining);
                if (keep < deduped.size()) {
                    leftover.add(new DiagnosticFile(filePath,
                            new ArrayList<>(deduped.subList(keep, deduped.size()))));
                    deduped = new ArrayList<>(deduped.subList(0, keep));
                }
            }

            if (deduped.isEmpty()) continue;

            totalCount += deduped.size();
            result.add(new DiagnosticFile(filePath, deduped));
        }

        requeue(leftover, serverByFile);
        return result;
    }

    /** 把本轮没取走的诊断放回队首（保持原顺序），下一轮继续返回。 */
    private void requeue(List<DiagnosticFile> leftover, Map<String, String> serverByFile) {
        for (int i = leftover.size() - 1; i >= 0; i--) {
            DiagnosticFile df = leftover.get(i);
            pendingBatches.addFirst(new PendingBatch(
                    serverByFile.getOrDefault(df.filePath(), ""),
                    List.of(df),
                    System.currentTimeMillis()));
        }
        if (!leftover.isEmpty()) {
            log.debug("Deferred {} diagnostic group(s) to the next round", leftover.size());
        }
    }

    // ==================== 已交付追踪 ====================

    /**
     * 把一批诊断标记为「已交付」。
     * <p>
     * 由调用方在内容确实写进上下文之后调用。标记后这些诊断不会再被
     * {@link #checkAndExtract()} 返回，除非对应文件被重新保存
     * （{@link #clearDeliveredForFile}）。
     */
    public void commitDelivered(List<DiagnosticFile> files) {
        if (files == null || files.isEmpty()) return;
        for (DiagnosticFile df : files) {
            markDelivered(df.filePath(), df.diagnostics());
        }
    }

    private void markDelivered(String filePath, List<Diagnostic> diagnostics) {
        Set<String> keys = deliveredDiagnostics.computeIfAbsent(filePath, k -> {
            deliveredFilesOrder.addLast(filePath);
            evictOldFiles();
            return ConcurrentHashMap.newKeySet();
        });

        for (Diagnostic d : diagnostics) {
            keys.add(d.key());
        }
    }

    private void evictOldFiles() {
        while (deliveredFilesOrder.size() > MAX_DELIVERED_FILES_CACHE) {
            String oldest = deliveredFilesOrder.pollFirst();
            if (oldest != null) {
                deliveredDiagnostics.remove(oldest);
            }
        }
    }

    /**
     * 清除指定文件的已交付记录（文件编辑后应调用）。
     *
     * @param fileKey 与 {@link #registerDiagnostics} 使用的同一个键
     *                （见 {@code LSPServerManager.diagnosticKey}）
     */
    public void clearDeliveredForFile(String fileKey) {
        deliveredDiagnostics.remove(fileKey);
    }

    /**
     * 清除所有诊断状态。
     */
    public void clearAll() {
        pendingBatches.clear();
        deliveredDiagnostics.clear();
        deliveredFilesOrder.clear();
    }

    /**
     * 获取待处理诊断数量。
     */
    public int getPendingCount() {
        return pendingBatches.size();
    }

    // ==================== 格式化 ====================

    /**
     * 将诊断列表格式化为可注入 Agent 上下文的文本。
     */
    public static String formatForContext(List<DiagnosticFile> files) {
        if (files.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## LSP Diagnostics\n\n");

        for (DiagnosticFile df : files) {
            sb.append("### ").append(df.filePath).append("\n");
            for (Diagnostic d : df.diagnostics) {
                sb.append("- [").append(d.severity).append("]");
                if (d.source != null) sb.append(" (").append(d.source).append(")");
                sb.append(" Line ").append(d.startLine + 1);
                if (d.startChar > 0) sb.append(":").append(d.startChar);
                sb.append(": ").append(d.message);
                if (d.code != null) sb.append(" [").append(d.code).append("]");
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
