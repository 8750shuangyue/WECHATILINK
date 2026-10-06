package com.example.demo.chat;

import com.example.demo.chat.entity.sqlite.VectorStore;
import com.example.demo.chat.repository.sqlite.VectorStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
}
