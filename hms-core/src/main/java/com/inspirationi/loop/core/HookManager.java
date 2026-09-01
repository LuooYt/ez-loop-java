package com.inspirationi.loop.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook 系统 —— 在工具执行前后插入自定义逻辑。
 * <p>
 * 经 {@link com.inspirationi.loop.api.HmsSessionManager#getSessionHooks} 取得会话的
 * 实例后注册钩子，可以拦截、改写、审计每一次工具调用：
 * <ul>
 *   <li>{@link HookType#PRE_TOOL_USE} —— 工具执行前：返回
 *       {@link HookResult#ABORT} 阻止执行，或原地改写
 *       {@link HookContext#getArguments()} 调整入参</li>
 *   <li>{@link HookType#POST_TOOL_USE} —— 工具执行后：用
 *       {@link HookContext#setResult} 改写回传给模型的结果（脱敏、截断、补充说明）</li>
 * </ul>
 * <p>
 * 钩子按优先级升序执行；任一钩子返回 ABORT 即短路，其后的不再执行。
 * 单个钩子抛出的异常会被记录并忽略，不影响主流程与其余钩子。
 * <p>
 * <b>只有这两个时机</b>。曾另有 {@code PRE_PROMPT} / {@code POST_RESPONSE} 两个
 * 枚举常量，但整个代码库里没有任何触发点 —— 注册上去只会静默不执行。与其把空壳
 * 写进对外契约，不如等真正需要时连同触发点一起加。
 */
public class HookManager {

    private static final Logger log = LoggerFactory.getLogger(HookManager.class);

    /** 所有已注册的 Hook 列表（线程安全） */
    private final List<HookRegistration> hooks = new CopyOnWriteArrayList<>();

    /**
     * 注册一个 Hook。
     *
     * @param type    Hook 类型
     * @param name    Hook 名称（用于日志/调试）
     * @param handler Hook 处理器
     */
    public void register(HookType type, String name, HookHandler handler) {
        hooks.add(new HookRegistration(type, name, handler, 0));
        log.debug("Registered Hook: {} [{}]", name, type);
    }

    /**
     * 注册一个带优先级的 Hook（数字越小优先级越高）。
     */
    public void register(HookType type, String name, HookHandler handler, int priority) {
        hooks.add(new HookRegistration(type, name, handler, priority));
        log.debug("Registered Hook: {} [{}] priority={}", name, type, priority);
    }

    /**
     * 执行指定类型的所有 Hook。
     * <p>
     * Hook 按优先级顺序执行。如果任一 Hook 返回 {@link HookResult#ABORT}，
     * 后续 Hook 将不再执行，并返回 ABORT 结果。
     *
     * @param type    Hook 类型
     * @param context Hook 执行上下文
     * @return 聚合的 Hook 结果
     */
    public HookResult execute(HookType type, HookContext context) {
        List<HookRegistration> matching = hooks.stream()
                .filter(h -> h.type() == type)
                .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                .toList();

        if (matching.isEmpty()) {
            return HookResult.CONTINUE;
        }

        for (HookRegistration reg : matching) {
            try {
                log.debug("Executing Hook: {} [{}]", reg.name(), type);
                HookResult result = reg.handler().handle(context);

                if (result == HookResult.ABORT) {
                    log.info("Hook [{}] aborted the operation", reg.name());
                    return HookResult.ABORT;
                }
            } catch (Exception e) {
                log.warn("Hook [{}] execution exception: {}", reg.name(), e.getMessage());
                // Hook 异常不影响主流程
            }
        }

        return HookResult.CONTINUE;
    }

    /** 移除指定名称的 Hook */
    public void unregister(String name) {
        hooks.removeIf(h -> h.name().equals(name));
    }

    /** 获取所有已注册的 Hook */
    public List<HookRegistration> getHooks() {
        return Collections.unmodifiableList(hooks);
    }

    /** 清除所有 Hook */
    public void clear() {
        hooks.clear();
    }

    // ==================== 内部类型 ====================

    /**
     * Hook 类型 —— 每个常量都必须有对应的触发点，否则注册上去只会静默不执行。
     */
    public enum HookType {
        /** 工具执行前 —— 可阻止执行（ABORT）或原地改写参数 */
        PRE_TOOL_USE,
        /** 工具执行后 —— 可改写回传给模型的结果 */
        POST_TOOL_USE
    }

    /** Hook 执行结果 */
    public enum HookResult {
        /** 继续执行 */
        CONTINUE,
        /** 中止操作 */
        ABORT
    }

    /** Hook 处理器接口 */
    @FunctionalInterface
    public interface HookHandler {
        HookResult handle(HookContext context);
    }

    /** Hook 执行上下文 —— 携带当前操作的相关信息 */
    public static class HookContext {
        /** 当前操作的工具名 */
        private final String toolName;
        /** 工具参数 */
        private final Map<String, Object> arguments;
        /** Hook 处理后的结果（供后续 Hook/调用方读取） */
        private String result;
        /** 自定义元数据存储 */
        private final Map<String, Object> metadata;

        public HookContext(String toolName, Map<String, Object> arguments) {
            this.toolName = toolName;
            this.arguments = arguments != null ? arguments : Map.of();
            this.metadata = new java.util.HashMap<>();
        }

        public String getToolName() { return toolName; }

        /**
         * 工具入参 —— {@link HookType#PRE_TOOL_USE} 阶段<b>原地修改即生效</b>。
         * <p>
         * 返回的就是工具执行时使用的那个 Map，因此 {@code put} / {@code remove}
         * 会直接影响本次调用（可用来注入默认值、改写危险路径、剔除多余字段）。
         * {@link HookType#POST_TOOL_USE} 阶段工具已执行完，此时修改不再有意义。
         */
        public Map<String, Object> getArguments() { return arguments; }

        /** 工具执行结果 —— PRE 阶段为 {@code null}，POST 阶段为工具的实际输出。 */
        public String getResult() { return result; }

        /**
         * 改写回传给模型的结果 —— 仅 {@link HookType#POST_TOOL_USE} 阶段有效。
         * <p>
         * 用于脱敏（抹掉结果里的密钥）、截断超长输出、追加说明。设为
         * {@code null} 会被忽略，保留工具原始结果。
         */
        public void setResult(String result) { this.result = result; }

        /** 自定义元数据 */
        public void put(String key, Object value) { metadata.put(key, value); }
        @SuppressWarnings("unchecked")
        public <T> T get(String key) { return (T) metadata.get(key); }
    }

    /** Hook 注册记录 */
    public record HookRegistration(HookType type, String name, HookHandler handler, int priority) {}
}
