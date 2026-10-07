package com.example.demo.chat.repository;

import com.example.demo.chat.ChatMessage;

import java.util.List;

public interface ChatMemoryRepository {

    List<ChatMessage> getMessages(String userId, String conversationId);

    void saveMessages(String userId, String conversationId, List<ChatMessage> messages);

    void addMessage(String userId, String conversationId, ChatMessage message);

    void clear(String userId, String conversationId);

    boolean exists(String userId, String conversationId);

    void removeSystemMessages(String userId, String conversationId, String contentPrefix);
}
