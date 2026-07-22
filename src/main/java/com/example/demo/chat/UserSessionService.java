package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class UserSessionService {

    private static final Logger logger = LoggerFactory.getLogger(UserSessionService.class);

    private static final String KEY_PREFIX = "chat:session:";
    private static final int DEFAULT_EXPIRE_MINUTES = 5;

    private final StringRedisTemplate redisTemplate;

    public UserSessionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public UserSession getSession(String userId) {
        String key = buildKey(userId);
        String json = redisTemplate.opsForValue().get(key);
        
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        try {
            JSONObject jsonObj = JSON.parseObject(json);
            UserSession session = new UserSession();
            session.setUserId(userId);
            session.setPendingImageBase64(jsonObj.getString("pendingImageBase64"));
            
            String timestampStr = jsonObj.getString("lastUpdateTime");
            if (timestampStr != null && !timestampStr.isEmpty()) {
                session.setLastUpdateTime(LocalDateTime.parse(timestampStr));
            } else {
                session.setLastUpdateTime(LocalDateTime.now());
            }
            
            logger.debug("Loaded session for user {}, hasPendingImage: {}", userId, session.hasPendingImage());
            return session;
        } catch (Exception e) {
            logger.error("Failed to parse session from Redis", e);
            return null;
        }
    }

    public void saveSession(UserSession session) {
        String key = buildKey(session.getUserId());
        session.setLastUpdateTime(LocalDateTime.now());
        
        JSONObject jsonObj = new JSONObject();
        jsonObj.put("userId", session.getUserId());
        jsonObj.put("pendingImageBase64", session.getPendingImageBase64());
        jsonObj.put("lastUpdateTime", session.getLastUpdateTime().toString());
        
        String json = jsonObj.toJSONString();
        redisTemplate.opsForValue().set(key, json, DEFAULT_EXPIRE_MINUTES, TimeUnit.MINUTES);
        
        logger.debug("Saved session for user {}, pendingImageBase64 length: {}", 
            session.getUserId(), 
            session.getPendingImageBase64() != null ? session.getPendingImageBase64().length() : 0);
    }

    public void clearSession(String userId) {
        String key = buildKey(userId);
        redisTemplate.delete(key);
        logger.debug("Cleared session for user {}", userId);
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

    public String getPendingImageBase64(String userId) {
        UserSession session = getSession(userId);
        return session != null ? session.getPendingImageBase64() : null;
    }

    public void storePendingImage(String userId, String imageBase64) {
        UserSession session = getSession(userId);
        if (session == null) {
            session = new UserSession(userId, imageBase64);
        } else {
            session.setPendingImageBase64(imageBase64);
        }
        saveSession(session);
        logger.info("Stored pending image for user {}, base64 length: {}", userId, imageBase64.length());
    }

    private String buildKey(String userId) {
        return KEY_PREFIX + userId;
    }
}