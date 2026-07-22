package com.example.demo.chat.config;

import com.example.demo.chat.repository.ChatMemoryRepository;
import com.example.demo.chat.repository.InMemoryChatMemoryRepository;
import com.example.demo.chat.repository.RedisChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class ChatMemoryConfig {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryConfig.class);

    @Bean
    @Primary
    @ConditionalOnProperty(name = "chat.memory.type", havingValue = "redis")
    public ChatMemoryRepository redisChatMemoryRepository(StringRedisTemplate redisTemplate) {
        logger.info("========== Using RedisChatMemoryRepository ==========");
        return new RedisChatMemoryRepository(redisTemplate);
    }

    @Bean
    @ConditionalOnProperty(name = "chat.memory.type", havingValue = "memory", matchIfMissing = true)
    public ChatMemoryRepository inMemoryChatMemoryRepository() {
        logger.info("========== Using InMemoryChatMemoryRepository ==========");
        return new InMemoryChatMemoryRepository();
    }
}