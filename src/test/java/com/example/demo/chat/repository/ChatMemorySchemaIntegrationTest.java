package com.example.demo.chat.repository;

import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.entity.Conversation;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.exception.ConversationAccessDeniedException;
import com.example.demo.chat.lifecycle.MessageLifecycleScheduler;
import com.example.demo.chat.repository.mysql.ConversationRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Autowired
    private MessageLifecycleScheduler lifecycleScheduler;

    private final List<String> conversationIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String conversationId : conversationIds) {
            jdbcTemplate.update("DELETE FROM messages_archive WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM conversation_summaries WHERE conversation_id = ?", conversationId);
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
    void messagesTableHasNullableSourcesJsonColumn() {
        String nullable = jdbcTemplate.queryForObject(
                """
                SELECT IS_NULLABLE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'messages'
                  AND COLUMN_NAME = 'sources_json'
                """,
                String.class);

        assertEquals("YES", nullable);
    }

    @Test
    void sourcesJsonRoundTripsThroughHistoryAndIsClearedWithConversation() {
        String conversationId = newConversationId("sources");
        String sourcesJson = "{\"v\":1,\"traceId\":\"trace-1\",\"sources\":["
                + "{\"sourceId\":\"kb_a\",\"title\":\"标题 A\",\"rank\":1}]}";

        chatMemoryRepository.addMessage(
                "user-a", conversationId, new ChatMessage("user", "question"));
        chatMemoryRepository.addMessage(
                "user-a", conversationId, new ChatMessage("assistant", "answer[1]"), sourcesJson);

        var page = chatMemoryRepository.getHistory("user-a", conversationId, null, 10);
        assertEquals(2, page.messages().size());
        assertEquals(1, page.messages().get(1).sources().size());
        assertEquals("kb_a", page.messages().get(1).sources().get(0).sourceId());
        assertEquals(1, page.messages().get(1).sources().get(0).rank());

        chatMemoryRepository.clear("user-a", conversationId);
        assertTrue(chatMemoryRepository.getHistory("user-a", conversationId, null, 10)
                .messages().isEmpty());
    }

    @Test
    void lifecycleSchedulerModifyingTasksRunInsideTransactions() {
        ReflectionTestUtils.setField(lifecycleScheduler, "enabled", true);
        ReflectionTestUtils.setField(lifecycleScheduler, "retryLimit", 0);
        ReflectionTestUtils.setField(lifecycleScheduler, "summaryKeepRecent", 1_000_000);
        ReflectionTestUtils.setField(lifecycleScheduler, "archiveRetentionDays", 180L);

        assertDoesNotThrow(lifecycleScheduler::retrySummaries);
        assertDoesNotThrow(lifecycleScheduler::cleanupArchive);
    }

    @Test
    void lifecycleTablesAndMessageIndexExist() {
        Integer tables = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME IN ('messages_archive','conversation_summaries')
                """,
                Integer.class);
        Integer index = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT INDEX_NAME) FROM INFORMATION_SCHEMA.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'messages'
                   AND INDEX_NAME = 'idx_messages_conv_id'
                """,
                Integer.class);

        assertEquals(2, tables);
        assertEquals(1, index);
    }

    @Test
    void historyMergesActiveAndArchivedMessagesWithSources() {
        String conversationId = newConversationId("history");
        String sourcesJson = "{\"v\":1,\"traceId\":\"trace-1\",\"sources\":["
                + "{\"sourceId\":\"kb_a\",\"title\":\"标题 A\",\"rank\":1}]}";

        chatMemoryRepository.addMessage("user-a", conversationId,
                new ChatMessage("user", "q1"));
        chatMemoryRepository.addMessage("user-a", conversationId,
                new ChatMessage("assistant", "a1[1]"), sourcesJson);
        chatMemoryRepository.addMessage("user-a", conversationId,
                new ChatMessage("user", "q2"));
        chatMemoryRepository.addMessage("user-a", conversationId,
                new ChatMessage("assistant", "a2"));

        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM messages WHERE conversation_id = ? ORDER BY id",
                Long.class, conversationId);
        jdbcTemplate.update("""
                INSERT INTO messages_archive
                    (id, conversation_id, role, content, `timestamp`, sources_json, archived_at)
                SELECT id, conversation_id, role, content, `timestamp`, sources_json, NOW(6)
                  FROM messages WHERE id IN (?, ?)
                """, ids.get(0), ids.get(1));
        jdbcTemplate.update("DELETE FROM messages WHERE id IN (?, ?)",
                ids.get(0), ids.get(1));

        var page = chatMemoryRepository.getHistory("user-a", conversationId, null, 10);

        assertEquals(4, page.messages().size());
        assertEquals(ids.get(0), page.messages().get(0).id());
        assertEquals("kb_a", page.messages().get(1).sources().get(0).sourceId());
        assertEquals(ids.get(3), page.messages().get(3).id());
    }

    @Test
    void clearDeletesActiveArchiveAndSummaryRows() {
        String conversationId = newConversationId("clear-lifecycle");
        chatMemoryRepository.addMessage("user-a", conversationId,
                new ChatMessage("user", "q1"));
        chatMemoryRepository.addMessage("user-a", conversationId,
                new ChatMessage("assistant", "a1"));

        Long firstId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM messages WHERE conversation_id = ?",
                Long.class, conversationId);
        jdbcTemplate.update("""
                INSERT INTO messages_archive
                    (id, conversation_id, role, content, `timestamp`, sources_json, archived_at)
                SELECT id, conversation_id, role, content, `timestamp`, sources_json, NOW(6)
                  FROM messages WHERE id = ?
                """, firstId);
        jdbcTemplate.update("""
                INSERT INTO conversation_summaries
                    (conversation_id, summary_text, covered_until_id, version, status,
                     attempt_count, created_at, updated_at)
                VALUES (?, 'summary', 0, 0, 'READY', 0, NOW(6), NOW(6))
                """, conversationId);

        chatMemoryRepository.clear("user-a", conversationId);

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
                Integer.class, conversationId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages_archive WHERE conversation_id = ?",
                Integer.class, conversationId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_summaries WHERE conversation_id = ?",
                Integer.class, conversationId));
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
