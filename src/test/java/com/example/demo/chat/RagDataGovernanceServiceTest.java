package com.example.demo.chat;

import com.example.demo.chat.repository.sqlite.RagObservabilityAccessLogRepository;
import com.example.demo.chat.repository.sqlite.RagRetrievalLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagDataGovernanceServiceTest {

    private VectorStoreService vectorStoreService;
    private RagRetrievalLogRepository retrievalLogRepository;
    private RagObservabilityAccessLogRepository observabilityAccessLogRepository;
    private RagDataGovernanceService service;

    @BeforeEach
    void setUp() {
        vectorStoreService = mock(VectorStoreService.class);
        retrievalLogRepository = mock(RagRetrievalLogRepository.class);
        observabilityAccessLogRepository = mock(RagObservabilityAccessLogRepository.class);
        service = new RagDataGovernanceService(
                vectorStoreService,
                retrievalLogRepository,
                observabilityAccessLogRepository
        );
        ReflectionTestUtils.setField(service, "conversationRetentionDays", 90L);
        ReflectionTestUtils.setField(service, "retrievalLogRetentionDays", 180L);
        ReflectionTestUtils.setField(service, "observabilityAccessLogRetentionDays", 180L);
    }

    @Test
    void usesConfiguredRetentionCutoffsForVectorsAndAuditLogs() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 4, 0);
        when(vectorStoreService.deleteExpiredConversationVectors(any(LocalDateTime.class))).thenReturn(4);
        when(retrievalLogRepository.deleteCreatedBefore(any(LocalDateTime.class))).thenReturn(5);
        when(observabilityAccessLogRepository.deleteCreatedBefore(any(LocalDateTime.class))).thenReturn(6);

        assertEquals(4, service.cleanupExpiredConversationVectors(now));
        assertEquals(11, service.cleanupExpiredAuditLogs(now));

        verify(vectorStoreService).deleteExpiredConversationVectors(now.minusDays(90));
        verify(retrievalLogRepository).deleteCreatedBefore(now.minusDays(180));
        verify(observabilityAccessLogRepository).deleteCreatedBefore(now.minusDays(180));
    }

    @Test
    void skipsCleanupWhenRetentionIsNotPositive() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 4, 0);
        ReflectionTestUtils.setField(service, "conversationRetentionDays", 0L);
        ReflectionTestUtils.setField(service, "retrievalLogRetentionDays", -1L);
        when(observabilityAccessLogRepository.deleteCreatedBefore(any(LocalDateTime.class))).thenReturn(2);

        assertEquals(0, service.cleanupExpiredConversationVectors(now));
        assertEquals(2, service.cleanupExpiredAuditLogs(now));

        verifyNoInteractions(vectorStoreService);
        verify(retrievalLogRepository, never()).deleteCreatedBefore(any(LocalDateTime.class));
        verify(observabilityAccessLogRepository).deleteCreatedBefore(now.minusDays(180));
    }
}
