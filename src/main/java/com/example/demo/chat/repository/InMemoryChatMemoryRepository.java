package com.example.demo.chat.repository;

import com.example.demo.chat.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryChatMemoryRepository implements ChatMemoryRepository {

    private final ConcurrentHashMap<String, List<ChatMessage>> memory = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(String userId, String conversationId) {
        return memory.getOrDefault(key(userId, conversationId), new ArrayList<>());
    }

    @Override
    public void saveMessages(String userId, String conversationId, List<ChatMessage> messages) {
        memory.put(key(userId, conversationId), new ArrayList<>(messages));
    }

    @Override
    public void addMessage(String userId, String conversationId, ChatMessage message) {
        memory.compute(key(userId, conversationId), (key, existing) -> {
            List<ChatMessage> list = existing != null ? existing : new ArrayList<>();
            list.add(message);
            return list;
        });
    }

    @Override
    public void clear(String userId, String conversationId) {
        memory.remove(key(userId, conversationId));
    }

    @Override
    public boolean exists(String userId, String conversationId) {
        return memory.containsKey(key(userId, conversationId));
    }

    @Override
    public void removeSystemMessages(String userId, String conversationId, String contentPrefix) {
        memory.computeIfPresent(key(userId, conversationId), (key, messages) -> {
            messages.removeIf(msg -> "system".equals(msg.getRole()) && 
                msg.getContent() != null && msg.getContent().startsWith(contentPrefix));
            return messages;
        });
    }

    private String key(String userId, String conversationId) {
        return userId + "\u0000" + conversationId;
    }
}
