package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.example.demo.chat.entity.sqlite.RagObservabilityAccessLog;
import com.example.demo.chat.entity.sqlite.RagRetrievalLog;
import com.example.demo.chat.repository.sqlite.RagObservabilityAccessLogRepository;
import com.example.demo.chat.repository.sqlite.RagRetrievalLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagObservabilityServiceTest {

    private RagRetrievalLogRepository retrievalLogRepository;
    private RagObservabilityAccessLogRepository accessLogRepository;

    @BeforeEach
    void setUp() {
        retrievalLogRepository = mock(RagRetrievalLogRepository.class);
        accessLogRepository = mock(RagObservabilityAccessLogRepository.class);
    }

    @Test
    void disabledSwitchDeniesWithoutReadingRetrievalLogs() {
        RagObservabilityService service = new RagObservabilityService(
                retrievalLogRepository, accessLogRepository, false, "alice");

        RagObservabilityService.LookupResult<?> result =
                service.findByTraceId("alice", "trace-1");

        assertEquals(403, result.status());
        assertEquals("功能未启用", result.message());
        verifyNoInteractions(retrievalLogRepository);
        RagObservabilityAccessLog audit = capturedAudit();
        assertEquals("DISABLED", audit.getReason());
        assertEquals("DENIED", audit.getDecision());
    }

    @Test
    void nonWhitelistedUserCannotReadRetrievalLogs() {
        RagObservabilityService service = new RagObservabilityService(
                retrievalLogRepository, accessLogRepository, true, " alice, bob ");

        RagObservabilityService.LookupResult<?> result =
                service.findRecentByConversation("charlie", "conversation-1", 20);

        assertEquals(403, result.status());
        assertEquals("无权访问", result.message());
        verifyNoInteractions(retrievalLogRepository);
        RagObservabilityAccessLog audit = capturedAudit();
        assertEquals("USER_NOT_ALLOWED", audit.getReason());
    }

    @Test
    void unauthenticatedRequestReturnsUnauthorizedAndIsAudited() {
        RagObservabilityService service = new RagObservabilityService(
                retrievalLogRepository, accessLogRepository, true, "alice");

        RagObservabilityService.LookupResult<?> result =
                service.findByTraceId(null, "trace-1");

        assertEquals(401, result.status());
        verifyNoInteractions(retrievalLogRepository);
        RagObservabilityAccessLog audit = capturedAudit();
        assertNull(audit.getUserId());
        assertEquals("UNAUTHENTICATED", audit.getReason());
    }

    @Test
    void whitelistedUserCanReadTraceWithoutSensitiveFields() {
        RagRetrievalLog stored = retrievalLog(
                "trace-1",
                "conversation-1",
                """
                [{"documentId":"doc-1","sourceId":"kb-guide","similarity":0.91,
                  "origin":"public_kb","content":"SECRET DOCUMENT BODY",
                  "query":"SECRET QUERY"}]
                """);
        when(retrievalLogRepository.findByTraceId("trace-1"))
                .thenReturn(Optional.of(stored));
        RagObservabilityService service = new RagObservabilityService(
                retrievalLogRepository, accessLogRepository, true, "alice");

        RagObservabilityService.LookupResult<RagObservabilityService.RagRetrievalObservation>
                result = service.findByTraceId("alice", "trace-1");

        assertEquals(200, result.status());
        assertEquals("trace-1", result.data().traceId());
        assertEquals("public_only", result.data().scope());
        assertEquals(1, result.data().results().size());
        assertEquals("doc-1", result.data().results().getFirst().documentId());

        String json = JSON.toJSONString(result.data());
        assertTrue(json.contains("doc-1"));
        assertTrue(json.contains("kb-guide"));
        assertFalse(json.contains("SECRET"));
        assertFalse(json.contains("queryHash"));
        assertFalse(json.contains("userId"));
        assertFalse(json.contains("conversationId"));

        RagObservabilityAccessLog audit = capturedAudit();
        assertEquals("ALLOWED", audit.getDecision());
        assertEquals(1, audit.getResultCount());
    }

    @Test
    void conversationLookupCapsLimitAndAuditsResultCount() {
        RagRetrievalLog first = retrievalLog("trace-2", "conversation-2", "[]");
        RagRetrievalLog second = retrievalLog("trace-3", "conversation-2", "[]");
        when(retrievalLogRepository.findByConversationIdOrderByCreatedAtDesc(
                eq("conversation-2"), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        RagObservabilityService service = new RagObservabilityService(
                retrievalLogRepository, accessLogRepository, true, "alice");

        RagObservabilityService.LookupResult<List<
                RagObservabilityService.RagRetrievalObservation>> result =
                service.findRecentByConversation("alice", "conversation-2", 500);

        assertEquals(200, result.status());
        assertEquals(2, result.data().size());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(retrievalLogRepository).findByConversationIdOrderByCreatedAtDesc(
                eq("conversation-2"), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
        RagObservabilityAccessLog audit = capturedAudit();
        assertEquals(2, audit.getResultCount());
        assertEquals("ALLOWED", audit.getDecision());
    }

    @Test
    void missingTraceIsAuditedAsNotFound() {
        when(retrievalLogRepository.findByTraceId("missing"))
                .thenReturn(Optional.empty());
        RagObservabilityService service = new RagObservabilityService(
                retrievalLogRepository, accessLogRepository, true, "alice");

        RagObservabilityService.LookupResult<?> result =
                service.findByTraceId("alice", "missing");

        assertEquals(404, result.status());
        RagObservabilityAccessLog audit = capturedAudit();
        assertEquals("NOT_FOUND", audit.getDecision());
        assertEquals("NOT_FOUND", audit.getReason());
    }

    @Test
    void invalidConversationIdIsRejectedBeforeRepositoryAccess() {
        RagObservabilityService service = new RagObservabilityService(
                retrievalLogRepository, accessLogRepository, true, "alice");

        RagObservabilityService.LookupResult<?> result =
                service.findRecentByConversation("alice", " ", 20);

        assertEquals(400, result.status());
        verifyNoInteractions(retrievalLogRepository);
        RagObservabilityAccessLog audit = capturedAudit();
        assertEquals("INVALID_CONVERSATION_ID", audit.getReason());
    }

    private RagRetrievalLog retrievalLog(String traceId, String conversationId,
                                         String resultsJson) {
        RagRetrievalLog log = new RagRetrievalLog();
        log.setTraceId(traceId);
        log.setUserId("sensitive-user");
        log.setConversationId(conversationId);
        log.setQueryHash("sensitive-query-hash");
        log.setScope("public_only");
        log.setTopK(5);
        log.setSimilarityThreshold(0.5);
        log.setRetrievedCount(1);
        log.setDurationMs(12L);
        log.setResultsJson(resultsJson);
        log.setCreatedAt(LocalDateTime.of(2026, 10, 8, 12, 0));
        return log;
    }

    private RagObservabilityAccessLog capturedAudit() {
        ArgumentCaptor<RagObservabilityAccessLog> captor =
                ArgumentCaptor.forClass(RagObservabilityAccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        return captor.getValue();
    }
}
