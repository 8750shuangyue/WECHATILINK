package com.example.demo.care.service;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalTriageService {

    private final ChatModel chatModel;

    private static final Set<String> CRITICAL_SYMPTOMS = Set.of(
        "抽搐", "大出血", "昏迷", "呼吸困难", "窒息",
        "心跳停止", "休克", "骨折", "中毒", "高烧",
        "吐血", "便血", "尿血", "咳血", "脱水",
        "癫痫", "瘫痪", "肿胀", "疼痛难忍", "无法进食"
    );

    private static final Set<String> PET_CRITICAL_SYMPTOMS = Set.of(
        "拒食", "呕吐", "腹泻", "便血", "呼吸急促",
        "体温升高", "抽搐", "昏迷", "流口水", "牙龈苍白",
        "尿频", "尿少", "腹水", "黄疸", "精神萎靡"
    );

    private static final Set<String> PLANT_CRITICAL_SYMPTOMS = Set.of(
        "枯萎", "腐烂", "落叶", "发黄", "根腐",
        "虫害", "霉菌", "斑点", "脱水", "冻伤"
    );

    public TriageResult analyze(String userInput, String targetType) {
        log.info("Analyzing symptoms: input={}, targetType={}", 
                userInput.length() > 50 ? userInput.substring(0, 50) + "..." : userInput, targetType);

        Set<String> criticalSet = switch (targetType != null ? targetType.toUpperCase() : "") {
            case "PET" -> PET_CRITICAL_SYMPTOMS;
            case "PLANT" -> PLANT_CRITICAL_SYMPTOMS;
            default -> CRITICAL_SYMPTOMS;
        };

        String matchedCritical = findMatchedCritical(userInput, criticalSet);
        if (matchedCritical != null) {
            log.warn("Critical symptom detected: {}", matchedCritical);
            return TriageResult.critical(matchedCritical);
        }

        return analyzeWithAi(userInput, targetType);
    }

    private String findMatchedCritical(String input, Set<String> criticalSet) {
        for (String symptom : criticalSet) {
            if (input.contains(symptom)) {
                return symptom;
            }
        }
        return null;
    }

    private TriageResult analyzeWithAi(String userInput, String targetType) {
        String systemPrompt = """
            你是一个专业的宠物/植物护理分诊助手。请根据用户描述的症状，分析健康状况并给出建议。
            
            分诊等级（必须选择一个）：
            - CRITICAL: 紧急情况，需要立即就医或采取急救措施
            - HIGH: 较高风险，建议尽快咨询专业人士
            - MEDIUM: 中等风险，需要关注并采取护理措施
            - LOW: 低风险，日常护理即可
            
            分析要求：
            1. 明确分诊等级
            2. 给出具体原因
            3. 提供护理建议或就医建议
            
            请以JSON格式返回：{"level": "CRITICAL|HIGH|MEDIUM|LOW", "reason": "...", "advice": "..."}
            """;

        String userPrompt = String.format("目标类型：%s\n症状描述：%s", 
                targetType != null ? targetType : "未知", userInput);

        Prompt prompt = new Prompt(List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(userPrompt)
        ));

        try {
            Object output = chatModel.call(prompt).getResult().getOutput();
            String response = output != null ? output.toString() : "{}";
            
            return parseTriageResponse(response);
        } catch (Exception e) {
            log.error("AI triage failed", e);
            return TriageResult.medium("AI分析失败，建议谨慎对待");
        }
    }

    private TriageResult parseTriageResponse(String response) {
        try {
            TriageResponse triageResponse = JSON.parseObject(response, TriageResponse.class);
            return TriageResult.of(triageResponse.getLevel(), triageResponse.getReason(), triageResponse.getAdvice());
        } catch (Exception e) {
            log.warn("Failed to parse triage response: {}", response);
            return TriageResult.medium("无法解析AI分析结果");
        }
    }

    public static class TriageResult {
        private final TriageLevel level;
        private final String reason;
        private final String advice;
        private final boolean isEmergency;

        private TriageResult(TriageLevel level, String reason, String advice) {
            this.level = level;
            this.reason = reason;
            this.advice = advice;
            this.isEmergency = level == TriageLevel.CRITICAL;
        }

        public static TriageResult critical(String reason) {
            return new TriageResult(TriageLevel.CRITICAL, reason, 
                    "⚠️ 紧急情况！请立即联系专业兽医/园艺师或前往最近的医疗机构！");
        }

        public static TriageResult high(String reason, String advice) {
            return new TriageResult(TriageLevel.HIGH, reason, advice);
        }

        public static TriageResult medium(String reason) {
            return new TriageResult(TriageLevel.MEDIUM, reason, "建议密切观察，如有恶化请及时就医");
        }

        public static TriageResult of(String levelStr, String reason, String advice) {
            TriageLevel level = switch (levelStr != null ? levelStr.toUpperCase() : "") {
                case "CRITICAL" -> TriageLevel.CRITICAL;
                case "HIGH" -> TriageLevel.HIGH;
                case "MEDIUM" -> TriageLevel.MEDIUM;
                default -> TriageLevel.LOW;
            };
            return new TriageResult(level, reason, advice);
        }

        public TriageLevel getLevel() { return level; }
        public String getReason() { return reason; }
        public String getAdvice() { return advice; }
        public boolean isEmergency() { return isEmergency; }
    }

    public enum TriageLevel {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public static class TriageResponse {
        private String level;
        private String reason;
        private String advice;

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getAdvice() { return advice; }
        public void setAdvice(String advice) { this.advice = advice; }
    }
}
