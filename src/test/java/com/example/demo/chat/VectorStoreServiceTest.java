package com.example.demo.chat;

import com.example.demo.chat.entity.sqlite.VectorStore;
import com.example.demo.chat.repository.sqlite.VectorStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorStoreServiceTest {

    private VectorStoreRepository repository;
    private EmbeddingService embeddingService;
    private RagRetrievalLogService retrievalLogService;
    private VectorStoreService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(VectorStoreRepository.class);
        embeddingService = mock(EmbeddingService.class);
        retrievalLogService = mock(RagRetrievalLogService.class);
        when(repository.findAll()).thenReturn(List.of());
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        service = new VectorStoreService(repository, embeddingService, retrievalLogService);
        ReflectionTestUtils.setField(service, "topK", 5);
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.5);
        service.init();
    }

    @Test
    void isolatesConversationMemoryByUserAndConversation() {
        service.addToIndex(0, "user-a conversation-a memory", new float[]{1.0f, 0.0f},
                "doc-a", "user-a", "conversation-a", null, null);
        service.addToIndex(1, "user-b conversation-b memory", new float[]{1.0f, 0.0f},
                "doc-b", "user-b", "conversation-b", null, null);

        List<String> ownMemory = service.searchSimilar("query", "user-a", "conversation-a");
        assertEquals(List.of("user-a conversation-a memory"), ownMemory);

        assertTrue(service.searchSimilar("query", "user-b", "conversation-a").isEmpty());
        assertTrue(service.searchSimilar("query", "user-a", "conversation-b").isEmpty());
        assertTrue(service.searchSimilar("query", "conversation-a").isEmpty());
    }

    @Test
    void sharesOnlyPublicKnowledgeAcrossUsers() {
        service.addToIndex(0, "public plant care knowledge", new float[]{1.0f, 0.0f},
                "doc-public", null, null, "kb_plant", "{\"topic\":\"plant\"}");
        service.addToIndex(1, "private user-a memory", new float[]{1.0f, 0.0f},
                "doc-private", "user-a", "conversation-a", null, null);

        List<String> userAResults = service.searchSimilar("query", "user-a", "conversation-a");
        List<String> userBResults = service.searchSimilar("query", "user-b", "conversation-b");

        assertTrue(userAResults.contains("public plant care knowledge"));
        assertTrue(userBResults.contains("public plant care knowledge"));
        assertFalse(userBResults.contains("private user-a memory"));
    }

    @Test
    void recordsAuditableMetadataWithoutPersistingRetrievedContent() {
        service.addToIndex(0, "private content must not be logged", new float[]{1.0f, 0.0f},
                "doc-private", "user-a", "conversation-a", null, null);
        service.addToIndex(1, "public content must not be logged", new float[]{1.0f, 0.0f},
                "doc-public", null, null, "kb_plant", null);

        service.searchSimilarWithMetadata("query", "user-a", "conversation-a");

        ArgumentCaptor<String> queryHash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultsJson = ArgumentCaptor.forClass(String.class);
        verify(retrievalLogService).record(
                anyString(),
                eq("user-a"),
                eq("conversation-a"),
                queryHash.capture(),
                eq("mixed"),
                eq(5),
                eq(0.5),
                eq(2),
                anyLong(),
                resultsJson.capture()
        );

        assertNotEquals("query", queryHash.getValue());
        assertEquals(64, queryHash.getValue().length());
        assertTrue(resultsJson.getValue().contains("doc-private"));
        assertTrue(resultsJson.getValue().contains("doc-public"));
        assertFalse(resultsJson.getValue().contains("must not be logged"));
    }

    @Test
    void retrievalStillWorksWhenLoggingFails() {
        service.addToIndex(0, "resilient memory", new float[]{1.0f, 0.0f},
                "doc-a", "user-a", "conversation-a", null, null);
        doThrow(new RuntimeException("audit store unavailable"))
                .when(retrievalLogService)
                .record(anyString(), any(), any(), any(), any(), eq(5), eq(0.5),
                        eq(1), anyLong(), any());

        List<String> results = service.searchSimilar("query", "user-a", "conversation-a");

        assertEquals(List.of("resilient memory"), results);
    }

    @Test
    void storesSameMessagePairOnceAndUsesStableDocumentId() throws Exception {
        AtomicReference<String> savedDocumentId = new AtomicReference<>();
        VectorStore existing = new VectorStore();
        existing.setId(1L);
        when(repository.findByDocumentId(anyString())).thenAnswer(invocation -> {
            String requestedId = invocation.getArgument(0);
            return requestedId.equals(savedDocumentId.get())
                    ? Optional.of(existing)
                    : Optional.empty();
        });
        when(repository.save(any(VectorStore.class))).thenAnswer(invocation -> {
            VectorStore vectorStore = invocation.getArgument(0);
            vectorStore.setId(1L);
            savedDocumentId.set(vectorStore.getDocumentId());
            return vectorStore;
        });

        service.saveMessage("user-a", "conversation-a", "How old is Doudou?", "Doudou is 5.");
        service.saveMessage("user-a", "conversation-a", "How old is Doudou?", "Doudou is 5.");

        ArgumentCaptor<String> documentIds = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).findByDocumentId(documentIds.capture());
        assertEquals(documentIds.getAllValues().get(0), documentIds.getAllValues().get(1));
        assertEquals(64, documentIds.getAllValues().get(0).length());
        verify(embeddingService, times(1)).embed(anyString());
        verify(repository, times(1)).save(any(VectorStore.class));
        assertEquals(1, service.countVectors());
    }

    @Test
    void clearsOnlyOwnersConversationVectorsAndKeepsOtherUsersMemory() {
        Map<Long, VectorStore> database = new LinkedHashMap<>();
        database.put(1L, vector(1L, "doc-a", "user-a conversation-a memory",
                "user-a", "conversation-a", null));
        database.put(2L, vector(2L, "doc-b", "user-b conversation-a memory",
                "user-b", "conversation-a", null));
        database.put(3L, vector(3L, "doc-public", "public plant care knowledge",
                null, null, "kb_plant"));
        when(repository.findAll()).thenAnswer(invocation -> List.copyOf(database.values()));
        doAnswer(invocation -> {
            database.entrySet().removeIf(entry -> {
                VectorStore vector = entry.getValue();
                return "user-a".equals(vector.getUserId())
                        && "conversation-a".equals(vector.getConversationId());
            });
            return null;
        }).when(repository).deleteByUserIdAndConversationId("user-a", "conversation-a");

        service.clearConversationVectors("user-a", "conversation-a");

        verify(repository).deleteByUserIdAndConversationId("user-a", "conversation-a");
        verify(repository, never()).deleteByConversationId(anyString());
        assertFalse(service.searchSimilar("query", "user-a", "conversation-a")
                .contains("user-a conversation-a memory"));
        assertTrue(service.searchSimilar("query", "user-b", "conversation-a")
                .contains("user-b conversation-a memory"));
        assertTrue(service.searchSimilar("query", "user-c", "conversation-c")
                .contains("public plant care knowledge"));
    }

    @Test
    void clearOperationsUseSqliteTransactionManager() throws Exception {
        assertSqliteTransaction("clearConversationVectors", String.class, String.class);
        assertSqliteTransaction("clearDocumentVectors", String.class);
    }

    private void assertSqliteTransaction(String methodName, Class<?>... parameterTypes) throws Exception {
        Transactional transactional = VectorStoreService.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional, methodName + " must run in a SQLite transaction");
        assertEquals("sqliteTransactionManager", transactional.transactionManager());
    }

    private VectorStore vector(Long id, String documentId, String content,
                               String userId, String conversationId, String sourceId) {
        VectorStore vector = new VectorStore(documentId, content, serializeVector(new float[]{1.0f, 0.0f}));
        vector.setId(id);
        vector.setUserId(userId);
        vector.setConversationId(conversationId);
        vector.setSourceId(sourceId);
        return vector;
    }

    private byte[] serializeVector(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }
}
