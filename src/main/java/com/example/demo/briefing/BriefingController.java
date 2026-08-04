package com.example.demo.briefing;

import com.example.demo.aicare.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

    private static final Logger logger = LoggerFactory.getLogger(BriefingController.class);
    private final BriefingService briefingService;

    public BriefingController(BriefingService briefingService) {
        this.briefingService = briefingService;
    }

    @PostMapping("/generate")
    public Result<String> generateBriefing(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) return Result.error("未登录");

        try {
            String briefing = briefingService.generateBriefing(userId);
            return Result.success(briefing);
        } catch (Exception e) {
            logger.error("Failed to generate briefing", e);
            return Result.error("简报生成失败：" + e.getMessage());
        }
    }
}
