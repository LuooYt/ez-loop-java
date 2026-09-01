package com.inspirationi.loop.tool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolContext} 的父子读透语义。
 * <p>
 * 会话级上下文必须能读到全局上下文注册的共享对象（TaskManager、McpManager…），
 * 同时自身的写入不能污染全局 —— 否则会话之间会串状态。
 */
class ToolContextInheritanceTest {

    @Test
    void childReadsKeysFromParent() {
        ToolContext parent = ToolContext.defaultContext();
        parent.set("TASK_MANAGER", "shared-instance");

        ToolContext child = ToolContext.childOf(parent);

        assertEquals("shared-instance", child.get("TASK_MANAGER"),
                "会话上下文必须能读到全局注册的共享对象");
        assertTrue(child.has("TASK_MANAGER"), "has() 也应穿透到父级");
        assertEquals("shared-instance", child.getOrDefault("TASK_MANAGER", "fallback"),
                "getOrDefault 命中父级时不应返回默认值");
    }

    @Test
    void childWritesDoNotLeakIntoParent() {
        ToolContext parent = ToolContext.defaultContext();
        ToolContext child = ToolContext.childOf(parent);

        child.set("SESSION_ONLY", "value");

        assertNull(parent.get("SESSION_ONLY"),
                "会话级写入不得污染全局上下文，否则会话间会串状态");
        assertFalse(parent.has("SESSION_ONLY"));
    }

    @Test
    void childOverrideShadowsParentWithoutMutatingIt() {
        ToolContext parent = ToolContext.defaultContext();
        parent.set("TOOL_REGISTRY", "global-registry");

        ToolContext child = ToolContext.childOf(parent);
        child.set("TOOL_REGISTRY", "session-registry");

        assertEquals("session-registry", child.get("TOOL_REGISTRY"),
                "本地值应遮蔽父级值");
        assertEquals("global-registry", parent.get("TOOL_REGISTRY"),
                "遮蔽不得改写父级 —— 两级工具隔离依赖这一点");
    }

    @Test
    void parentRegistrationAfterChildCreationIsStillVisible() {
        // 读透而非快照复制：使用方可能在会话创建之后才注册共享对象
        ToolContext parent = ToolContext.defaultContext();
        ToolContext child = ToolContext.childOf(parent);

        parent.set("LATE_PROVIDER", "registered-later");

        assertEquals("registered-later", child.get("LATE_PROVIDER"),
                "子上下文必须读透父级，否则后注册的共享对象对已有会话永久不可见");
    }

    @Test
    void childInheritsWorkDirAndModel() {
        ToolContext parent = new ToolContext(Path.of("/tmp"), "gpt-4o");
        ToolContext child = ToolContext.childOf(parent);

        assertEquals(Path.of("/tmp"), child.getWorkDir());
        assertEquals("gpt-4o", child.getModel());
    }

    @Test
    void childOfNullYieldsUsableStandaloneContext() {
        ToolContext child = ToolContext.childOf(null);

        child.set("K", "v");
        assertEquals("v", child.get("K"));
        assertNull(child.get("MISSING"));
        assertEquals("fallback", child.getOrDefault("MISSING", "fallback"));
    }

    @Test
    void missingKeyFallsBackThroughWholeChain() {
        ToolContext grandparent = ToolContext.defaultContext();
        grandparent.set("DEEP", "from-grandparent");

        ToolContext child = ToolContext.childOf(ToolContext.childOf(grandparent));

        assertEquals("from-grandparent", child.get("DEEP"),
                "回落应沿整条父链，子 Agent 上下文可能嵌套多层");
    }
}
