package com.example.demo.chat.listener;

import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.VectorStoreService;
import com.example.demo.chat.event.SummaryUpdateEvent;
import com.example.demo.chat.event.VectorSaveEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class MemoryEventListener {
    
    private static final Logger logger = LoggerFactory.getLogger(MemoryEventListener.class);
    
    private final VectorStoreService vectorStoreService;
    private final ChatMemoryService chatMemoryService;
    
    public MemoryEventListener(VectorStoreService vectorStoreService, ChatMemoryService chatMemoryService) {
        this.vectorStoreService = vectorStoreService;
        this.chatMemoryService = chatMemoryService;
    }
    
    @EventListener
    @Async("vectorTaskExecutor")
    public void handleVectorSaveEvent(VectorSaveEvent event) {
        try {
            logger.debug("Processing VectorSaveEvent for conversation: {}", event.getConversationId());
            vectorStoreService.saveMessage(event.getConversationId(), 
                    event.getUserMessage(), event.getAssistantReply());
            logger.debug("Vector saved successfully for conversation: {}", event.getConversationId());
        } catch (Exception e) {
            logger.error("Failed to save vector asynchronously for conversation: {}", 
                    event.getConversationId(), e);
        }
    }
    
    @EventListener
    @Async("summaryTaskExecutor")
    public void handleSummaryUpdateEvent(SummaryUpdateEvent event) {
        try {
            logger.debug("Processing SummaryUpdateEvent for conversation: {}", event.getConversationId());
            chatMemoryService.checkAndUpdateSummary(event.getConversationId());
            logger.debug("Summary updated successfully for conversation: {}", event.getConversationId());
        } catch (Exception e) {
            logger.error("Failed to update summary asynchronously for conversation: {}", 
                    event.getConversationId(), e);
        }
    }
}