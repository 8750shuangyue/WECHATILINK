package com.example.demo.ai;

import com.example.demo.care.model.CareRecord;
import com.example.demo.config.MdcContext;
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
public class ChatController {

    private final SpringAiChatService springAiChatService;
    private final LlmService llmService;
    private final ChatMemoryService chatMemoryService;

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request, HttpSession session) {
        String message = request.get("message");
        String systemPrompt = request.get("systemPrompt");
        String userId = (String) session.getAttribute("user");
        
        log.info("AI chat request: message={}", com.example.demo.core.LogSanitizer.describe(message));
        
        Map<String, Object> response = new HashMap<>();
        try {
            String result = springAiChatService.chat(userId, message, systemPrompt, null);
            response.put("success", true);
            response.put("content", result);
        } catch (Exception e) {
            log.error("AI chat failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody Map<String, String> request, HttpSession session) {
        String message = request.get("message");
        String systemPrompt = request.get("systemPrompt");
        boolean includeCitations = Boolean.parseBoolean(request.get("includeCitations"));
        boolean includeSources = Boolean.parseBoolean(request.get("includeSources")) || includeCitations;
        boolean includeTraceStats = Boolean.parseBoolean(request.get("includeTraceStats"));
        String conversationId = getOrCreateConversationId(session);
        String userId = (String) session.getAttribute("user");
        SseEmitter emitter = new SseEmitter(120000L);
        CompletableFuture.runAsync(MdcContext.wrap(() -> {
            try {
                java.util.concurrent.atomic.AtomicReference<String> traceRef =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Consumer<String> onTrace = traceId -> {
                    traceRef.set(traceId);
                    try {
                        emitter.send(SseEmitter.event()
                                .name("rag-trace")
                                .data(Map.of("traceId", traceId)));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                };
                Consumer<RetrievalTraceStats> onStats = stats -> {
                    if (!includeTraceStats || stats == null) {
                        return;
                    }
                    try {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("traceId", traceRef.get() == null ? "" : traceRef.get());
                        payload.put("stats", stats.toJson());
                        emitter.send(SseEmitter.event()
                                .name("rag-stats")
                                .data(payload));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                };
                Consumer<List<RagContextService.SourceRef>> onSources = sources -> {
                    if (!includeSources) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event()
                                .name("rag-sources")
                                .data(Map.of("sources", sources)));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                };
                Consumer<String> onToken = token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                };
                if (includeTraceStats) {
                    llmService.chatStream(userId, conversationId, message, systemPrompt,
                            onTrace, onSources, onStats, onToken, emitter::complete,
                            includeCitations);
                } else if (includeCitations) {
                    llmService.chatStream(userId, conversationId, message, systemPrompt,
                            onTrace, onSources, onToken, emitter::complete, true);
                } else {
                    llmService.chatStream(userId, conversationId, message, systemPrompt,
                            onTrace, onSources, onToken, emitter::complete);
                }
            } catch (Exception e) {
                log.error("AI chat stream failed", e);
                emitter.completeWithError(e);
            }
        }));
        return emitter;
    }

    /**
     * 历史回放：conversationId 只从 Session 取，客户端不能指定。
     * 只返回 user/assistant 消息，system 摘要不展示。
     */
    @GetMapping("/chat/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            HttpSession session) {
        String userId = (String) session.getAttribute("user");
        Map<String, Object> response = new HashMap<>();
        if (userId == null || userId.isBlank()) {
            response.put("success", false);
            response.put("error", "请先登录");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore())
                    .body(response);
        }

        String conversationId = (String) session.getAttribute("conversationId");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        var page = chatMemoryService.getHistory(userId, conversationId, beforeId, safeLimit);
        response.put("success", true);
        response.put("conversationId", page.conversationId());
        response.put("messages", page.messages());
        response.put("hasMore", page.hasMore());
        response.put("nextBeforeId", page.nextBeforeId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private String getOrCreateConversationId(HttpSession session) {
        String conversationId = (String) session.getAttribute("conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = "web_" + System.currentTimeMillis() + "_" + System.nanoTime();
            session.setAttribute("conversationId", conversationId);
        }
        return conversationId;
    }

    @PostMapping("/chat/clear")
    public ResponseEntity<Map<String, Object>> clearConversation(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        Map<String, Object> response = new HashMap<>();
        if (userId == null || userId.isBlank()) {
            response.put("success", false);
            response.put("error", "请先登录");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }

        String conversationId = (String) session.getAttribute("conversationId");
        try {
            if (conversationId == null || conversationId.isBlank()) {
                chatMemoryService.clearUserSession(userId);
                response.put("cleared", false);
            } else {
                chatMemoryService.clearConversation(userId, conversationId);
                response.put("cleared", true);
            }
            session.removeAttribute("conversationId");
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (ConversationAccessDeniedException e) {
            log.warn("Rejected clear request for unauthorized conversation, userId: {}, conversationId: {}",
                    userId, conversationId);
            response.put("success", false);
            response.put("error", "无权清空该会话");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("error", "清空会话参数无效");
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("Failed to clear conversation, userId: {}, conversationId: {}",
                    userId, conversationId, e);
            response.put("success", false);
            response.put("error", "清空会话失败，请重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
