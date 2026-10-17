package com.example.demo.chat;

import java.util.List;

/**
 * 历史回放中的一条消息。sources 只对 assistant 消息非空。
 */
public record ChatHistoryEntry(
        Long id,
        String role,
        String content,
        String timestamp,
        List<RagContextService.SourceRef> sources
) {
    public ChatHistoryEntry {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
