package com.example.demo.chat.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String KEY_PREFIX = "chat:memory:";
    private static final int DEFAULT_EXPIRE_HOURS = 24;

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final Map<String, List<ChatMessage>> memoryCache = new ConcurrentHashMap<>();

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = redisTemplate.getConnectionFactory();
    }

    private boolean isRedisAvailable() {
        if (connectionFactory == null) {
            return false;
        }
        try {
            connectionFactory.getConnection().close();
            return true;
        } catch (Exception e) {
            logger.warn("Redis connection not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<ChatMessage> getMessages(String conversationId) {
        if (!isRedisAvailable()) {
            logger.warn("Redis not available, using memory cache for getMessages: {}", conversationId);
            return memoryCache.getOrDefault(conversationId, new ArrayList<>());
        }
        
        String key = buildKey(conversationId);
        logger.info("Redis getMessages - key: {}", key);
        String json = null;
        
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            logger.error("Redis get operation failed for key: {}", key, e);
            return memoryCache.getOrDefault(conversationId, new ArrayList<>());
        }
        
        logger.info("Redis getMessages - json: {}", json != null ? json.substring(0, Math.min(200, json.length())) + "..." : "null");
        
        if (json == null || json.isEmpty()) {
            logger.info("Redis getMessages - no data found for key: {}", key);
            return memoryCache.getOrDefault(conversationId, new ArrayList<>());
        }
        
        try {
            JSONArray jsonArray = JSON.parseArray(json);
            List<ChatMessage> messages = new ArrayList<>();
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                ChatMessage msg = new ChatMessage();
                msg.setRole(obj.getString("role"));
                msg.setContent(obj.getString("content"));
                msg.setTimestamp(obj.getString("timestamp"));
                messages.add(msg);
            }
            memoryCache.put(conversationId, messages);
            logger.info("Redis getMessages - loaded {} messages for key: {}", messages.size(), key);
            return messages;
        } catch (Exception e) {
            logger.error("Failed to parse messages from Redis", e);
            return memoryCache.getOrDefault(conversationId, new ArrayList<>());
        }
    }

    @Override
    public void saveMessages(String conversationId, List<ChatMessage> messages) {
        if (!isRedisAvailable()) {
            logger.warn("Redis not available, using memory cache for saveMessages: {}", conversationId);
            memoryCache.put(conversationId, new ArrayList<>(messages));
            return;
        }
        
        String key = buildKey(conversationId);
        String json = JSON.toJSONString(messages);
        logger.info("Redis saveMessages - key: {}, messages count: {}, json length: {}", key, messages.size(), json.length());
        
        try {
            redisTemplate.opsForValue().set(key, json, DEFAULT_EXPIRE_HOURS, TimeUnit.HOURS);
            memoryCache.put(conversationId, new ArrayList<>(messages));
            String verify = redisTemplate.opsForValue().get(key);
            logger.info("Redis saveMessages - verify: {}", verify != null ? "SUCCESS" : "FAILED");
        } catch (Exception e) {
            logger.error("Redis set operation failed for key: {}", key, e);
            memoryCache.put(conversationId, new ArrayList<>(messages));
        }
    }

    @Override
    public void addMessage(String conversationId, ChatMessage message) {
        logger.info("Redis addMessage - conversationId: {}, role: {}, content: {}", conversationId, message.getRole(), 
            message.getContent() != null ? message.getContent().substring(0, Math.min(50, message.getContent().length())) + "..." : "null");
        List<ChatMessage> messages = getMessages(conversationId);
        messages.add(message);
        saveMessages(conversationId, messages);
    }

    @Override
    public void clear(String conversationId) {
        if (!isRedisAvailable()) {
            logger.warn("Redis not available, clearing from memory cache: {}", conversationId);
            memoryCache.remove(conversationId);
            return;
        }
        
        String key = buildKey(conversationId);
        try {
            redisTemplate.delete(key);
            memoryCache.remove(conversationId);
            logger.info("Cleared conversation history from Redis for: {}", conversationId);
        } catch (Exception e) {
            logger.error("Redis delete operation failed for key: {}", key, e);
            memoryCache.remove(conversationId);
        }
    }

    @Override
    public boolean exists(String conversationId) {
        if (!isRedisAvailable()) {
            return memoryCache.containsKey(conversationId);
        }
        
        String key = buildKey(conversationId);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            logger.error("Redis hasKey operation failed for key: {}", key, e);
            return memoryCache.containsKey(conversationId);
        }
    }

    private String buildKey(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}