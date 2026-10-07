package com.example.demo.chat.repository;

import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.entity.Conversation;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.exception.ConversationAccessDeniedException;
import com.example.demo.chat.repository.mysql.ConversationRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ChatMemorySchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private DatabaseChatMemoryRepository chatMemoryRepository;

    private final List<String> conversationIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String conversationId : conversationIds) {
            jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM conversations WHERE conversation_id = ?", conversationId);
        }
        conversationIds.clear();
    }

    @Test
    void migrationAddsNullableUserIdColumnForLegacyCompatibility() {
        String nullable = jdbcTemplate.queryForObject(
                """
                SELECT IS_NULLABLE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'conversations'
                  AND COLUMN_NAME = 'user_id'
                """,
                String.class);

        assertEquals("YES", nullable);
    }

    @Test
    void legacyUnownedConversationIsReadOnlyAndCannotBeClaimed() {
        String conversationId = newConversationId("legacy");
        conversationRepository.saveAndFlush(new Conversation(conversationId, null));
        messageRepository.saveAndFlush(new Message(
                conversationId, "user", "legacy message", LocalDateTime.now()));

        assertTrue(chatMemoryRepository.getMessages("user-a", conversationId).isEmpty());
        assertFalse(chatMemoryRepository.exists("user-a", conversationId));
        assertThrows(ConversationAccessDeniedException.class,
                () -> chatMemoryRepository.addMessage(
                        "user-a", conversationId, new ChatMessage("user", "new message")));
        assertEquals(1, messageRepository.countByConversationId(conversationId));
    }

    @Test
    void newConversationIsPersistedWithOwnerAndIsolatedFromOtherUsers() {
        String conversationId = newConversationId("owner");

        chatMemoryRepository.addMessage(
                "user-a", conversationId, new ChatMessage("user", "hello"));

        Conversation stored = conversationRepository.findById(conversationId).orElseThrow();
        assertEquals("user-a", stored.getUserId());
        assertEquals(1, chatMemoryRepository.getMessages("user-a", conversationId).size());
        assertTrue(chatMemoryRepository.getMessages("user-b", conversationId).isEmpty());
        assertThrows(ConversationAccessDeniedException.class,
                () -> chatMemoryRepository.addMessage(
                        "user-b", conversationId, new ChatMessage("user", "not allowed")));
        assertEquals(1, messageRepository.countByConversationId(conversationId));
    }

    @Test
    void rolledBackConversationCreationLeavesNoConversationOrMessages() {
        String conversationId = newConversationId("rollback");

        transactionTemplate.executeWithoutResult(status -> {
            chatMemoryRepository.addMessage(
                    "user-a", conversationId, new ChatMessage("user", "temporary"));
            assertTrue(conversationRepository.existsById(conversationId));
            assertEquals(1, messageRepository.countByConversationId(conversationId));
            status.setRollbackOnly();
        });

        assertFalse(conversationRepository.existsById(conversationId));
        assertEquals(0, messageRepository.countByConversationId(conversationId));
    }

    private String newConversationId(String prefix) {
        String conversationId = prefix + "_" + UUID.randomUUID();
        conversationIds.add(conversationId);
        return conversationId;
    }
}
