package com.example.demo.chat.event;

public class SummaryUpdateEvent {
    
    private final String conversationId;
    
    public SummaryUpdateEvent(String conversationId) {
        this.conversationId = conversationId;
    }
    
    public String getConversationId() {
        return conversationId;
    }
}