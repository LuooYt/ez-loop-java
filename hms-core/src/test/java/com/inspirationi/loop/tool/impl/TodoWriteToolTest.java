package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.tool.ToolContext;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待办工具的错误信息必须能让模型自己纠正。
 * <p>
 * 实测：模型把 {@code TaskCreate} 返回的 task_id 当成待办 id 传给 TodoWrite，得到一句
 * {@code Error: Task not found: 3bed11ea}；它不知道两套 id 属于不同的存储，于是反复重试
 * 同一串 id，白白消耗迭代次数。另一个常见错误是 complete 不带 id，同样只有一句
 * {@code 'id' is required}。
 */
class TodoWriteToolTest {

    private final TodoWriteTool tool = new TodoWriteTool();
    private final ToolContext context = ToolContext.defaultContext();

    private String exec(Map<String, Object> input) {
        return tool.execute(input, context);
    }

    private String add(String title) {
        String out = exec(Map.of("operation", "add", "title", title));
        Matcher m = Pattern.compile("\\[([0-9a-f-]{8})]").matcher(out);
        assertTrue(m.find(), "添加待办的返回里应带 id，实际: " + out);
        return m.group(1);
    }

    /** 未知 id：回复里要列出当前可用的待办 id，模型才可能改对。 */
    @Test
    void unknownIdListsAvailableTodos() {
        String id = add("步骤一");

        String out = exec(Map.of("operation", "complete", "id", "3bed11ea"));

        assertTrue(out.contains("3bed11ea"), "仍要指出传入的 id，实际: " + out);
        assertTrue(out.contains(id), "应列出当前可用的待办 id，实际: " + out);
        assertTrue(out.contains("步骤一"), "只给 id 不够，标题更好认，实际: " + out);
    }

    /**
     * 未知 id 还要提示「TaskCreate 的 id 不属于这里」。
     * <p>
     * 这是实测中最常见的一次误用：两者都叫 task/todo 且都返回 id。
     */
    @Test
    void unknownIdHintsThatTaskApiIdsAreSeparate() {
        add("步骤一");

        String out = exec(Map.of("operation", "update", "id", "3bed11ea", "status", "done"));

        assertTrue(out.contains("TaskCreate") || out.contains("TaskUpdate"),
                "应提示 TaskCreate/TaskUpdate 的 id 属于任务系统，实际: " + out);
    }

    /** 缺 id：提示怎么拿到 id（先 list），而不是只说一句 "id is required"。 */
    @Test
    void missingIdPointsToListOperation() {
        add("步骤一");

        String out = exec(Map.of("operation", "complete"));

        assertTrue(out.contains("id"), "实际: " + out);
        assertTrue(out.contains("list"), "应提示用 list 取 id，实际: " + out);
    }

    /** 正常路径不受影响。 */
    @Test
    void completeWithValidIdStillWorks() {
        String id = add("步骤一");

        String out = exec(Map.of("operation", "complete", "id", id));

        assertTrue(out.contains("步骤一"), "实际: " + out);

        // list 按状态分组：标记完成后该条目应出现在 DONE 分组下
        String list = exec(Map.of("operation", "list"));
        int doneIdx = list.indexOf("DONE");
        int itemIdx = list.indexOf("[" + id + "]");
        assertTrue(doneIdx >= 0, "list 应包含 DONE 分组，实际: " + list);
        assertTrue(itemIdx > doneIdx, "完成后条目应落在 DONE 分组下，实际: " + list);
    }

    /** 存储是会话级共享状态：同一 ToolContext 下两次调用看到同一份列表。 */
    @Test
    void todosLiveInContext() {
        String id = add("共享状态");
        ToolContext child = ToolContext.childOf(context);

        assertTrue(tool.execute(Map.of("operation", "list"), child).contains(id),
                "子上下文应继承待办列表");
    }

    /** 空 Map 也要走同一条缺参提示，不能抛异常。 */
    @Test
    void missingOperationIsReported() {
        String out = exec(new HashMap<>());

        assertTrue(out.startsWith("Error"), "实际: " + out);
    }
}
