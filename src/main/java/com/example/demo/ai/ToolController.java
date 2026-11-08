package com.example.demo.ai;

import com.example.demo.web.ApiResponses;

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
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ToolController {

    private final SpringAiChatService springAiChatService;
    private final ToolCallingService toolCallingService;

    @PostMapping("/chat-with-tools")
    public ResponseEntity<Map<String, Object>> chatWithTools(@RequestBody Map<String, Object> request,
                                                             jakarta.servlet.http.HttpServletRequest servletRequest) {
        String message = (String) request.get("message");
        String systemPrompt = (String) request.get("systemPrompt");

        @SuppressWarnings("unchecked")
        List<String> allowedTools = (List<String>) request.get("allowedTools");

        log.info("AI chat with tools request: message={}, allowedTools={}",
                com.example.demo.core.LogSanitizer.describe(message), allowedTools);

        Map<String, Object> response = new HashMap<>();
        String userId = (String) servletRequest.getAttribute("userName");
        try {
            List<String> validatedTools = toolCallingService.validateToolNames(allowedTools);
            Set<String> allowedToolSet = validatedTools.isEmpty() ? null : new HashSet<>(validatedTools);

            UserContextHolder.setUserId(userId);
            ToolCallResponse toolResponse = springAiChatService.chatWithTools(userId, message, systemPrompt, allowedToolSet);

            response.put("success", true);
            response.put("content", toolResponse.getText());
            response.put("traceId", toolResponse.getTraceId());
            response.put("totalIterations", toolResponse.getTotalIterations());
            response.put("totalTokens", toolResponse.getTotalTokens());

            if (toolResponse.hasToolCalls()) {
                List<Map<String, Object>> historyList = toolResponse.getToolCallHistory().stream()
                        .map(this::convertToolCallResult)
                        .collect(Collectors.toList());
                response.put("toolCallHistory", historyList);
            }

            if (toolResponse.hasGeneratedFiles()) {
                response.put("generatedFiles", toolResponse.getGeneratedFiles());
            }

            if (toolResponse.getToolCallHistory() != null) {
                long successCount = toolResponse.getToolCallHistory().stream()
                        .filter(ToolCallResult::isSuccess)
                        .count();
                response.put("toolsUsed", toolResponse.getToolCallHistory().size());
                response.put("toolsSucceeded", successCount);
            }

        } catch (Exception e) {
            log.error("AI chat with tools failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        } finally {
            UserContextHolder.clear();
        }
        return ResponseEntity.ok(ApiResponses.enrich(response));
    }

    @GetMapping("/tools/registered")
    public ResponseEntity<Map<String, Object>> getRegisteredTools() {
        Map<String, Object> response = new HashMap<>();
        Set<String> tools = toolCallingService.getRegisteredToolNames();
        response.put("success", true);
        response.put("tools", new ArrayList<>(tools));
        response.put("count", tools.size());
        return ResponseEntity.ok(ApiResponses.enrich(response));
    }

    private Map<String, Object> convertToolCallResult(ToolCallResult result) {
        Map<String, Object> map = new HashMap<>();
        map.put("toolName", result.getToolName());
        map.put("success", result.isSuccess());
        map.put("durationMs", result.getDurationMs());
        map.put("timestamp", result.getTimestamp());
        if (result.getArguments() != null) {
            map.put("arguments", result.getArguments());
        }
        if (result.getResult() != null) {
            map.put("result", result.getResult().length() > 200
                    ? result.getResult().substring(0, 200) + "..."
                    : result.getResult());
        }
        if (result.getErrorMessage() != null) {
            map.put("errorMessage", result.getErrorMessage());
        }
        return map;
    }
}
