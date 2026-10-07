package com.example.demo.chat.repository;

import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.entity.Conversation;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.exception.ConversationAccessDeniedException;
import com.example.demo.chat.repository.mysql.ConversationRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
}
