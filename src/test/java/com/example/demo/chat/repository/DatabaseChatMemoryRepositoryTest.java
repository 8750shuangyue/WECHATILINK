package com.example.demo.chat.repository;

import com.example.demo.chat.ChatHistoryEntry;
import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.HistoryPage;
import com.example.demo.chat.entity.Conversation;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.entity.MessageHistoryRow;
import com.example.demo.chat.exception.ConversationAccessDeniedException;
import com.example.demo.chat.repository.mysql.ConversationRepository;
import com.example.demo.chat.repository.mysql.ConversationSummaryRepository;
import com.example.demo.chat.repository.mysql.MessageArchiveRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseChatMemoryRepositoryTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private DatabaseChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        repository = new DatabaseChatMemoryRepository(conversationRepository, messageRepository);
    }

    @Test
    void createsConversationWithCurrentUserAndStoresMessage() {
        when(conversationRepository.findById("conversation-a")).thenReturn(Optional.empty());

        repository.addMessage("user-a", "conversation-a", new ChatMessage("user", "hello"));

        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(conversationCaptor.capture());
        assertEquals("conversation-a", conversationCaptor.getValue().getConversationId());
        assertEquals("user-a", conversationCaptor.getValue().getUserId());

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertEquals("conversation-a", messageCaptor.getValue().getConversationId());
        assertEquals("hello", messageCaptor.getValue().getContent());
    }

    @Test
    void storesSourcesJsonOnTheMessageEntity() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));
        String sourcesJson = "{\"v\":1,\"traceId\":\"trace-1\",\"sources\":[]}";

        repository.addMessage("user-a", "conversation-a",
                new ChatMessage("assistant", "answer[1]"), sourcesJson);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertEquals(sourcesJson, messageCaptor.getValue().getSourcesJson());
    }

    @Test
    void leavesSourcesJsonNullForLegacyAddMessage() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));

        repository.addMessage("user-a", "conversation-a", new ChatMessage("user", "hello"));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertNull(messageCaptor.getValue().getSourcesJson());
    }

    @Test
    void doesNotReadAnotherUsersConversation() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));

        assertTrue(repository.getMessages("user-b", "conversation-a").isEmpty());

        verifyNoInteractions(messageRepository);
    }

    @Test
    void rejectsAllMutationsForAnotherUsersConversation() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));

        assertThrows(ConversationAccessDeniedException.class,
                () -> repository.addMessage("user-b", "conversation-a", new ChatMessage("user", "hello")));
        assertThrows(ConversationAccessDeniedException.class,
                () -> repository.saveMessages("user-b", "conversation-a", List.of(new ChatMessage("user", "hello"))));
        assertThrows(ConversationAccessDeniedException.class,
                () -> repository.clear("user-b", "conversation-a"));
        assertThrows(ConversationAccessDeniedException.class,
                () -> repository.removeSystemMessages("user-b", "conversation-a", "summary"));

        verifyNoInteractions(messageRepository);
    }

    @Test
    void keepsLegacyUnownedConversationInaccessible() {
        Conversation legacyConversation = new Conversation("legacy-conversation", null);
        when(conversationRepository.findById("legacy-conversation"))
                .thenReturn(Optional.of(legacyConversation));

        assertTrue(repository.getMessages("user-a", "legacy-conversation").isEmpty());
        assertFalse(repository.exists("user-a", "legacy-conversation"));
        assertThrows(ConversationAccessDeniedException.class,
                () -> repository.addMessage("user-a", "legacy-conversation", new ChatMessage("user", "hello")));

        verifyNoInteractions(messageRepository);
    }

    @Test
    void allowsOwnerToReadClearAndRemoveSystemMessages() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));
        when(messageRepository.findByConversationIdOrderByTimestampAsc("conversation-a"))
                .thenReturn(List.of());

        assertTrue(repository.exists("user-a", "conversation-a"));
        assertTrue(repository.getMessages("user-a", "conversation-a").isEmpty());

        repository.clear("user-a", "conversation-a");
        repository.removeSystemMessages("user-a", "conversation-a", "summary");

        verify(messageRepository).deleteByConversationId("conversation-a");
        verify(messageRepository).deleteByConversationIdAndRoleAndContentStartingWith(
                "conversation-a", "system", "summary");
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void returnsHistoryPageWithSourcesInAscendingOrder() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));
        String sourcesJson = "{\"v\":1,\"traceId\":\"trace-1\",\"sources\":["
                + "{\"sourceId\":\"kb_a\",\"title\":\"标题 A\",\"rank\":1}]}";
        Message oldest = message(1L, "user", "q1", null);
        Message middle = message(2L, "assistant", "a1", null);
        Message newest = message(3L, "assistant", "a2[1]", sourcesJson);
        when(messageRepository.findByConversationIdAndRoleInOrderByIdDesc(
                eq("conversation-a"), eq(List.of("user", "assistant")), any(Pageable.class)))
                .thenReturn(List.of(newest, middle, oldest));

        HistoryPage page = repository.getHistory("user-a", "conversation-a", null, 2);

        assertTrue(page.hasMore());
        assertEquals(2L, page.nextBeforeId());
        assertEquals(2, page.messages().size());
        assertEquals(2L, page.messages().get(0).id());
        assertEquals(3L, page.messages().get(1).id());
        assertEquals("kb_a", page.messages().get(1).sources().get(0).sourceId());
        assertEquals(1, page.messages().get(1).sources().get(0).rank());
    }

    @Test
    void appliesBeforeIdCursorAndToleratesBrokenSourcesJson() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));
        Message older = message(1L, "user", "q1", null);
        Message newer = message(2L, "assistant", "a1", "{broken");
        when(messageRepository.findByConversationIdAndRoleInAndIdLessThanOrderByIdDesc(
                eq("conversation-a"), eq(List.of("user", "assistant")), eq(3L),
                any(Pageable.class)))
                .thenReturn(List.of(newer, older));

        HistoryPage page = repository.getHistory("user-a", "conversation-a", 3L, 50);

        assertFalse(page.hasMore());
        assertNull(page.nextBeforeId());
        assertEquals(2, page.messages().size());
        assertEquals(1L, page.messages().get(0).id());
        assertTrue(page.messages().get(1).sources().isEmpty());
    }

    @Test
    void returnsEmptyHistoryForAnotherUserWithoutLeakingExistence() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));

        HistoryPage page = repository.getHistory("user-b", "conversation-a", null, 50);

        assertTrue(page.messages().isEmpty());
        assertFalse(page.hasMore());
        assertNull(page.nextBeforeId());
        verifyNoInteractions(messageRepository);
    }

    private Message message(Long id, String role, String content, String sourcesJson) {
        Message message = new Message(
                "conversation-a", role, content, LocalDateTime.now(), sourcesJson);
        message.setId(id);
        return message;
    }

    @Test
    void getMessagesUsesIdOrder() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));
        Message message = new Message("conversation-a", "user", "hello",
                LocalDateTime.now(), null);
        message.setId(1L);
        when(messageRepository.findByConversationIdOrderByIdAsc("conversation-a"))
                .thenReturn(List.of(message));

        var messages = repository.getMessages("user-a", "conversation-a");

        assertEquals(1, messages.size());
        verify(messageRepository).findByConversationIdOrderByIdAsc("conversation-a");
    }

    @Test
    void mergedHistoryReadsAcrossActiveAndArchive() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));
        String sourcesJson = "{\"v\":1,\"traceId\":\"t\",\"sources\":["
                + "{\"sourceId\":\"kb_a\",\"title\":\"A\",\"rank\":1}]}";
        MessageHistoryRow newest = historyRow(5L, "assistant", "a5", sourcesJson);
        MessageHistoryRow middle = historyRow(4L, "user", "q4", null);
        MessageHistoryRow oldest = historyRow(3L, "assistant", "a3", null);
        when(messageRepository.findMergedHistory("conversation-a", Long.MAX_VALUE, 3))
                .thenReturn(List.of(newest, middle, oldest));
        DatabaseChatMemoryRepository mergedRepository = new DatabaseChatMemoryRepository(
                conversationRepository,
                messageRepository,
                mock(MessageArchiveRepository.class),
                mock(ConversationSummaryRepository.class));

        HistoryPage page = mergedRepository.getHistory("user-a", "conversation-a", null, 2);

        assertTrue(page.hasMore());
        assertEquals(4L, page.nextBeforeId());
        assertEquals(List.of(4L, 5L),
                page.messages().stream().map(ChatHistoryEntry::id).toList());
        assertEquals("kb_a", page.messages().get(1).sources().get(0).sourceId());
    }

    @Test
    void clearDeletesActiveArchiveAndSummary() {
        when(conversationRepository.findById("conversation-a"))
                .thenReturn(Optional.of(new Conversation("conversation-a", "user-a")));
        MessageArchiveRepository archiveRepository = mock(MessageArchiveRepository.class);
        ConversationSummaryRepository summaryRepository = mock(ConversationSummaryRepository.class);
        when(summaryRepository.findForUpdate("conversation-a")).thenReturn(Optional.empty());
        DatabaseChatMemoryRepository lifecycleRepository = new DatabaseChatMemoryRepository(
                conversationRepository, messageRepository, archiveRepository, summaryRepository);

        lifecycleRepository.clear("user-a", "conversation-a");

        verify(messageRepository).deleteByConversationId("conversation-a");
        verify(archiveRepository).deleteByConversationId("conversation-a");
        verify(summaryRepository).deleteByConversationId("conversation-a");
    }

    private MessageHistoryRow historyRow(Long id, String role, String content, String sourcesJson) {
        MessageHistoryRow row = mock(MessageHistoryRow.class);
        when(row.getId()).thenReturn(id);
        when(row.getRole()).thenReturn(role);
        when(row.getContent()).thenReturn(content);
        when(row.getTs()).thenReturn(LocalDateTime.now());
        when(row.getSourcesJson()).thenReturn(sourcesJson);
        return row;
    }

    @Test
    void clearingMissingConversationIsIdempotentAndDoesNotCreateIt() {
        when(conversationRepository.findById("conversation-a")).thenReturn(Optional.empty());

        repository.clear("user-a", "conversation-a");

        verify(conversationRepository, never()).save(any(Conversation.class));
        verifyNoInteractions(messageRepository);
    }
}
