package com.example.demo.push;

import com.example.demo.push.entity.PushSubscription;
import com.example.demo.push.repository.PushSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class WebPushService {

    private static final Logger logger = LoggerFactory.getLogger(WebPushService.class);

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final nl.martijndwars.webpush.PushService libraryPushService;
    private final boolean enabled;

    public WebPushService(PushSubscriptionRepository pushSubscriptionRepository,
                          @Value("${webpush.vapid.public-key}") String vapidPublicKey,
                          @Value("${webpush.vapid.private-key}") String vapidPrivateKey,
                          @Value("${webpush.vapid.subject}") String vapidSubject) {
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        nl.martijndwars.webpush.PushService tmp = null;
        boolean tmpEnabled = false;
        try {
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            tmp = new nl.martijndwars.webpush.PushService();
            // web-push 库使用标准 Base64 解码密钥；VAPID 密钥是 URL-safe 编码，
            // 必须转换（-/_ → +// 并补 =），否则解码出错导致 "Invalid point coordinates"
            tmp.setPublicKey(toStandardBase64(vapidPublicKey));
            tmp.setPrivateKey(toStandardBase64(vapidPrivateKey));
            tmp.setSubject(vapidSubject);
            tmpEnabled = true;
            logger.info("WebPush service initialized successfully");
        } catch (Throwable e) {
            logger.warn("WebPush unavailable (BouncyCastle missing or key error): {}. Push notifications disabled.", e.toString());
        }
        this.libraryPushService = tmp;
        this.enabled = tmpEnabled;
    }

    private static String toStandardBase64(String urlSafe) {
        if (urlSafe == null) {
            return null;
        }
        String std = urlSafe.replace('-', '+').replace('_', '/');
        int pad = (4 - std.length() % 4) % 4;
        for (int i = 0; i < pad; i++) {
            std += '=';
        }
        return std;
    }

    public void subscribe(String userId, String endpoint, String p256dh, String auth, String userAgent) {
        try {
            PushSubscription subscription = PushSubscription.builder()
                    .userId(userId)
                    .endpoint(endpoint)
                    .p256dh(p256dh)
                    .auth(auth)
                    .userAgent(userAgent)
                    .build();
            pushSubscriptionRepository.save(subscription);
            logger.info("Push subscription saved for user: {}", userId);
        } catch (Exception e) {
            logger.error("Failed to save push subscription: {}", e.getMessage(), e);
            throw new RuntimeException("保存推送订阅失败", e);
        }
    }

    public void unsubscribe(String endpoint) {
        try {
            pushSubscriptionRepository.deleteByEndpoint(endpoint);
            logger.info("Push subscription removed: {}", endpoint);
        } catch (Exception e) {
            logger.error("Failed to remove push subscription: {}", e.getMessage(), e);
            throw new RuntimeException("取消推送订阅失败", e);
        }
    }

    public void sendPushToAll(String title, String body, String url) {
        if (!enabled) {
            logger.debug("WebPush disabled, skipping push to all");
            return;
        }
        List<PushSubscription> subscriptions = pushSubscriptionRepository.findAll();
        for (PushSubscription sub : subscriptions) {
            sendPushNotification(sub, title, body, url);
        }
    }

    public void sendPushToUser(String userId, String title, String body, String url) {
        if (!enabled) {
            logger.debug("WebPush disabled, skipping push to user {}", userId);
            return;
        }
        List<PushSubscription> subscriptions = pushSubscriptionRepository.findByUserId(userId);
        for (PushSubscription sub : subscriptions) {
            sendPushNotification(sub, title, body, url);
        }
    }

    private void sendPushNotification(PushSubscription sub, String title, String body, String url) {
        try {
            String payloadJson = String.format(
                    "{\"title\":\"%s\",\"body\":\"%s\",\"url\":\"%s\",\"icon\":\"/favicon.ico\"}",
                    escapeJson(title), escapeJson(body), escapeJson(url)
            );
            byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
            nl.martijndwars.webpush.Notification notification =
                    new nl.martijndwars.webpush.Notification(sub.getEndpoint(), sub.getP256dh(), sub.getAuth(), payload);
            libraryPushService.send(notification);
        } catch (Exception e) {
            logger.warn("Failed to send push to user {}: {}", sub.getUserId(), e.getMessage());
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
