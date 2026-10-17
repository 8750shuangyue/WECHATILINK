package com.example.demo.chat.repository;

import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.HistoryPage;

import java.util.List;

public interface ChatMemoryRepository {

    List<ChatMessage> getMessages(String userId, String conversationId);

    void saveMessages(String userId, String conversationId, List<ChatMessage> messages);

    void addMessage(String userId, String conversationId, ChatMessage message);

    /**
     * 写入一条消息，并可为 assistant 消息附带来源快照。
     * 未实现该能力的仓储忽略 sourcesJson；正式实现必须覆盖。
     */
    default void addMessage(String userId, String conversationId, ChatMessage message,
                            String sourcesJson) {
        addMessage(userId, conversationId, message);
    }

    void clear(String userId, String conversationId);

    boolean exists(String userId, String conversationId);

    void removeSystemMessages(String userId, String conversationId, String contentPrefix);

    /**
     * 读取一页历史消息（仅 user/assistant），用于界面回放。
     * 默认实现返回空页，方便测试替身；正式仓储必须覆盖。
     */
    default HistoryPage getHistory(String userId, String conversationId, Long beforeId, int limit) {
        return new HistoryPage(conversationId, List.of(), false, null);
    }
}
