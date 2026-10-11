package com.example.demo.chat.exception;

public class ConversationAccessDeniedException extends RuntimeException {

    public ConversationAccessDeniedException(String conversationId) {
        super("Conversation access denied: " + conversationId);
    }
}
