package com.inspirationi.loop.permission;

import com.inspirationi.loop.permission.PermissionTypes.PermissionBehavior;
import com.inspirationi.loop.permission.PermissionTypes.PermissionMode;
import com.inspirationi.loop.permission.PermissionTypes.PermissionRule;
import com.inspirationi.loop.tool.Tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionSettings} 的并发安全。
 * <p>
 * 该类是单例 Bean，处于权限评估的热路径上：
 * <ul>
 *   <li>每次工具调用都经 {@code getAllRules()} 遍历全部规则列表</li>
 *   <li>用户点「始终允许/拒绝」时 {@code addUserRule()} 并发写入</li>
 *   <li>集成方可随时调 {@code setProjectRules()} / {@code clearAll()}</li>
 * </ul>
 * 底层若是无同步的 ArrayList，读写并发会抛异常或读到损坏的中间状态 ——
 * 后者更危险：权限判断基于残缺的规则集，可能漏掉 DENY 规则。
 */
class PermissionSettingsConcurrencyTest {

    @Test
    void concurrentRuleMutationAndEvaluationDoesNotThrow() throws Exception {
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.DEFAULT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);

        for (int i = 0; i < 30; i++) {
            settings.addUserRule(PermissionRule.forTool("seed-" + i, PermissionBehavior.ALLOW));
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        // 写线程：模拟用户反复点「始终允许/拒绝」
        Thread.ofVirtual().start(() -> {
            try {
                start.await();
                for (int i = 0; i < 3_000; i++) {
                    var rule = PermissionRule.forTool("churn-" + i, PermissionBehavior.ALLOW);
                    settings.addUserRule(rule);
                    settings.removeUserRule(PermissionSettings.formatRule(rule));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        // 读线程：模拟持续的权限评估
        Thread.ofVirtual().start(() -> {
            try {
                start.await();
                for (int i = 0; i < 3_000; i++) {
                    settings.getAllRules();
                    engine.evaluate("SomeTool", Map.of(), Tool.RiskLevel.HIGH);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发读写应在限时内完成");

        assertNull(failure.get(),
                "权限规则的并发增删与评估不应抛异常，实际：" + failure.get());
    }

    @Test
    void concurrentSetProjectRulesDoesNotCorruptState() throws Exception {
        PermissionSettings settings = new PermissionSettings();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        // setProjectRules 内部是 clear + addAll，读线程可能撞上清空后的中间状态
        Thread.ofVirtual().start(() -> {
            try {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    settings.setProjectRules(
                            List.of("ToolA", "ToolB"), List.of("ToolC"), PermissionMode.DEFAULT);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    settings.getAllRules();
                    settings.listRules();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertNull(failure.get(),
                "setProjectRules 与读取并发不应抛异常，实际：" + failure.get());
    }

    @Test
    void getAllRulesReturnsSnapshotNotLiveView() {
        PermissionSettings settings = new PermissionSettings();
        settings.addUserRule(PermissionRule.forTool("A", PermissionBehavior.ALLOW));

        var snapshot = settings.getAllRules();
        int sizeBefore = snapshot.size();
        settings.addUserRule(PermissionRule.forTool("B", PermissionBehavior.ALLOW));

        assertEquals(sizeBefore, snapshot.size(),
                "getAllRules() 返回的列表不应随后续写入而变化");
    }

    @Test
    void denyRuleRemainsVisibleUnderConcurrentWrites() throws Exception {
        // 关键安全属性：并发写入期间，已存在的 DENY 规则不能「时隐时现」
        PermissionSettings settings = new PermissionSettings();
        settings.setCurrentMode(PermissionMode.DEFAULT);
        PermissionRuleEngine engine = new PermissionRuleEngine(settings);
        settings.addUserRule(PermissionRule.forTool("Banned", PermissionBehavior.DENY));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread.ofVirtual().start(() -> {
            try {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    var r = PermissionRule.forTool("noise-" + i, PermissionBehavior.ALLOW);
                    settings.addUserRule(r);
                    settings.removeUserRule(PermissionSettings.formatRule(r));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    if (!engine.evaluate("Banned", Map.of(), Tool.RiskLevel.LOW).isDenied()) {
                        failure.compareAndSet(null,
                                new AssertionError("DENY 规则在并发写入期间丢失 —— "
                                        + "被禁工具会被放行"));
                        return;
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertNull(failure.get(), String.valueOf(failure.get()));
    }
}
