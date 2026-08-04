package com.example.demo.care.service;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.ToolResult;
import com.example.demo.agent.tools.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PetCareQueryService {

    private static final Logger log = LoggerFactory.getLogger(PetCareQueryService.class);

    private static final String API_URL = "https://uapis.cn/api/v1/search/aggregate";

    private final WebSearchTool webSearchTool;

    public PetCareQueryService(WebSearchTool webSearchTool) {
        this.webSearchTool = webSearchTool;
    }

    public String queryPetCare(String queryType, String petType, String question) {
        if (question == null || question.isBlank()) {
            return "请提供具体的宠物护理问题";
        }

        String typeLabel = getTypeLabel(queryType);
        String searchQuery;
        if (petType != null && !petType.isBlank()) {
            searchQuery = petType + " " + question + " " + typeLabel + " 兽医 专业建议";
        } else {
            searchQuery = question + " " + typeLabel + " 宠物 兽医";
        }

        try {
            JSONObject params = new JSONObject();
            params.put("query", searchQuery);
            params.put("count", 5);
            ToolResult<?> result = webSearchTool.execute(params);

            StringBuilder sb = new StringBuilder();
            sb.append("🐾 【宠物护理专业查询】\n");
            if (petType != null && !petType.isBlank()) {
                sb.append("宠物类型：").append(petType).append("\n");
            }
            sb.append("问题：").append(question).append("\n\n");

            if ("disease".equals(queryType) || "emergency".equals(queryType)) {
                sb.append("⚠️ 以下信息仅供参考，不能替代专业兽医诊断。如症状严重请立即就医！\n\n");
            }

            if (result.isSuccess()) {
                sb.append(result.getData()).append("\n");
            }

            sb.append("\n📋 请根据以上专业信息，结合你的知识给用户专业、温暖的回答。涉及疾病时必须提醒就医。");
            return sb.toString();
        } catch (Exception e) {
            log.error("[PetCare] Query error: {}", e.getMessage(), e);
            return "宠物护理查询出错：" + e.getMessage();
        }
    }

    private String getTypeLabel(String queryType) {
        if (queryType == null) return "";
        return switch (queryType) {
            case "feeding" -> "喂养";
            case "disease" -> "病症 治疗";
            case "vaccine" -> "疫苗";
            case "breed" -> "品种";
            case "training" -> "训练";
            case "care" -> "护理";
            case "behavior" -> "行为";
            case "emergency" -> "急救";
            default -> "";
        };
    }
}