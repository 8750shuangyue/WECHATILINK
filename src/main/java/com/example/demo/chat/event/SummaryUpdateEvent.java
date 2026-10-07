package com.example.demo.chat.event;

public class SummaryUpdateEvent {
    
    private final String userId;
    private final String conversationId;
    
    public SummaryUpdateEvent(String userId, String conversationId) {
        this.userId = userId;
        this.conversationId = conversationId;
    }

    public String getUserId() {
        return userId;
    }
    
    public String getConversationId() {
        return conversationId;
    }
}
