package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class VectorStoreService {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreService.class);
    
    private static final String INDEX_NAME = "chat_memory_index";
    private static final String VECTOR_FIELD = "vector";
    private static final String CONTENT_FIELD = "content";
    private static final String CONVERSATION_FIELD = "conversation_id";
    private static final String TIMESTAMP_FIELD = "timestamp";
    
    private final StringRedisTemplate redisTemplate;
    private final EmbeddingService embeddingService;
    
    @Value("${chat.vectorstore.top-k:3}")
    private int topK;
    
    @Value("${chat.vectorstore.similarity-threshold:0.5}")
    private double similarityThreshold;
    
    private boolean indexCreated = false;
    
    public VectorStoreService(StringRedisTemplate redisTemplate, EmbeddingService embeddingService) {
        this.redisTemplate = redisTemplate;
        this.embeddingService = embeddingService;
    }
    
    public void saveMessage(String conversationId, String userMessage, String assistantReply) {
        try {
            ensureIndexCreated();
            
            String combinedContent = "用户: " + userMessage + "\n助手: " + assistantReply;
            float[] embedding = embeddingService.embed(combinedContent);
            
            if (embedding.length == 0) {
                logger.warn("Embedding is empty, skipping save");
                return;
            }
            
            String docId = UUID.randomUUID().toString();
            String key = "vec:" + docId;
            
            Map<String, String> fields = new HashMap<>();
            fields.put(VECTOR_FIELD, new String(serializeVector(embedding), StandardCharsets.UTF_8));
            fields.put(CONTENT_FIELD, combinedContent);
            fields.put(CONVERSATION_FIELD, conversationId);
            fields.put(TIMESTAMP_FIELD, String.valueOf(System.currentTimeMillis()));
            
            redisTemplate.opsForHash().putAll(key, fields);
            
            logger.info("Saved vector for conversation {}, docId: {}", conversationId, docId);
                
        } catch (Exception e) {
            logger.error("Failed to save vector to Redis", e);
        }
    }
    
    public List<String> searchSimilar(String query, String conversationId) {
        List<String> results = new ArrayList<>();
        
        try {
            float[] queryEmbedding = embeddingService.embed(query);
            if (queryEmbedding.length == 0) {
                logger.warn("Query embedding is empty");
                return results;
            }
            
            ensureIndexCreated();
            
            String vectorString = formatVector(queryEmbedding);
            
            String searchResult = redisTemplate.execute((RedisCallback<String>) connection -> {
                byte[][] args = new byte[][]{
                    INDEX_NAME.getBytes(StandardCharsets.UTF_8),
                    ("@" + VECTOR_FIELD + ":[" + vectorString + "]=>{$vector_distance: L2}").getBytes(StandardCharsets.UTF_8),
                    "LIMIT".getBytes(StandardCharsets.UTF_8),
                    "0".getBytes(StandardCharsets.UTF_8),
                    String.valueOf(topK).getBytes(StandardCharsets.UTF_8),
                    "RETURN".getBytes(StandardCharsets.UTF_8),
                    "1".getBytes(StandardCharsets.UTF_8),
                    CONTENT_FIELD.getBytes(StandardCharsets.UTF_8),
                    "SORTBY".getBytes(StandardCharsets.UTF_8),
                    "$vector_distance".getBytes(StandardCharsets.UTF_8),
                    "ASC".getBytes(StandardCharsets.UTF_8)
                };
                
                Object resultObj = connection.execute("FT.SEARCH", args);
                if (resultObj == null) {
                    return null;
                }
                if (resultObj instanceof byte[]) {
                    return new String((byte[]) resultObj, StandardCharsets.UTF_8);
                }
                return resultObj.toString();
            });
            
            if (searchResult != null && !searchResult.isEmpty()) {
                results = parseSearchResults(searchResult);
                logger.info("Search found {} results", results.size());
            }
            
        } catch (Exception e) {
            logger.error("Failed to search vector in Redis", e);
        }
        
        return results;
    }
    
    private void ensureIndexCreated() {
        if (indexCreated) {
            return;
        }
        
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                try {
                    connection.execute("FT.DROPINDEX", INDEX_NAME.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    logger.debug("Index doesn't exist, skipping drop");
                }
                
                int dim = 1024;
                
                byte[][] args = new byte[][]{
                    INDEX_NAME.getBytes(StandardCharsets.UTF_8),
                    "ON".getBytes(StandardCharsets.UTF_8),
                    "HASH".getBytes(StandardCharsets.UTF_8),
                    "PREFIX".getBytes(StandardCharsets.UTF_8),
                    "1".getBytes(StandardCharsets.UTF_8),
                    "vec:".getBytes(StandardCharsets.UTF_8),
                    "SCHEMA".getBytes(StandardCharsets.UTF_8),
                    VECTOR_FIELD.getBytes(StandardCharsets.UTF_8),
                    "VECTOR".getBytes(StandardCharsets.UTF_8),
                    "FLAT".getBytes(StandardCharsets.UTF_8),
                    "6".getBytes(StandardCharsets.UTF_8),
                    "TYPE".getBytes(StandardCharsets.UTF_8),
                    "FLOAT32".getBytes(StandardCharsets.UTF_8),
                    "DIM".getBytes(StandardCharsets.UTF_8),
                    String.valueOf(dim).getBytes(StandardCharsets.UTF_8),
                    "DISTANCE_METRIC".getBytes(StandardCharsets.UTF_8),
                    "L2".getBytes(StandardCharsets.UTF_8),
                    CONTENT_FIELD.getBytes(StandardCharsets.UTF_8),
                    "TEXT".getBytes(StandardCharsets.UTF_8),
                    CONVERSATION_FIELD.getBytes(StandardCharsets.UTF_8),
                    "TEXT".getBytes(StandardCharsets.UTF_8),
                    TIMESTAMP_FIELD.getBytes(StandardCharsets.UTF_8),
                    "NUMERIC".getBytes(StandardCharsets.UTF_8)
                };
                
                connection.execute("FT.CREATE", args);
                return null;
            });
            
            indexCreated = true;
            logger.info("Redis vector index created successfully");
            
        } catch (Exception e) {
            logger.warn("Failed to create vector index, Redis may not have RediSearch module: {}", e.getMessage());
        }
    }
    
    private String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
    
    private byte[] serializeVector(float[] vector) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
    
    private List<String> parseSearchResults(String response) {
        List<String> results = new ArrayList<>();
        
        try {
            Object parsed = JSON.parse(response);
            if (!(parsed instanceof List)) {
                return results;
            }
            
            List<?> list = (List<?>) parsed;
            if (list.isEmpty()) {
                return results;
            }
            
            long total = ((Number) list.get(0)).longValue();
            
            for (int i = 1; i < list.size(); i += 4) {
                if (i + 3 < list.size()) {
                    Object contentObj = list.get(i + 3);
                    if (contentObj instanceof String) {
                        results.add((String) contentObj);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.warn("Failed to parse search results: {}", e.getMessage());
        }
        
        return results;
    }
    
    public void clearConversationVectors(String conversationId) {
        try {
            Set<String> keys = redisTemplate.keys("vec:*");
            if (keys != null) {
                for (String key : keys) {
                    Object convIdObj = redisTemplate.opsForHash().get(key, CONVERSATION_FIELD);
                    String convId = convIdObj != null ? convIdObj.toString() : null;
                    if (conversationId.equals(convId)) {
                        redisTemplate.delete(key);
                    }
                }
            }
            logger.info("Cleared vectors for conversation: {}", conversationId);
        } catch (Exception e) {
            logger.error("Failed to clear conversation vectors", e);
        }
    }
}