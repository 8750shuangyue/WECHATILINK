package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.entity.sqlite.RagObservabilityAccessLog;
import com.example.demo.chat.entity.sqlite.RagRetrievalLog;
import com.example.demo.chat.repository.sqlite.RagObservabilityAccessLogRepository;
import com.example.demo.chat.repository.sqlite.RagRetrievalLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RagObservabilityService {

    private static final Logger logger = LoggerFactory.getLogger(RagObservabilityService.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private static final String ACTION_TRACE = "TRACE_LOOKUP";
    private static final String ACTION_CONVERSATION = "CONVERSATION_LOOKUP";
    private static final String DECISION_ALLOWED = "ALLOWED";
    private static final String DECISION_DENIED = "DENIED";
    private static final String DECISION_NOT_FOUND = "NOT_FOUND";

    private final RagRetrievalLogRepository retrievalLogRepository;
    private final RagObservabilityAccessLogRepository accessLogRepository;
    private final boolean enabled;
    private final Set<String> allowedUsers;

    public RagObservabilityService(
            RagRetrievalLogRepository retrievalLogRepository,
            RagObservabilityAccessLogRepository accessLogRepository,
            @Value("${app.rag-observability.enabled:false}") boolean enabled,
            @Value("${app.rag-observability.allowed-users:}") String allowedUsers) {
        this.retrievalLogRepository = retrievalLogRepository;
        this.accessLogRepository = accessLogRepository;
        this.enabled = enabled;
        this.allowedUsers = parseAllowedUsers(allowedUsers);
    }

    public LookupResult<RagRetrievalObservation> findByTraceId(String actor, String traceId) {
        if (actor == null || actor.isBlank()) {
            audit(null, ACTION_TRACE, DECISION_DENIED, "UNAUTHENTICATED", traceId, null, 0);
            return LookupResult.error(401, "未登录");
        }
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            audit(actor, ACTION_TRACE, DECISION_DENIED, "INVALID_TRACE_ID", traceId, null, 0);
            return LookupResult.error(400, "traceId 无效");
        }
        LookupResult<RagRetrievalObservation> denied = authorize(
                actor, ACTION_TRACE, traceId, null);
        if (denied != null) {
            return denied;
        }

        Optional<RagRetrievalLog> record = retrievalLogRepository.findByTraceId(traceId);
        if (record.isEmpty()) {
            audit(actor, ACTION_TRACE, DECISION_NOT_FOUND, "NOT_FOUND", traceId, null, 0);
            return LookupResult.error(404, "未找到检索记录");
        }

        RagRetrievalObservation observation = toObservation(record.get());
        audit(actor, ACTION_TRACE, DECISION_ALLOWED, "ALLOWED", traceId, null, 1);
        return LookupResult.success(observation);
    }

    public LookupResult<List<RagRetrievalObservation>> findRecentByConversation(
            String actor, String conversationId, int limit) {
        if (actor == null || actor.isBlank()) {
            audit(null, ACTION_CONVERSATION, DECISION_DENIED, "UNAUTHENTICATED",
                    null, conversationId, 0);
            return LookupResult.error(401, "未登录");
        }
        if (conversationId == null || conversationId.isBlank() || conversationId.length() > 255) {
            audit(actor, ACTION_CONVERSATION, DECISION_DENIED, "INVALID_CONVERSATION_ID",
                    null, conversationId, 0);
            return LookupResult.error(400, "conversationId 无效");
        }
        LookupResult<List<RagRetrievalObservation>> denied = authorize(
                actor, ACTION_CONVERSATION, null, conversationId);
        if (denied != null) {
            return denied;
        }

        int normalizedLimit = normalizeLimit(limit);
        Pageable pageable = PageRequest.of(0, normalizedLimit);
        List<RagRetrievalLog> records =
                retrievalLogRepository.findByConversationIdOrderByCreatedAtDesc(
                        conversationId, pageable);
        List<RagRetrievalObservation> observations = new ArrayList<>(records.size());
        for (RagRetrievalLog record : records) {
            observations.add(toObservation(record));
        }

        audit(actor, ACTION_CONVERSATION, DECISION_ALLOWED, "ALLOWED",
                null, conversationId, observations.size());
        return LookupResult.success(observations);
    }

    private <T> LookupResult<T> authorize(String actor, String action,
                                          String traceId, String conversationId) {
        if (!enabled) {
            audit(actor, action, DECISION_DENIED, "DISABLED", traceId, conversationId, 0);
            return LookupResult.error(403, "功能未启用");
        }
        if (!allowedUsers.contains(actor)) {
            audit(actor, action, DECISION_DENIED, "USER_NOT_ALLOWED",
                    traceId, conversationId, 0);
            return LookupResult.error(403, "无权访问");
        }
        return null;
    }

    private RagRetrievalObservation toObservation(RagRetrievalLog record) {
        return new RagRetrievalObservation(
                record.getTraceId(),
                record.getScope(),
                record.getTopK(),
                record.getSimilarityThreshold(),
                record.getRetrievedCount(),
                record.getDurationMs(),
                record.getCreatedAt(),
                parseResults(record.getResultsJson())
        );
    }

    private List<RagRetrievalResult> parseResults(String resultsJson) {
        if (resultsJson == null || resultsJson.isBlank()) {
            return List.of();
        }
        try {
            JSONArray array = JSON.parseArray(resultsJson);
            List<RagRetrievalResult> results = new ArrayList<>(array.size());
            for (Object value : array) {
                if (!(value instanceof JSONObject item)) {
                    continue;
                }
                results.add(new RagRetrievalResult(
                        item.getString("documentId"),
                        item.getString("sourceId"),
                        item.getDouble("similarity"),
                        item.getString("origin")
                ));
            }
            return results;
        } catch (Exception e) {
            logger.warn("[RAG] Failed to parse retrieval observation results: {}",
                    e.getMessage());
            return List.of();
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private Set<String> parseAllowedUsers(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void audit(String userId, String action, String decision, String reason,
                       String traceId, String conversationId, int resultCount) {
        try {
            RagObservabilityAccessLog auditLog = new RagObservabilityAccessLog();
            auditLog.setUserId(userId);
            auditLog.setAction(action);
            auditLog.setDecision(decision);
            auditLog.setReason(reason);
            auditLog.setTargetTraceId(traceId);
            auditLog.setConversationId(conversationId);
            auditLog.setResultCount(resultCount);
            accessLogRepository.save(auditLog);
        } catch (Exception e) {
            logger.warn("[RAG] Failed to persist observability access audit: {}",
                    e.getMessage());
        }
    }

    public record LookupResult<T>(int status, String message, T data) {
        public static <T> LookupResult<T> success(T data) {
            return new LookupResult<>(200, "操作成功", data);
        }

        public static <T> LookupResult<T> error(int status, String message) {
            return new LookupResult<>(status, message, null);
        }
    }

    public record RagRetrievalObservation(
            String traceId,
            String scope,
            Integer topK,
            Double similarityThreshold,
            Integer retrievedCount,
            Long durationMs,
            LocalDateTime createdAt,
            List<RagRetrievalResult> results) {
    }

    public record RagRetrievalResult(
            String documentId,
            String sourceId,
            Double similarity,
            String origin) {
    }
}
