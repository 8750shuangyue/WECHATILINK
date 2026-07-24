package com.example.demo.chat.repository;

import com.example.demo.chat.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CachedChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(CachedChatMemoryRepository.class);

    private final RedisChatMemoryRepository redisRepository;
    private final DatabaseChatMemoryRepository databaseRepository;
    private boolean databaseAvailable = true;

    public CachedChatMemoryRepository(RedisChatMemoryRepository redisRepository, 
                                      DatabaseChatMemoryRepository databaseRepository) {
        this.redisRepository = redisRepository;
        this.databaseRepository = databaseRepository;
        migrateExistingData();
    }

    private void migrateExistingData() {
        logger.info("Starting data migration from Redis to Database...");
        try {
            Thread migrationThread = new Thread(() -> {
                try {
                    List<ChatMessage> sampleMessages = redisRepository.getMessages("sample");
                    logger.info("Data migration check completed");
                } catch (Exception e) {
                    logger.warn("Redis not available during migration check", e);
                }
            });
            migrationThread.setDaemon(true);
            migrationThread.start();
        } catch (Exception e) {
            logger.warn("Data migration skipped due to error", e);
        }
    }

    @Override
    public List<ChatMessage> getMessages(String conversationId) {
        List<ChatMessage> messages = redisRepository.getMessages(conversationId);
        
        if (messages.isEmpty() && databaseAvailable) {
            try {
                messages = databaseRepository.getMessages(conversationId);
                if (!messages.isEmpty()) {
                    redisRepository.saveMessages(conversationId, messages);
                    logger.info("Loaded messages from Database and cached in Redis: {}", conversationId);
                }
            } catch (Exception e) {
                logger.warn("Failed to get messages from Database, using Redis/memory only: {}", e.getMessage());
                databaseAvailable = false;
            }
        }
        
        return messages;
    }

    @Override
    public void saveMessages(String conversationId, List<ChatMessage> messages) {
        redisRepository.saveMessages(conversationId, messages);
        
        if (databaseAvailable) {
            try {
                databaseRepository.saveMessages(conversationId, messages);
            } catch (Exception e) {
                logger.warn("Failed to save messages to Database, using Redis only: {}", e.getMessage());
                databaseAvailable = false;
            }
        }
    }

    @Override
    public void addMessage(String conversationId, ChatMessage message) {
        redisRepository.addMessage(conversationId, message);
        
        if (databaseAvailable) {
            try {
                databaseRepository.addMessage(conversationId, message);
            } catch (Exception e) {
                logger.warn("Failed to add message to Database, using Redis only: {}", e.getMessage());
                databaseAvailable = false;
            }
        }
    }

    @Override
    public void clear(String conversationId) {
        redisRepository.clear(conversationId);
        
        if (databaseAvailable) {
            try {
                databaseRepository.clear(conversationId);
            } catch (Exception e) {
                logger.warn("Failed to clear messages from Database: {}", e.getMessage());
            }
        }
    }

    @Override
    public boolean exists(String conversationId) {
        boolean existsInRedis = redisRepository.exists(conversationId);
        if (existsInRedis) {
            return true;
        }
        
        if (databaseAvailable) {
            try {
                return databaseRepository.exists(conversationId);
            } catch (Exception e) {
                logger.warn("Failed to check existence in Database: {}", e.getMessage());
                databaseAvailable = false;
            }
        }
        
        return false;
    }

    public boolean isDatabaseAvailable() {
        return databaseAvailable;
    }
}