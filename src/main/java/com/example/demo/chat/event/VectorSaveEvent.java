package com.example.demo.chat.event;

public class VectorSaveEvent {
    
    private final String conversationId;
    private final String userMessage;
    private final String assistantReply;
    
    public VectorSaveEvent(String conversationId, String userMessage, String assistantReply) {
        this.conversationId = conversationId;
        this.userMessage = userMessage;
        this.assistantReply = assistantReply;
    }
    
    public String getConversationId() {
        return conversationId;
    }
    
    public String getUserMessage() {
        return userMessage;
    }
    
    public String getAssistantReply() {
        return assistantReply;
    }
}