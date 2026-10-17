package com.example.demo.chat;

import java.util.List;

/**
 * 一页历史消息，按 id 升序返回。
 * nextBeforeId 为继续向前翻页的排他游标；没有更多消息时为 null。
 */
public record HistoryPage(
        String conversationId,
        List<ChatHistoryEntry> messages,
        boolean hasMore,
        Long nextBeforeId
) {
    public HistoryPage {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
