package com.example.demo.care.service;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.ToolResult;
import com.example.demo.agent.tools.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlantSafetyQueryService {

    private static final Logger log = LoggerFactory.getLogger(PlantSafetyQueryService.class);

    private final WebSearchTool webSearchTool;

    public PlantSafetyQueryService(WebSearchTool webSearchTool) {
        this.webSearchTool = webSearchTool;
    }

    public String queryPlantSafety(String queryType, String plantName, String question) {
        if (question == null || question.isBlank()) {
            return "请提供具体的植物安全性问题";
        }

        String typeLabel = getTypeLabel(queryType);
        String searchQuery;
        if (plantName != null && !plantName.isBlank()) {
            searchQuery = plantName + " " + typeLabel + " 猫 狗 宠物安全";
        } else {
            searchQuery = question + " " + typeLabel + " 植物 宠物 有毒 安全";
        }

        try {
            JSONObject params = new JSONObject();
            params.put("query", searchQuery);
            params.put("count", 5);
            ToolResult<?> result = webSearchTool.execute(params);

            StringBuilder sb = new StringBuilder();
            sb.append("🌱 【植物对宠物安全性查询】\n");
            if (plantName != null && !plantName.isBlank()) {
                sb.append("植物名称：").append(plantName).append("\n");
            }
            sb.append("问题：").append(question).append("\n\n");
            sb.append("⚠️ 重要：以下信息仅供参考。如宠物已误食植物并出现异常，请立即就医！\n\n");

            if (result.isSuccess()) {
                sb.append(result.getData()).append("\n");
            }

            sb.append("\n📋 请根据以上植物毒性信息，告知用户该植物对宠物是否安全、误食症状及应急处理。如该植物有毒，推荐安全的替代植物。");
            return sb.toString();
        } catch (Exception e) {
            log.error("[PlantSafety] Query error: {}", e.getMessage(), e);
            return "植物安全性查询出错：" + e.getMessage();
        }
    }

    private String getTypeLabel(String queryType) {
        if (queryType == null) return "对宠物 毒性";
        return switch (queryType) {
            case "toxicity" -> "对宠物有毒 毒性";
            case "symptoms" -> "误食症状 中毒表现";
            case "emergency" -> "误食急救 应急处理";
            case "safe_plants" -> "宠物友好 安全植物 推荐";
            case "identify" -> "品种识别 是否对宠物有毒";
            default -> "对宠物 毒性";
        };
    }
}