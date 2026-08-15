package com.example.demo.briefing;

import com.example.demo.care.service.CareReminderService;
import com.example.demo.chat.LlmService;
import com.example.demo.push.WebPushService;
import com.example.demo.weather.model.WeatherResponse;
import com.example.demo.weather.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class BriefingService {

    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);
    private final WeatherService weatherService;
    private final CareReminderService careReminderService;
    private final LlmService llmService;
    private final WebPushService webPushService;
    private final List<String> recentBriefings = new CopyOnWriteArrayList<>();

    public BriefingService(WeatherService weatherService,
                           CareReminderService careReminderService,
                           LlmService llmService,
                           WebPushService webPushService) {
        this.weatherService = weatherService;
        this.careReminderService = careReminderService;
        this.llmService = llmService;
        this.webPushService = webPushService;
    }

    @Scheduled(cron = "0 0 8 * * ?")
    public void scheduledBriefing() {
        log.info("[Briefing] Starting scheduled daily briefing generation");
        try {
            String briefing = generateBriefingText(null);
            recentBriefings.add(0, briefing);
            if (recentBriefings.size() > 10) {
                recentBriefings.remove(recentBriefings.size() - 1);
            }
            // 每天 8 点自动生成后推送给所有已订阅用户
            webPushService.sendPushToAll("🌅 每日简报", briefing, "/briefing");
            log.info("[Briefing] Daily briefing generated, length: {}", briefing.length());
        } catch (Exception e) {
            log.error("[Briefing] Failed to generate daily briefing: {}", e.getMessage(), e);
        }
    }

    public String generateBriefing(String userId) {
        try {
            return generateBriefingText(userId);
        } catch (Exception e) {
            log.error("[Briefing] Failed to generate briefing for user {}: {}", userId, e.getMessage(), e);
            return "简报生成失败：" + e.getMessage();
        }
    }

    private String generateBriefingText(String userId) throws IOException {
        StringBuilder context = new StringBuilder();

        try {
            WeatherResponse weather = weatherService.getWeatherByCity("杭州");
            if (weather.getForecast() != null && !weather.getForecast().isEmpty()) {
                WeatherResponse.ForecastDay today = weather.getForecast().get(0);
                context.append("今日天气：").append(today.getDayWeather() != null ? today.getDayWeather() : "未知")
                        .append("，气温").append(today.getLowTemp()).append("~").append(today.getHighTemp()).append("℃\n");
            }
        } catch (Exception e) {
            context.append("今日天气：暂无数据\n");
        }

        if (userId != null) {
            try {
                List<Map<String, Object>> pending = careReminderService.getPendingReminders(userId);
                if (pending.isEmpty()) {
                    context.append("待完成护理任务：暂无\n");
                } else {
                    context.append("待完成护理任务：\n");
                    for (Map<String, Object> r : pending) {
                        context.append("- ").append(r.get("reminder_type")).append("：")
                                .append(r.get("content")).append(" [").append(r.get("due_at")).append("]\n");
                    }
                }
            } catch (Exception e) {
                context.append("待完成护理任务：暂无数据\n");
            }
        }

        String todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("MM-dd"));
        context.append("历史上的今天（").append(todayStr).append("）：回顾往年今日的护理记录\n");

        String systemPrompt = """
            你是一位贴心的宠物/植物护理助手。请根据以下信息，生成一份温馨的每日早间简报。

            要求：
            1. 以"早上好！"开头
            2. 根据天气给出对宠物/植物的影响提示（如：今天高温注意遛狗时间、今天暴雨减少浇水等）
            3. 列出待完成的护理任务（如果有的话）
            4. 写一条今日护理小知识
            5. 整体语气温暖亲切，像朋友在说话
            6. 总长度控制在300字以内
            7. 使用emoji让内容更生动
            """;

        return llmService.chat(context.toString(), systemPrompt);
    }
}
