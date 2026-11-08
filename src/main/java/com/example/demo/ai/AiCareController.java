package com.example.demo.ai;

import com.example.demo.care.model.CareRecord;
import com.example.demo.care.model.CareTarget;
import com.example.demo.care.service.CareRecordService;
import com.example.demo.care.service.CareReminderService;
import com.example.demo.care.service.MedicalTriageService;
import com.example.demo.care.service.SpringAiCareWorkflowService;
import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.RagContextService;
import com.example.demo.chat.RetrievalTraceStats;
import com.example.demo.chat.exception.ConversationAccessDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/ai/care")
@RequiredArgsConstructor
public class AiCareController {

    private final SpringAiCareWorkflowService careWorkflowService;
    private final CareRecordService careRecordService;
    private final CareReminderService careReminderService;
    private final MedicalTriageService medicalTriageService;

    @PostMapping("/workflow")
    public ResponseEntity<Map<String, Object>> careWorkflow(@RequestBody Map<String, String> request,
                                                            HttpSession session) {
        String userId = (String) session.getAttribute("user");
        String message = request.get("message");
        String targetId = request.get("targetId");
        
        log.info("Care workflow request: userId={}, targetId={}, message={}",
                userId, targetId, com.example.demo.core.LogSanitizer.describe(message));
        
        Map<String, Object> response = new HashMap<>();
        try {
            SpringAiCareWorkflowService.WorkflowResult result = careWorkflowService.executeWorkflow(userId, message, targetId);
            response.put("success", true);
            response.put("emergency", result.isEmergency());
            response.put("triageLevel", result.getTriageLevel());
            response.put("advice", result.getAdvice());
            response.put("result", result.getResult());
            response.put("steps", result.getSteps());
        } catch (Exception e) {
            log.error("Care workflow failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/triage")
    public ResponseEntity<Map<String, Object>> triage(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String targetType = request.get("targetType");
        
        log.info("Triage request: targetType={}, message={}", targetType,
                com.example.demo.core.LogSanitizer.describe(message));
        
        Map<String, Object> response = new HashMap<>();
        try {
            MedicalTriageService.TriageResult result = medicalTriageService.analyze(message, targetType);
            response.put("success", true);
            response.put("emergency", result.isEmergency());
            response.put("level", result.getLevel().name());
            response.put("reason", result.getReason());
            response.put("advice", result.getAdvice());
        } catch (Exception e) {
            log.error("Triage failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/target")
    public ResponseEntity<Map<String, Object>> createTarget(@RequestBody CareTarget target, HttpSession session) {
        target.setUserId((String) session.getAttribute("user"));
        log.info("Create care target: userId={}, name={}, type={}", 
                target.getUserId(), target.getName(), target.getType());
        
        Map<String, Object> response = new HashMap<>();
        try {
            CareTarget created = careRecordService.createTarget(target);
            response.put("success", true);
            response.put("data", created);
        } catch (Exception e) {
            log.error("Create target failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/targets")
    public ResponseEntity<Map<String, Object>> getTargets(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        log.info("Get care targets for userId={}", userId);
        
        Map<String, Object> response = new HashMap<>();
        try {
            List<CareTarget> targets = careRecordService.getTargetsByUser(userId);
            response.put("success", true);
            response.put("data", targets);
        } catch (Exception e) {
            log.error("Get targets failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reminder")
    public ResponseEntity<Map<String, Object>> createReminder(@RequestBody Map<String, Object> request,
                                                              HttpSession session) {
        String userId = (String) session.getAttribute("user");
        String targetId = (String) request.get("targetId");
        String title = (String) request.get("title");
        String content = (String) request.get("content");
        String reminderTimeStr = (String) request.get("reminderTime");
        
        log.info("Create reminder: userId={}, targetId={}, title={}", userId, targetId, title);
        
        Map<String, Object> response = new HashMap<>();
        try {
            LocalDateTime reminderTime = LocalDateTime.parse(reminderTimeStr);
            CareRecord reminder = careReminderService.createReminder(userId, 
                    targetId != null ? Long.parseLong(targetId) : null, title, content, reminderTime);
            response.put("success", true);
            response.put("data", reminder);
        } catch (Exception e) {
            log.error("Create reminder failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reminders")
    public ResponseEntity<Map<String, Object>> getPendingReminders(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        log.info("Get pending reminders for userId={}", userId);
        
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> reminders = careReminderService.getPendingReminders(userId);
            response.put("success", true);
            response.put("data", reminders);
        } catch (Exception e) {
            log.error("Get reminders failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reminders/pending")
    public ResponseEntity<Map<String, Object>> pendingReminderCount(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        Map<String, Object> response = new HashMap<>();
        if (userId == null) {
            response.put("success", false);
            response.put("error", "未登录");
            return ResponseEntity.ok(response);
        }
        try {
            int count = careReminderService.countPendingReminders(userId);
            response.put("success", true);
            response.put("count", count);
        } catch (Exception e) {
            log.error("Failed to count pending reminders", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reminder/{id}/complete")
    public ResponseEntity<Map<String, Object>> completeReminder(@PathVariable Long id,
                                                                @RequestBody Map<String, String> request,
                                                                HttpSession session) {
        String userId = (String) session.getAttribute("user");
        log.info("Complete reminder: id={}, userId={}", id, userId);
        
        Map<String, Object> response = new HashMap<>();
        try {
            careReminderService.completeReminder(id, userId);
            response.put("success", true);
        } catch (Exception e) {
            log.error("Complete reminder failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/records/medication")
    public ResponseEntity<Map<String, Object>> saveMedication(@RequestBody Map<String, Object> request,
                                                              HttpSession session) {
        String userId = (String) session.getAttribute("user");
        String targetType = (String) request.get("targetType");
        Long targetId = request.get("targetId") != null ? ((Number) request.get("targetId")).longValue() : null;
        String medicine = (String) request.get("medicine");
        String instruction = (String) request.get("instruction");
        String prescribedBy = (String) request.get("prescribedBy");

        log.info("Save medication: userId={}, targetId={}, medicine={}", userId, targetId, medicine);

        Map<String, Object> response = new HashMap<>();
        try {
            CareRecord record = careRecordService.saveMedication(userId, targetType, targetId,
                    medicine, instruction, prescribedBy);
            response.put("success", true);
            response.put("data", record);
        } catch (Exception e) {
            log.error("Save medication failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/records/compare")
    public ResponseEntity<Map<String, Object>> compareImages(@RequestBody Map<String, Object> request,
                                                             HttpSession session) {
        String userId = (String) session.getAttribute("user");
        String type = (String) request.get("type");

        log.info("Compare images: userId={}, type={}", userId, type);

        Map<String, Object> response = new HashMap<>();
        try {
            String result = careRecordService.compareImages(userId, type);
            response.put("success", true);
            response.put("content", result);
        } catch (Exception e) {
            log.error("Compare images failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/plan/generate")
    public ResponseEntity<Map<String, Object>> generateCarePlan(@RequestBody Map<String, Object> request,
                                                                HttpSession session) {
        String userId = (String) session.getAttribute("user");
        String targetType = (String) request.get("targetType");
        Long targetId = request.get("targetId") != null ? ((Number) request.get("targetId")).longValue() : null;
        String breed = (String) request.get("breed");
        String age = (String) request.get("age");
        String season = (String) request.get("season");
        String weather = (String) request.get("weather");

        log.info("Generate care plan: userId={}, targetId={}, breed={}", userId, targetId, breed);

        Map<String, Object> response = new HashMap<>();
        try {
            CareRecord record = careRecordService.generateCarePlan(userId, targetType, targetId,
                    breed, age, season, weather);
            response.put("success", true);
            response.put("data", record);
        } catch (Exception e) {
            log.error("Generate care plan failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/records/check")
    public ResponseEntity<Map<String, Object>> checkMedication(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        log.info("Check medication: userId={}", userId);

        Map<String, Object> response = new HashMap<>();
        try {
            String result = careRecordService.checkMedicationReminders(userId);
            response.put("success", true);
            response.put("content", result);
        } catch (Exception e) {
            log.error("Check medication failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
}
