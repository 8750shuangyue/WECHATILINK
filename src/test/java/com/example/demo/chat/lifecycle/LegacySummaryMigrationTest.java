package com.example.demo.chat.lifecycle;

import com.example.demo.chat.entity.ConversationSummary;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.entity.SummaryStatus;
import com.example.demo.chat.repository.mysql.ConversationSummaryRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacySummaryMigrationTest {

    private MessageRepository messageRepository;
    private ConversationSummaryRepository summaryRepository;
    private LegacySummaryMigration migration;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        summaryRepository = mock(ConversationSummaryRepository.class);
        migration = new LegacySummaryMigration(messageRepository, summaryRepository);
        ReflectionTestUtils.setField(migration, "enabled", true);
    }

    @Test
    void migratesLatestLegacySummaryAndKeepsMirrorRow() {
        Message latest = summaryMessage(20L, "c1",
                "【对话摘要】[更新时间: 2026-10-17T10:00]\n用户养了一只猫");
        Message older = summaryMessage(10L, "c1", "【对话摘要】旧摘要");
        when(messageRepository.findSummaryMessages()).thenReturn(List.of(latest, older));
        when(summaryRepository.existsById("c1")).thenReturn(false);

        migration.migrate();

        ArgumentCaptor<ConversationSummary> captor =
                ArgumentCaptor.forClass(ConversationSummary.class);
        verify(summaryRepository).save(captor.capture());
        ConversationSummary saved = captor.getValue();
        assertEquals("c1", saved.getConversationId());
        assertEquals("用户养了一只猫", saved.getSummaryText());
        assertEquals(0L, saved.getCoveredUntilId());
        assertEquals(SummaryStatus.READY, saved.getStatus());
        // 旧 system 镜像行保留，不删除。
        verify(messageRepository, never()).deleteById(anyLong());
    }

    @Test
    void doesNotOverwriteExistingSummaryRow() {
        when(messageRepository.findSummaryMessages()).thenReturn(
                List.of(summaryMessage(20L, "c1", "【对话摘要】新内容")));
        when(summaryRepository.existsById("c1")).thenReturn(true);

        migration.migrate();

        verify(summaryRepository, never()).save(any(ConversationSummary.class));
    }

    @Test
    void doesNothingWhenLifecycleDisabled() {
        ReflectionTestUtils.setField(migration, "enabled", false);

        migration.migrate();

        verifyNoInteractions(messageRepository, summaryRepository);
    }

    private Message summaryMessage(long id, String conversationId, String content) {
        Message message = new Message(conversationId, "system", content, LocalDateTime.now(), null);
        message.setId(id);
        return message;
    }
}
