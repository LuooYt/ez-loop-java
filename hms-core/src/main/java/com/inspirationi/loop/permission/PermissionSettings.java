package com.inspirationi.loop.permission;

import com.inspirationi.loop.permission.PermissionTypes.PermissionBehavior;
import com.inspirationi.loop.permission.PermissionTypes.PermissionRule;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限设置 —— 纯内存管理，不读写磁盘文件。
 * <p>
 * SDK 场景下，权限规则完全由 API 调用方控制：
 * <ul>
 *   <li>{@link #addUserRule(PermissionRule)} — 添加持久化规则（会话生效）</li>
 *   <li>{@link #addSessionRule(PermissionRule)} — 添加会话级规则</li>
 *   <li>{@link #setCurrentMode(PermissionTypes.PermissionMode)} — 设置权限模式</li>
 *   <li>{@link #clearAll()} — 清除所有规则</li>
 * </ul>
 * 不再读取/写入 ~/.hms-core/settings.json 等磁盘文件。
 */
public class PermissionSettings {

    /**
     * 保护下方所有规则列表与模式字段的读写。
     * <p>
     * 本类是单例 Bean 且处于权限评估热路径：{@link #getAllRules()} 在每次工具调用
     * 时被遍历，而 {@link #addUserRule} 会在用户点「始终允许/拒绝」时并发写入。
     * 无锁的 ArrayList 在此场景下不仅抛异常，更会让评估读到残缺的规则集 ——
     * <b>DENY 规则可能在并发写入期间不可见，导致被禁工具被放行</b>。
     */
    private final Object lock = new Object();

    /** 内存中的合并规则（从所有来源加载后合并） */
    private final List<PermissionRule> sessionRules = new ArrayList<>();
    /** 当前生效的权限模式（默认 DEFAULT，优先取项目级模式） */
    private PermissionTypes.PermissionMode currentMode = PermissionTypes.PermissionMode.DEFAULT;

    /** 项目级规则（内存，由 API 设置） */
    private final List<String> projectAllowRules = new ArrayList<>();
    private final List<String> projectDenyRules = new ArrayList<>();
    /** 项目级权限模式（优先级最高） */
    private PermissionTypes.PermissionMode projectMode;

    /** 用户级规则（内存，由 API 设置） */
    private final List<String> userAllowRules = new ArrayList<>();
    private final List<String> userDenyRules = new ArrayList<>();
    private final List<String> userAskRules = new ArrayList<>();
    /** 用户级权限模式（项目级未设置时生效） */
    private PermissionTypes.PermissionMode userMode;

    public PermissionSettings() {
    }

    /**
     * 设置项目级权限规则（替代从 .hms-core/settings.json 读取）。
     * SDK 调用方通过此 API 注入配置。
     */
    public void setProjectRules(List<String> allowRules, List<String> denyRules, PermissionTypes.PermissionMode mode) {
        synchronized (lock) {
            this.projectAllowRules.clear();
            if (allowRules != null) this.projectAllowRules.addAll(allowRules);
            this.projectDenyRules.clear();
            if (denyRules != null) this.projectDenyRules.addAll(denyRules);
            this.projectMode = mode;
            recomputeMode();
        }
    }

    /**
     * 设置用户级权限规则（替代从 ~/.hms-core/settings.json 读取）。
     */
    public void setUserRules(List<String> allowRules, List<String> denyRules, PermissionTypes.PermissionMode mode) {
        synchronized (lock) {
            this.userAllowRules.clear();
            if (allowRules != null) this.userAllowRules.addAll(allowRules);
            this.userDenyRules.clear();
            if (denyRules != null) this.userDenyRules.addAll(denyRules);
            this.userMode = mode;
            recomputeMode();
        }
    }

    /**
     * 重新计算当前生效模式 —— 优先级：项目级 > 用户级 > 默认 DEFAULT。
     * <p>调用方必须已持有 {@link #lock}。</p>
     */
    private void recomputeMode() {
        if (projectMode != null) {
            currentMode = projectMode;
        } else if (userMode != null) {
            currentMode = userMode;
        }
        // 否则保持 DEFAULT
    }

    /**
     * 获取所有合并后的规则（项目级 > 用户级 > 会话级）。
     */
    public List<PermissionRule> getAllRules() {
        synchronized (lock) {
            var rules = new ArrayList<PermissionRule>();
            // 项目级优先
            rules.addAll(toRules(projectAllowRules, PermissionBehavior.ALLOW));
            rules.addAll(toRules(projectDenyRules, PermissionBehavior.DENY));
            // 用户级
            rules.addAll(toRules(userAllowRules, PermissionBehavior.ALLOW));
            rules.addAll(toRules(userDenyRules, PermissionBehavior.DENY));
            rules.addAll(toRules(userAskRules, PermissionBehavior.ASK));
            // 会话级
            rules.addAll(sessionRules);
            return rules;
        }
    }

    /**
     * 添加规则到用户级规则（内存中，SDK 调用方自行持久化）。
     */
    public void addUserRule(PermissionRule rule) {
        String formatted = formatRule(rule);
        synchronized (lock) {
            if (rule.behavior() == PermissionBehavior.ALLOW) {
                userAllowRules.add(formatted);
            } else if (rule.behavior() == PermissionBehavior.DENY) {
                userDenyRules.add(formatted);
            } else if (rule.behavior() == PermissionBehavior.ASK) {
                userAskRules.add(formatted);
            }
        }
    }

    /**
     * 添加规则到会话级（不持久化）。
     */
    public void addSessionRule(PermissionRule rule) {
        synchronized (lock) {
            sessionRules.add(rule);
        }
    }

    /**
     * 移除用户级规则。
     */
    public void removeUserRule(String ruleStr) {
        synchronized (lock) {
            userAllowRules.remove(ruleStr);
            userDenyRules.remove(ruleStr);
            userAskRules.remove(ruleStr);
        }
    }

    /**
     * 清除所有规则。
     */
    public void clearAll() {
        synchronized (lock) {
            userAllowRules.clear();
            userDenyRules.clear();
            userAskRules.clear();
            projectAllowRules.clear();
            projectDenyRules.clear();
            sessionRules.clear();
        }
    }

    public PermissionTypes.PermissionMode getCurrentMode() {
        synchronized (lock) {
            return currentMode;
        }
    }

    public void setCurrentMode(PermissionTypes.PermissionMode mode) {
        // 兼容旧模式名映射
        PermissionTypes.PermissionMode normalized = normalizeLegacyMode(mode);
        synchronized (lock) {
            this.currentMode = normalized;
            this.userMode = normalized;
        }
    }

    /**
     * 兼容旧模式名到新模式名。
     * <ul>
     *   <li>旧 PLAN → 新 STRICT</li>
     *   <li>旧 ACCEPT_EDITS → 新 TRUSTED</li>
     *   <li>旧 DONT_ASK → 新 SAFE</li>
     * </ul>
     */
    static PermissionTypes.PermissionMode normalizeLegacyMode(PermissionTypes.PermissionMode mode) {
        if (mode == null) return PermissionTypes.PermissionMode.DEFAULT;
        // 新模式原样返回
        // 旧模式映射（兼容）—— 通过枚举名匹配
        String modeName = mode.name();
        if ("PLAN".equals(modeName)) return PermissionTypes.PermissionMode.STRICT;
        if ("ACCEPT_EDITS".equals(modeName)) return PermissionTypes.PermissionMode.TRUSTED;
        if ("DONT_ASK".equals(modeName)) return PermissionTypes.PermissionMode.SAFE;
        return mode;
    }

    /**
     * 获取所有已保存规则的可读列表。
     */
    public List<String> listRules() {
        var result = new ArrayList<String>();
        synchronized (lock) {
            for (var r : userAllowRules) {
                result.add("[user] ALLOW " + r);
            }
            for (var r : userDenyRules) {
                result.add("[user] DENY  " + r);
            }
            for (var r : userAskRules) {
                result.add("[user] ASK   " + r);
            }
            for (var r : projectAllowRules) {
                result.add("[proj] ALLOW " + r);
            }
            for (var r : projectDenyRules) {
                result.add("[proj] DENY  " + r);
            }
            for (var r : sessionRules) {
                result.add("[sess] " + r.behavior() + " " + formatRule(r));
            }
        }
        return result;
    }

    // ── 内部方法 ──

    /**
     * 将规则字符串列表按指定行为解析为 {@link PermissionRule} 列表。
     * <p>调用方必须已持有 {@link #lock}。</p>
     */
    private List<PermissionRule> toRules(List<String> ruleStrings, PermissionBehavior behavior) {
        return ruleStrings.stream()
                .map(s -> parseRule(s, behavior))
                .toList();
    }

    /** 解析规则字符串，格式: "ToolName(pattern)" 或 "ToolName" */
    static PermissionRule parseRule(String ruleStr, PermissionBehavior behavior) {
        int parenStart = ruleStr.indexOf('(');
        if (parenStart > 0 && ruleStr.endsWith(")")) {
            String toolName = ruleStr.substring(0, parenStart);
            String content = ruleStr.substring(parenStart + 1, ruleStr.length() - 1);
            return new PermissionRule(toolName, content, behavior);
        }
        return PermissionRule.forTool(ruleStr, behavior);
    }

    /** 格式化规则为字符串 */
    static String formatRule(PermissionRule rule) {
        if ("*".equals(rule.ruleContent())) {
            return rule.toolName();
        }
        return rule.toolName() + "(" + rule.ruleContent() + ")";
    }

    // ── JSON 数据结构（保留用于可能的序列化场景） ──

    /**
     * 序列化数据结构根节点（保留用于兼容旧版 settings.json 读取场景）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SettingsData {
        public PermissionsBlock permissions = new PermissionsBlock();
    }

    /**
     * 权限块 —— 权限模式与放行/拒绝/附加目录规则。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PermissionsBlock {
        public PermissionTypes.PermissionMode mode; // 权限模式
        public List<String> alwaysAllow = new ArrayList<>(); // 始终放行的规则列表
        public List<String> alwaysDeny = new ArrayList<>(); // 始终拒绝的规则列表
        public List<String> additionalDirectories = new ArrayList<>(); // 额外允许访问的目录
    }
}
