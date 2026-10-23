package com.inspirationi.loop.tool.impl;

import com.inspirationi.loop.lsp.LSPServerManager;
import com.inspirationi.loop.tool.ToolContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 写文件之后把新内容同步给语言服务器 —— 「Edit 完再查诊断」必须看到新内容。
 * <p>
 * 没有这层同步时，语言服务器里留着的始终是它第一次打开该文件时的快照，
 * 模型改完代码再查 diagnostics，拿到的是修改前的问题列表，于是反复「修」同一个错误。
 * <p>
 * 全程 best-effort：任何失败只记 debug，绝不影响写入结果本身 ——
 * 语言服务器是增强能力，不能成为写文件的失败点。
 */
final class LspFileSync {

    private static final Logger log = LoggerFactory.getLogger(LspFileSync.class);

    private LspFileSync() {}

    /**
     * 把 {@code file} 的当前内容推给语言服务器（若该文件类型已有运行中的服务器）。
     * <p>
     * 只对<b>已经在运行</b>的服务器同步：{@code LSPServerManager.openFile} 会懒启动
     * 语言服务器，若在每次写文件时无脑调用，一次普通写入就会拉起一个后台进程。
     * 用户没配 LSP、或该扩展名没被使用过时，这里应当什么都不做。
     *
     * @param context 工具上下文，用于取 {@link LSPTool#MANAGER_KEY}
     * @param file    刚写完的文件（绝对路径）
     */
    static void afterWrite(ToolContext context, Path file) {
        if (context == null || file == null) return;

        Object managerObj = context.get(LSPTool.MANAGER_KEY);
        if (!(managerObj instanceof LSPServerManager manager)) return;

        String key = file.toString();
        if (!manager.isServerRunningForFile(key)) return;

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (manager.isFileOpen(key)) {
                manager.changeFile(key, content);
            } else {
                manager.openFile(key, content);
            }
            // didSave 同时会清掉该文件的已交付诊断，下一次查询才会重新检查
            manager.saveFile(key);
        } catch (Exception e) {
            log.debug("LSP sync after write failed for {}: {}", key, e.toString());
        }
    }
}
