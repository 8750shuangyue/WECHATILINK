package com.example.demo.chat;

import com.example.demo.aicare.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 受控只读的 RAG 召回观测接口。
 * 默认关闭，并同时受登录态和用户白名单约束。
 */
@RestController
@RequestMapping("/api/internal/rag/retrievals")
public class RagObservabilityController {

    private final RagObservabilityService observabilityService;

    public RagObservabilityController(RagObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @GetMapping("/{traceId}")
    public ResponseEntity<Result<RagObservabilityService.RagRetrievalObservation>> getByTraceId(
            @PathVariable String traceId, HttpServletRequest request) {
        return toResponse(observabilityService.findByTraceId(actor(request), traceId));
    }

    @GetMapping
    public ResponseEntity<Result<List<RagObservabilityService.RagRetrievalObservation>>> getRecent(
            @RequestParam(required = false) String conversationId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        return toResponse(observabilityService.findRecentByConversation(
                actor(request), conversationId, limit));
    }

    private String actor(HttpServletRequest request) {
        Object value = request.getAttribute("userName");
        return value instanceof String userName ? userName : null;
    }

    private <T> ResponseEntity<Result<T>> toResponse(
            RagObservabilityService.LookupResult<T> result) {
        if (result.status() == 200) {
            return ResponseEntity.ok(Result.success(result.data()));
        }
        return ResponseEntity.status(result.status())
                .body(Result.error(result.status(), result.message()));
    }
}
