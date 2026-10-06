package com.example.demo.chat.config;

import com.example.demo.chat.entity.Conversation;
import com.example.demo.chat.repository.mysql.ConversationRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ChatMemoryMigration {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryMigration.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @Autowired
    public ChatMemoryMigration(ConversationRepository conversationRepository, 
                               MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeDatabase() {
        logger.info("========== Starting Chat Memory Initialization ==========");
        
        try {
            long conversationCount = conversationRepository.count();
            long messageCount = messageRepository.count();
            logger.info("Database initialized - conversations: {}, messages: {}", conversationCount, messageCount);
            
        } catch (Exception e) {
            logger.warn("Database initialization warning: {}", e.getMessage());
        }
        
        logger.info("========== Chat Memory Initialization Completed ==========");
    }
}