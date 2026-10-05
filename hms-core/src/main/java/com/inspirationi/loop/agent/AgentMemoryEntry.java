package com.inspirationi.loop.agent;

import java.time.Instant;
import java.util.List;

/**
 * 一条 Agent 级记忆 —— 不可变快照。
 *
 * @param id        记忆唯一 ID
 * @param content   记忆正文（已归一化、已限长）
 * @param tags      标签（已归一化、去重，不可变）
 * @param createdAt 写入时间
 * @param source    来源标识 —— {@code "tool:<sessionId>"} 表示由模型经写工具写入，
 *                  {@code "api"} 表示由集成方经编程 API 写入。仅用于追溯，不参与逻辑。
 */
public record AgentMemoryEntry(
        String id,
        String content,
        List<String> tags,
        Instant createdAt,
        String source
) {
    /** 紧凑构造器：tags 固化为不可变副本，防止调用方持有可变引用。 */
    public AgentMemoryEntry {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
