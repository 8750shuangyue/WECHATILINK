package com.example.demo.care.service;

import com.example.demo.care.model.CareRecord;
import com.example.demo.care.model.CareTarget;
import com.example.demo.weather.model.WeatherResponse;
import com.example.demo.weather.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpringAiCareWorkflowService {

    private final MedicalTriageService triageService;
    private final CareRecordService recordService;
    private final CareReminderService reminderService;
    private final WeatherService weatherService;
    private final ChatModel chatModel;

    public WorkflowResult executeWorkflow(String userId, String userMessage, String targetId) {
        log.info("Executing care workflow: userId={}, targetId={}", userId, targetId);

        WorkflowResult result = new WorkflowResult();
        List<String> steps = new ArrayList<>();

        CareTarget target = null;
        String targetType = null;

        if (targetId != null && !targetId.isEmpty()) {
            try {
                target = recordService.getTarget(Long.parseLong(targetId), userId).orElse(null);
                if (target != null) {
                    targetType = target.getType().name();
                    steps.add("步骤1: 加载护理对象 [" + target.getName() + "]");
                }
            } catch (Exception e) {
                log.warn("Failed to load target: {}", e.getMessage());
            }
        }

        MedicalTriageService.TriageResult triageResult = triageService.analyze(userMessage, targetType);
        steps.add("步骤2: 急症判断 - 等级: " + triageResult.getLevel());
        result.setTriageLevel(triageResult.getLevel().name());

        if (triageResult.isEmergency()) {
            result.setEmergency(true);
            result.setResult(triageResult.getAdvice());
            result.setSteps(steps);
            return result;
        }

        WeatherResponse weather = null;
        String weatherContext = "";
        if (targetType != null) {
            try {
                weather = weatherService.getWeatherByCity("北京");
                weatherContext = String.format("天气信息：%s，温度：%s°C", 
                        weather.getCity(), weather.getCurrent().getTemperature());
                steps.add("步骤3: 获取天气信息 - " + weatherContext);
            } catch (Exception e) {
                log.warn("Failed to get weather: {}", e.getMessage());
            }
        }

        String careAdvice = generateCareAdvice(userMessage, targetType, target, weatherContext);
        steps.add("步骤4: 生成护理建议");
        result.setAdvice(careAdvice);

        CareRecord record = CareRecord.builder()
                .userId(userId)
                .targetType(target != null ? CareRecord.TargetType.valueOf(target.getType().name()) : CareRecord.TargetType.PET)
                .targetId(target != null ? target.getId() : null)
                .recordType(CareRecord.RecordType.ADVICE)
                .title("护理咨询")
                .content(careAdvice)
                .build();
        recordService.createRecord(record);
        steps.add("步骤5: 保存护理记录");

        result.setEmergency(false);
        result.setResult(careAdvice);
        result.setSteps(steps);
        return result;
    }

    private String generateCareAdvice(String userMessage, String targetType, 
                                      CareTarget target, String weatherContext) {
        String systemPrompt = """
            你是一个专业的宠物/植物护理助手。请根据用户描述、目标信息和天气情况，给出详细的护理建议。
            
            建议结构：
            1. 问题分析：分析用户描述的问题
            2. 护理建议：具体的护理措施
            3. 注意事项：需要特别注意的事项
            
            如果是紧急情况，请明确指出并建议立即就医。
            """;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("用户描述：").append(userMessage).append("\n");
        if (targetType != null) {
            userPrompt.append("目标类型：").append(targetType).append("\n");
        }
        if (target != null) {
            userPrompt.append("目标名称：").append(target.getName()).append("\n");
            if (target.getSpecies() != null) {
                userPrompt.append("品种：").append(target.getSpecies()).append("\n");
            }
            if (target.getBreed() != null) {
                userPrompt.append("种类：").append(target.getBreed()).append("\n");
            }
            if (target.getHealthStatus() != null) {
                userPrompt.append("健康状态：").append(target.getHealthStatus()).append("\n");
            }
        }
        if (!weatherContext.isEmpty()) {
            userPrompt.append(weatherContext).append("\n");
        }

        Prompt prompt = new Prompt(List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(userPrompt.toString())
        ));

        Object output = chatModel.call(prompt).getResult().getOutput();
        return output != null ? output.toString() : "无法生成护理建议";
    }

    public WorkflowResult createMedicationReminder(String userId, String targetId, 
                                                   String title, String content, 
                                                   LocalDateTime reminderTime) {
        log.info("Creating medication reminder: userId={}, targetId={}, title={}", userId, targetId, title);

        WorkflowResult result = new WorkflowResult();
        List<String> steps = new ArrayList<>();

        Long targetIdLong = null;
        if (targetId != null && !targetId.isEmpty()) {
            targetIdLong = Long.parseLong(targetId);
        }

        CareRecord reminder = reminderService.createReminder(userId, targetIdLong, title, content, reminderTime);
        steps.add("步骤1: 创建用药提醒 [" + title + "]");

        CareRecord medicationRecord = CareRecord.builder()
                .userId(userId)
                .targetType(CareRecord.TargetType.PET)
                .targetId(targetIdLong)
                .recordType(CareRecord.RecordType.MEDICATION)
                .title(title)
                .content(content)
                .build();
        recordService.createRecord(medicationRecord);
        steps.add("步骤2: 保存用药记录");

        result.setEmergency(false);
        result.setResult("提醒已创建，将在 " + reminderTime + " 提醒您");
        result.setSteps(steps);
        return result;
    }

    public static class WorkflowResult {
        private boolean emergency;
        private String triageLevel;
        private String advice;
        private String result;
        private List<String> steps;

        public boolean isEmergency() { return emergency; }
        public void setEmergency(boolean emergency) { this.emergency = emergency; }
        public String getTriageLevel() { return triageLevel; }
        public void setTriageLevel(String triageLevel) { this.triageLevel = triageLevel; }
        public String getAdvice() { return advice; }
        public void setAdvice(String advice) { this.advice = advice; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public List<String> getSteps() { return steps; }
        public void setSteps(List<String> steps) { this.steps = steps; }
    }
}
