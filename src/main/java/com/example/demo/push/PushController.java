package com.example.demo.push;

import com.example.demo.aicare.Result;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/push")
public class PushController {

    private static final Logger logger = LoggerFactory.getLogger(PushController.class);
    private final WebPushService webPushService;

    public PushController(WebPushService webPushService) {
        this.webPushService = webPushService;
    }

    @PostMapping("/subscribe")
    public Result<String> subscribe(@RequestBody Map<String, String> body, HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) return Result.error("未登录");

        String endpoint = body.get("endpoint");
        String p256dh = body.get("p256dh");
        String auth = body.get("auth");
        String userAgent = body.getOrDefault("userAgent", "");

        if (endpoint == null || p256dh == null || auth == null) {
            return Result.error("缺少必要的订阅信息");
        }

        try {
            webPushService.subscribe(userId, endpoint, p256dh, auth, userAgent);
            logger.info("Push subscribed for user: {}", userId);
            return Result.success("推送订阅成功");
        } catch (Exception e) {
            logger.error("Push subscribe failed: {}", userId, e);
            return Result.error("推送订阅失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/unsubscribe")
    public Result<String> unsubscribe(@RequestBody Map<String, String> body) {
        String endpoint = body.get("endpoint");
        if (endpoint == null) return Result.error("缺少endpoint参数");

        try {
            webPushService.unsubscribe(endpoint);
            return Result.success("推送取消订阅成功");
        } catch (Exception e) {
            logger.error("Push unsubscribe failed: {}", e.getMessage(), e);
            return Result.error("推送取消订阅失败：" + e.getMessage());
        }
    }

    @PostMapping("/test")
    public Result<String> testPush(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) return Result.error("未登录");

        try {
            webPushService.sendPushToUser(userId, "Sekai PetPlant", "这是一条测试推送通知", "/home.html");
            return Result.success("测试推送已发送");
        } catch (Exception e) {
            logger.error("Test push failed: {}", userId, e);
            return Result.error("测试推送发送失败：" + e.getMessage());
        }
    }
}
