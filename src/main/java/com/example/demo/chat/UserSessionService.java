package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class UserSessionService {

    private static final Logger logger = LoggerFactory.getLogger(UserSessionService.class);

    private static final String KEY_PREFIX = "chat:session:";
    private static final int DEFAULT_EXPIRE_MINUTES = 5;

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final Map<String, UserSession> memoryCache = new ConcurrentHashMap<>();

    public UserSessionService(StringRedisTemplate redisTemplate) {
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

    public UserSession getSession(String userId) {
        if (!isRedisAvailable()) {
            logger.warn("Redis not available, using memory cache for getSession: {}", userId);
            return memoryCache.get(userId);
        }
        
        String key = buildKey(userId);
        String json = null;
        
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            logger.error("Redis get operation failed for key: {}", key, e);
            return memoryCache.get(userId);
        }
        
        if (json == null || json.isEmpty()) {
            return memoryCache.get(userId);
        }
        
        try {
            JSONObject jsonObj = JSON.parseObject(json);
            UserSession session = new UserSession();
            session.setUserId(userId);
            session.setPendingImageBase64(jsonObj.getString("pendingImageBase64"));
            session.setImageDescription(jsonObj.getString("imageDescription"));
            session.setImageAnalyzed(jsonObj.getBooleanValue("imageAnalyzed"));
            session.setPendingFileUrl(jsonObj.getString("pendingFileUrl"));
            session.setPendingFileName(jsonObj.getString("pendingFileName"));
            session.setFileAnalyzed(jsonObj.getBooleanValue("fileAnalyzed"));
            
            String timestampStr = jsonObj.getString("lastUpdateTime");
            if (timestampStr != null && !timestampStr.isEmpty()) {
                session.setLastUpdateTime(LocalDateTime.parse(timestampStr));
            } else {
                session.setLastUpdateTime(LocalDateTime.now());
            }
            
            memoryCache.put(userId, session);
            logger.debug("Loaded session for user {}, hasPendingImage: {}, imageAnalyzed: {}", 
                userId, session.hasPendingImage(), session.isImageAnalyzed());
            return session;
        } catch (Exception e) {
            logger.error("Failed to parse session from Redis", e);
            return memoryCache.get(userId);
        }
    }

    public void saveSession(UserSession session) {
        session.setLastUpdateTime(LocalDateTime.now());
        
        if (!isRedisAvailable()) {
            logger.warn("Redis not available, using memory cache for saveSession: {}", session.getUserId());
            memoryCache.put(session.getUserId(), session);
            return;
        }
        
        String key = buildKey(session.getUserId());
        
        JSONObject jsonObj = new JSONObject();
        jsonObj.put("userId", session.getUserId());
        jsonObj.put("pendingImageBase64", session.getPendingImageBase64());
        jsonObj.put("imageDescription", session.getImageDescription());
        jsonObj.put("imageAnalyzed", session.isImageAnalyzed());
        jsonObj.put("pendingFileUrl", session.getPendingFileUrl());
        jsonObj.put("pendingFileName", session.getPendingFileName());
        jsonObj.put("fileAnalyzed", session.isFileAnalyzed());
        jsonObj.put("lastUpdateTime", session.getLastUpdateTime().toString());
        
        String json = jsonObj.toJSONString();
        
        try {
            redisTemplate.opsForValue().set(key, json, DEFAULT_EXPIRE_MINUTES, TimeUnit.MINUTES);
            memoryCache.put(session.getUserId(), session);
            logger.debug("Saved session for user {}, pendingImageBase64 length: {}, imageAnalyzed: {}", 
                session.getUserId(), 
                session.getPendingImageBase64() != null ? session.getPendingImageBase64().length() : 0,
                session.isImageAnalyzed());
        } catch (Exception e) {
            logger.error("Redis set operation failed for key: {}", key, e);
            memoryCache.put(session.getUserId(), session);
        }
    }

    public void clearSession(String userId) {
        if (!isRedisAvailable()) {
            logger.warn("Redis not available, clearing from memory cache: {}", userId);
            memoryCache.remove(userId);
            return;
        }
        
        String key = buildKey(userId);
        try {
            redisTemplate.delete(key);
            memoryCache.remove(userId);
            logger.debug("Cleared session for user {}", userId);
        } catch (Exception e) {
            logger.error("Redis delete operation failed for key: {}", key, e);
            memoryCache.remove(userId);
        }
    }

    public void clearPendingImage(String userId) {
        UserSession session = getSession(userId);
        if (session != null) {
            session.clearPendingImage();
            saveSession(session);
            logger.debug("Cleared pending image for user {}", userId);
        }
    }

    public boolean hasPendingImage(String userId) {
        UserSession session = getSession(userId);
        return session != null && session.hasPendingImage();
    }

    public boolean hasUnanalyzedImage(String userId) {
        UserSession session = getSession(userId);
        return session != null && session.hasUnanalyzedImage();
    }

    public String getPendingImageBase64(String userId) {
        UserSession session = getSession(userId);
        return session != null ? session.getPendingImageBase64() : null;
    }
    
    public String getImageDescription(String userId) {
        UserSession session = getSession(userId);
        return session != null ? session.getImageDescription() : null;
    }

    public void storePendingImage(String userId, String imageBase64) {
        UserSession session = getSession(userId);
        if (session == null) {
            session = new UserSession(userId, imageBase64);
        } else {
            session.setPendingImageBase64(imageBase64);
            session.setImageAnalyzed(false);
        }
        saveSession(session);
        logger.info("Stored pending image for user {}, base64 length: {}", userId, imageBase64.length());
    }
    
    public void markImageAsAnalyzed(String userId) {
        UserSession session = getSession(userId);
        if (session != null) {
            session.setImageAnalyzed(true);
            saveSession(session);
            logger.info("Marked image as analyzed for user {}", userId);
        }
    }

    public boolean hasPendingFile(String userId) {
        UserSession session = getSession(userId);
        return session != null && session.hasPendingFile();
    }

    public boolean hasUnanalyzedFile(String userId) {
        UserSession session = getSession(userId);
        return session != null && session.hasUnanalyzedFile();
    }

    public String getPendingFileUrl(String userId) {
        UserSession session = getSession(userId);
        return session != null ? session.getPendingFileUrl() : null;
    }

    public String getPendingFileName(String userId) {
        UserSession session = getSession(userId);
        return session != null ? session.getPendingFileName() : null;
    }

    public void storePendingFile(String userId, String fileUrl, String fileName) {
        UserSession session = getSession(userId);
        if (session == null) {
            session = new UserSession();
            session.setUserId(userId);
        }
        session.setPendingFileUrl(fileUrl);
        session.setPendingFileName(fileName);
        session.setFileAnalyzed(false);
        saveSession(session);
        logger.info("Stored pending file for user {}, fileName: {}, fileUrl: {}", userId, fileName, fileUrl);
    }

    public void markFileAsAnalyzed(String userId) {
        UserSession session = getSession(userId);
        if (session != null) {
            session.setFileAnalyzed(true);
            saveSession(session);
            logger.info("Marked file as analyzed for user {}", userId);
        }
    }

    public void clearPendingFile(String userId) {
        UserSession session = getSession(userId);
        if (session != null) {
            session.clearPendingFile();
            saveSession(session);
            logger.debug("Cleared pending file for user {}", userId);
        }
    }

    private String buildKey(String userId) {
        return KEY_PREFIX + userId;
    }
}