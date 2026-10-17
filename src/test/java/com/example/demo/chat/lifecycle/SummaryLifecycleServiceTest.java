package com.example.demo.chat.lifecycle;

import com.example.demo.chat.LlmService;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummaryLifecycleServiceTest {

    private SummaryStateStore stateStore;
    private SummaryCompactionWriter compactionWriter;
    private MessageRepository messageRepository;
    private LlmService llmService;
    private SummaryLifecycleService service;

    @BeforeEach
    void setUp() {
        stateStore = mock(SummaryStateStore.class);
        compactionWriter = mock(SummaryCompactionWriter.class);
        messageRepository = mock(MessageRepository.class);
        llmService = mock(LlmService.class);
        service = new SummaryLifecycleService(
                stateStore, compactionWriter, messageRepository, llmService);
        ReflectionTestUtils.setField(service, "summaryKeepRecent", 2);
        ReflectionTestUtils.setField(service, "maxMessagesPerRun", 200);
        ReflectionTestUtils.setField(service, "lockTtlSeconds", 600);
        ReflectionTestUtils.setField(service, "maxAttempts", 10);
        ReflectionTestUtils.setField(service, "retryBaseDelaySeconds", 30);
        ReflectionTestUtils.setField(service, "retryMaxDelaySeconds", 21600);
    }

    @Test
    void summarizesOnlyOldMessagesAndIncludesPreviousSummary() throws Exception {
        List<Message> active = messages(
                new Entry(1, "user"), new Entry(2, "assistant"),
                new Entry(3, "user"), new Entry(4, "assistant"),
                new Entry(5, "user"), new Entry(6, "assistant"));
        when(stateStore.coveredUntilId("c1")).thenReturn(0L);
        when(messageRepository.findActiveAfter(eq("c1"), eq(0L), any(PageRequest.class)))
                .thenReturn(active);
        SummaryClaim claim = new SummaryClaim("c1", "旧事实：用户养了一只猫", 0L, 1L, 1);
        when(stateStore.claim(eq("c1"), any(LocalDateTime.class), any()))
                .thenReturn(claim);
        when(llmService.chat(anyString(), anyString())).thenReturn("合并后的摘要");

        service.summarize("user-a", "c1");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmService).chat(prompt.capture(), anyString());
        assertTrue(prompt.getValue().contains("旧摘要"));
        assertTrue(prompt.getValue().contains("旧事实：用户养了一只猫"));
        assertTrue(prompt.getValue().contains("用户: q1"));
        assertTrue(prompt.getValue().contains("助手: a2"));
        verify(compactionWriter).apply(eq(claim), eq("合并后的摘要"), eq(2L), eq(2), any(LocalDateTime.class));
    }

    @Test
    void skipsWhenActiveMessagesDoNotExceedTheRecentWindow() throws Exception {
        List<Message> active = messages(
                new Entry(1, "user"), new Entry(2, "assistant"),
                new Entry(3, "user"), new Entry(4, "assistant"));
        when(stateStore.coveredUntilId("c1")).thenReturn(0L);
        when(messageRepository.findActiveAfter(eq("c1"), eq(0L), any(PageRequest.class)))
                .thenReturn(active);

        service.summarize("user-a", "c1");

        verify(llmService, never()).chat(anyString(), anyString());
        verify(stateStore, never()).claim(anyString(), any(LocalDateTime.class), any());
    }

    @Test
    void doesNotClaimWhenTheCompactWindowHasNoAssistant() throws Exception {
        List<Message> active = messages(
                new Entry(1, "user"), new Entry(2, "assistant"),
                new Entry(3, "user"), new Entry(4, "assistant"),
                new Entry(5, "user"));
        when(stateStore.coveredUntilId("c1")).thenReturn(0L);
        when(messageRepository.findActiveAfter(eq("c1"), eq(0L), any(PageRequest.class)))
                .thenReturn(active);

        service.summarize("user-a", "c1");

        verify(stateStore, never()).claim(anyString(), any(LocalDateTime.class), any());
        verify(llmService, never()).chat(anyString(), anyString());
    }

    @Test
    void marksFailureWithBackoffWhenLlmFails() throws Exception {
        List<Message> active = messages(
                new Entry(1, "user"), new Entry(2, "assistant"),
                new Entry(3, "user"), new Entry(4, "assistant"),
                new Entry(5, "user"), new Entry(6, "assistant"));
        when(stateStore.coveredUntilId("c1")).thenReturn(0L);
        when(messageRepository.findActiveAfter(eq("c1"), eq(0L), any(PageRequest.class)))
                .thenReturn(active);
        SummaryClaim claim = new SummaryClaim("c1", "old", 0L, 1L, 1);
        when(stateStore.claim(eq("c1"), any(LocalDateTime.class), any()))
                .thenReturn(claim);
        when(llmService.chat(anyString(), anyString()))
                .thenThrow(new IOException("llm unavailable"));

        service.summarize("user-a", "c1");

        verify(stateStore).markFailed(eq(claim), anyString(), any(LocalDateTime.class),
                any(LocalDateTime.class));
        verify(compactionWriter, never()).apply(any(), anyString(), anyLong(), anyInt(), any());
    }

    @Test
    void ignoresApplyConflictsWithoutMarkingFailure() throws Exception {
        List<Message> active = messages(
                new Entry(1, "user"), new Entry(2, "assistant"),
                new Entry(3, "user"), new Entry(4, "assistant"),
                new Entry(5, "user"), new Entry(6, "assistant"));
        when(stateStore.coveredUntilId("c1")).thenReturn(0L);
        when(messageRepository.findActiveAfter(eq("c1"), eq(0L), any(PageRequest.class)))
                .thenReturn(active);
        SummaryClaim claim = new SummaryClaim("c1", "old", 0L, 1L, 1);
        when(stateStore.claim(eq("c1"), any(LocalDateTime.class), any()))
                .thenReturn(claim);
        when(llmService.chat(anyString(), anyString())).thenReturn("summary");
        org.mockito.Mockito.doThrow(new SummaryApplyException(
                        "c1", SummaryApplyException.Reason.CONFLICT))
                .when(compactionWriter).apply(any(), anyString(), anyLong(),
                        anyInt(), any(LocalDateTime.class));

        service.summarize("user-a", "c1");

        verify(stateStore, never()).markFailed(any(), anyString(), any(), any());
    }

    private List<Message> messages(Entry... entries) {
        List<Message> messages = new ArrayList<>();
        for (Entry entry : entries) {
            String content = "user".equals(entry.role()) ? "q" + entry.id() : "a" + entry.id();
            Message message = new Message("c1", entry.role(), content, LocalDateTime.now(), null);
            message.setId((long) entry.id());
            messages.add(message);
        }
        return messages;
    }

    private record Entry(int id, String role) {
    }
}
