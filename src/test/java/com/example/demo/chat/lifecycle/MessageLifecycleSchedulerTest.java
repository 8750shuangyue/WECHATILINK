package com.example.demo.chat.lifecycle;

import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.entity.Conversation;
import com.example.demo.chat.repository.mysql.ConversationRepository;
import com.example.demo.chat.repository.mysql.ConversationSummaryRepository;
import com.example.demo.chat.repository.mysql.MessageArchiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageLifecycleSchedulerTest {

    private ConversationSummaryRepository summaryRepository;
    private MessageArchiveRepository archiveRepository;
    private ConversationRepository conversationRepository;
    private ChatMemoryService chatMemoryService;
    private MessageLifecycleScheduler scheduler;

    @BeforeEach
    void setUp() {
        summaryRepository = mock(ConversationSummaryRepository.class);
        archiveRepository = mock(MessageArchiveRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        chatMemoryService = mock(ChatMemoryService.class);
        Executor directExecutor = Runnable::run;
        scheduler = new MessageLifecycleScheduler(
                summaryRepository, archiveRepository, conversationRepository,
                chatMemoryService, directExecutor);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "summaryKeepRecent", 5);
        ReflectionTestUtils.setField(scheduler, "summaryLockTtlSeconds", 600L);
        ReflectionTestUtils.setField(scheduler, "retryLimit", 50);
        ReflectionTestUtils.setField(scheduler, "archiveRetentionDays", 180L);
        ReflectionTestUtils.setField(scheduler, "archiveBatchSize", 1000);
        ReflectionTestUtils.setField(scheduler, "archiveMaxBatches", 10);
    }

    @Test
    void disabledLifecycleDoesNotTouchAnything() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.retrySummaries();
        scheduler.cleanupArchive();

        verifyNoInteractions(summaryRepository, archiveRepository,
                conversationRepository, chatMemoryService);
    }

    @Test
    void cleanupUsesRetentionCutoffAndStopsAfterShortBatch() {
        when(archiveRepository.deleteExpired(any(LocalDateTime.class), eq(1000))).thenReturn(0);

        scheduler.cleanupArchive();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(archiveRepository).deleteExpired(cutoff.capture(), eq(1000));
        assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().minusDays(179)));
        verify(archiveRepository, times(1)).deleteExpired(any(LocalDateTime.class), anyInt());
    }

    @Test
    void retrySubmitsDueAndReconciliationConversationsOnce() {
        when(summaryRepository.markStalePendingFailed(any(), any(), any())).thenReturn(1);
        when(summaryRepository.findDueRetries(any(LocalDateTime.class), eq(50)))
                .thenReturn(List.of("c1"));
        when(summaryRepository.findReconciliationCandidates(eq(10), any(LocalDateTime.class), eq(50)))
                .thenReturn(List.of("c1", "c2"));
        when(conversationRepository.findById("c1"))
                .thenReturn(Optional.of(new Conversation("c1", "user-a")));
        when(conversationRepository.findById("c2"))
                .thenReturn(Optional.of(new Conversation("c2", "user-b")));

        scheduler.retrySummaries();

        verify(chatMemoryService, times(1)).checkAndUpdateSummary("user-a", "c1");
        verify(chatMemoryService, times(1)).checkAndUpdateSummary("user-b", "c2");
    }
}
