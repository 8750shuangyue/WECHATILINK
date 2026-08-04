package com.example.demo.chat.config;

import com.example.demo.chat.repository.ChatMemoryRepository;
import com.example.demo.chat.repository.DatabaseChatMemoryRepository;
import com.example.demo.chat.repository.mysql.ConversationRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChatMemoryConfig {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryConfig.class);

    @Bean
    @Primary
    public ChatMemoryRepository chatMemoryRepository(ConversationRepository conversationRepository,
                                                     MessageRepository messageRepository) {
        logger.info("========== Initializing DatabaseChatMemoryRepository (MySQL) as Primary ==========");
        return new DatabaseChatMemoryRepository(conversationRepository, messageRepository);
    }
}