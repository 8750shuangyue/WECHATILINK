package com.example.demo.care.service;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.ToolResult;
import com.example.demo.agent.tools.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PetFoodSafetyService {

    private static final Logger log = LoggerFactory.getLogger(PetFoodSafetyService.class);

    private final WebSearchTool webSearchTool;

    public PetFoodSafetyService(WebSearchTool webSearchTool) {
        this.webSearchTool = webSearchTool;
    }

    public String queryFoodSafety(String foodName, String petType) {
        if (foodName == null || foodName.isBlank()) {
            return "请提供具体的食物名称";
        }

        String pet = petType != null ? petType : "猫";
        String searchQuery = foodName + " 对" + pet + "是否有毒 安全性 中毒症状";

        try {
            JSONObject params = new JSONObject();
            params.put("query", searchQuery);
            params.put("count", 5);
            ToolResult<?> result = webSearchTool.execute(params);

            StringBuilder sb = new StringBuilder();
            sb.append("🍖 【宠物食品安全查询】\n");
            sb.append("食物：").append(foodName).append("\n");
            sb.append("宠物类型：").append(pet).append("\n\n");

            if (result.isSuccess()) {
                sb.append(result.getData()).append("\n");
            }

            sb.append("\n⚠️ 重要：以下信息仅供参考。如宠物误食危险食物并出现异常，请立即就医！\n");
            sb.append("📋 请根据以上信息，告知用户该食物是否安全、建议分量和中毒症状。如食物危险，提醒用户不要喂食。");
            return sb.toString();
        } catch (Exception e) {
            log.error("[FoodSafety] Query error: {}", e.getMessage(), e);
            return "食品安全查询出错：" + e.getMessage();
        }
    }
}