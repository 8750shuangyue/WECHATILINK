package com.example.demo.chat.config;

import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.repository.CachedChatMemoryRepository;
import com.example.demo.chat.repository.RedisChatMemoryRepository;
import com.example.demo.chat.repository.DatabaseChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChatMemoryMigration {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryMigration.class);

    private final RedisChatMemoryRepository redisRepository;
    private final DatabaseChatMemoryRepository databaseRepository;

    @Autowired
    public ChatMemoryMigration(RedisChatMemoryRepository redisRepository, 
                               DatabaseChatMemoryRepository databaseRepository) {
        this.redisRepository = redisRepository;
        this.databaseRepository = databaseRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateExistingData() {
        logger.info("========== Starting Chat Memory Migration ==========");
        
        try {
            String testKey = "chat:memory:o9cq80zxm0L5kcJtoj6ZbeijGgGg@im.wechat";
            List<ChatMessage> existingMessages = redisRepository.getMessages("o9cq80zxm0L5kcJtoj6ZbeijGgGg@im.wechat");
            
            if (!existingMessages.isEmpty()) {
                logger.info("Found {} existing messages in Redis, checking Database...", existingMessages.size());
                
                if (!databaseRepository.exists("o9cq80zxm0L5kcJtoj6ZbeijGgGg@im.wechat")) {
                    logger.info("No records in Database, starting migration...");
                    databaseRepository.saveMessages("o9cq80zxm0L5kcJtoj6ZbeijGgGg@im.wechat", existingMessages);
                    logger.info("Migration completed: {} messages transferred to Database", existingMessages.size());
                } else {
                    logger.info("Records already exist in Database, skipping migration");
                }
            } else {
                logger.info("No existing messages found in Redis");
            }
        } catch (Exception e) {
            logger.warn("Data migration skipped due to error: {}", e.getMessage());
        }
        
        logger.info("========== Chat Memory Migration Completed ==========");
    }
}