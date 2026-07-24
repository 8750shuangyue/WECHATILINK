package com.example.demo.chat.repository;

import com.example.demo.chat.ChatMessage;

import java.util.List;

public interface ChatMemoryRepository {

    List<ChatMessage> getMessages(String conversationId);

    void saveMessages(String conversationId, List<ChatMessage> messages);

    void addMessage(String conversationId, ChatMessage message);

    void clear(String conversationId);

    boolean exists(String conversationId);
}