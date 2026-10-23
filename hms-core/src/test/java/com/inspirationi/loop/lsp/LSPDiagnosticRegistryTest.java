package com.inspirationi.loop.lsp;

import com.inspirationi.loop.lsp.LSPDiagnosticRegistry.Diagnostic;
import com.inspirationi.loop.lsp.LSPDiagnosticRegistry.DiagnosticFile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断注册表的数量限制与交付语义。
 * <p>
 * 关键约束：超出上限的诊断要被<b>推迟</b>而不是丢弃 —— 丢掉的那些模型永远
 * 不会知道；以及已交付标记必须由调用方在内容送达后提交。
 */
class LSPDiagnosticRegistryTest {

    private static Diagnostic diag(String message) {
        return new Diagnostic(message, "Error", 0, 0, 0, 1, "test", null);
    }

    private static List<Diagnostic> diags(String prefix, int count) {
        List<Diagnostic> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(diag(prefix + i));
        }
        return list;
    }

    private static int count(List<DiagnosticFile> files) {
        return files.stream().mapToInt(f -> f.diagnostics().size()).sum();
    }

    /** 40 条诊断分两轮取完：第一轮 30 条，第二轮剩余 10 条。 */
    @Test
    void diagnosticsBeyondTotalLimitAreDeferredNotDropped() {
        LSPDiagnosticRegistry registry = new LSPDiagnosticRegistry();
        // 4 个文件各 10 条（正好是每文件上限），合计 40 > 总量上限 30
        for (String name : List.of("a.java", "b.java", "c.java", "d.java")) {
            registry.registerDiagnostics("test-server", name, diags(name, 10));
        }

        List<DiagnosticFile> first = registry.checkAndExtract();
        assertEquals(LSPDiagnosticRegistry.MAX_TOTAL_DIAGNOSTICS, count(first),
                "第一轮应取满 " + LSPDiagnosticRegistry.MAX_TOTAL_DIAGNOSTICS + " 条");
        registry.commitDelivered(first);

        List<DiagnosticFile> second = registry.checkAndExtract();
        assertEquals(10, count(second), "剩余 10 条应在下一轮返回");
        registry.commitDelivered(second);

        assertTrue(registry.checkAndExtract().isEmpty(), "全部交付后不应再有诊断");
    }

    /** 每文件上限同理：超出的部分下一轮继续返回。 */
    @Test
    void diagnosticsBeyondPerFileLimitAreDeferred() {
        LSPDiagnosticRegistry registry = new LSPDiagnosticRegistry();
        registry.registerDiagnostics("s", "big.java", diags("d", 15));

        List<DiagnosticFile> first = registry.checkAndExtract();
        assertEquals(LSPDiagnosticRegistry.MAX_DIAGNOSTICS_PER_FILE, count(first));
        registry.commitDelivered(first);

        List<DiagnosticFile> second = registry.checkAndExtract();
        assertEquals(5, count(second));
        assertEquals("big.java", second.get(0).filePath());
    }

    /**
     * 没 commit 就取走了：服务器重报同一批时必须还能返回。
     * 这正是把 markDelivered 从 checkAndExtract 里拆出来的原因 ——
     * 内容没送达上下文就不算交付。
     */
    @Test
    void uncommittedDiagnosticsAreNotRememberedAsDelivered() {
        LSPDiagnosticRegistry registry = new LSPDiagnosticRegistry();
        registry.registerDiagnostics("s", "a.java", diags("x", 3));
        registry.checkAndExtract();

        registry.registerDiagnostics("s", "a.java", diags("x", 3));
        assertEquals(3, count(registry.checkAndExtract()),
                "未提交的交付不应被记为已交付");
    }

    /** 提交之后不再重复返回 —— 跨轮去重仍然有效。 */
    @Test
    void committedDiagnosticsAreNotReturnedTwice() {
        LSPDiagnosticRegistry registry = new LSPDiagnosticRegistry();
        registry.registerDiagnostics("s", "a.java", diags("x", 3));
        registry.commitDelivered(registry.checkAndExtract());

        registry.registerDiagnostics("s", "a.java", diags("x", 3));
        assertTrue(registry.checkAndExtract().isEmpty(),
                "同一批诊断重新上报时不应再次交付");
    }

    /** 文件被重新保存后，之前的交付记录作废，诊断重新可见。 */
    @Test
    void clearDeliveredMakesDiagnosticsVisibleAgain() {
        LSPDiagnosticRegistry registry = new LSPDiagnosticRegistry();
        registry.registerDiagnostics("s", "a.java", diags("x", 2));
        registry.commitDelivered(registry.checkAndExtract());

        registry.clearDeliveredForFile("a.java");
        registry.registerDiagnostics("s", "a.java", diags("x", 2));

        assertEquals(2, count(registry.checkAndExtract()));
    }

    /** 按严重性排序：Error 排在 Warning 前面。 */
    @Test
    void ordersBySeverity() {
        LSPDiagnosticRegistry registry = new LSPDiagnosticRegistry();
        registry.registerDiagnostics("s", "a.java", List.of(
                new Diagnostic("warn", "Warning", 1, 0, 1, 1, "t", null),
                new Diagnostic("err", "Error", 2, 0, 2, 1, "t", null)));

        List<Diagnostic> got = registry.checkAndExtract().get(0).diagnostics();
        assertEquals("err", got.get(0).message());
        assertEquals("warn", got.get(1).message());
    }
}
