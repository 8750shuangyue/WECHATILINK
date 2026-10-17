package com.example.demo.chat;

import com.example.demo.chat.entity.sqlite.VectorStore;
import com.example.demo.chat.repository.sqlite.VectorStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
        when(embeddingService.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> embeddings = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new float[]{1.0f, 0.0f});
            }
            return embeddings;
        });

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
    void replacesSourceWithStableChunkDocumentIds() {
        Map<String, VectorStore> database = new LinkedHashMap<>();
        AtomicLong sequence = new AtomicLong(1);
        when(repository.findAll()).thenAnswer(invocation -> new ArrayList<>(database.values()));
        doAnswer(invocation -> {
            database.values().removeIf(vector -> "guide-1".equals(vector.getSourceId()));
            return null;
        }).when(repository).deleteBySourceId("guide-1");
        when(repository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<VectorStore> vectors = invocation.getArgument(0);
            List<VectorStore> saved = new ArrayList<>();
            for (VectorStore vector : vectors) {
                vector.setId(sequence.getAndIncrement());
                database.put(vector.getDocumentId(), vector);
                saved.add(vector);
            }
            return saved;
        });

        int firstCount = service.replaceDocument(
                "guide-1", List.of("first chunk", "second chunk"),
                Map.of("fileName", "guide.md"));
        List<String> firstDocumentIds = new ArrayList<>(database.keySet());

        int secondCount = service.replaceDocument(
                "guide-1", List.of("first chunk", "second chunk"),
                Map.of("fileName", "guide.md"));
        List<String> secondDocumentIds = new ArrayList<>(database.keySet());

        assertEquals(2, firstCount);
        assertEquals(2, secondCount);
        assertEquals(firstDocumentIds, secondDocumentIds);
        assertEquals(2, database.size());
        verify(repository, times(2)).deleteBySourceId("guide-1");
        verify(repository, times(2)).saveAll(any());
        verify(repository, times(4)).flush();

        // 删除必须先落库，否则正文未变的文档会以其相同的 documentId 撞旧行唯一索引。
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).deleteBySourceId("guide-1");
        inOrder.verify(repository).flush();
        inOrder.verify(repository).saveAll(any());
    }

    @Test
    void replaceDocumentUsesOneBatchEmbeddingCall() throws Exception {
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        int count = service.replaceDocument("guide-batch",
                List.of("first chunk", "second chunk"), Map.of());

        assertEquals(2, count);
        verify(embeddingService).embedBatch(List.of("first chunk", "second chunk"));
        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    void embeddingFailureLeavesExistingVectorsUntouched() throws Exception {
        when(embeddingService.embedBatch(anyList()))
                .thenThrow(new IOException("embedding unavailable"));

        assertThrows(IllegalStateException.class, () -> service.replaceDocument(
                "guide-batch", List.of("first chunk", "second chunk"), Map.of()));

        verify(repository, never()).deleteBySourceId("guide-batch");
        verify(repository, never()).saveAll(any());
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
        assertSqliteTransaction("clearDocumentVectorsStrict", String.class);
        assertSqliteTransaction("cleanupDuplicateConversationVectors");
        assertSqliteTransaction("deleteExpiredConversationVectors", LocalDateTime.class);
    }

    @Test
    void strictClearRemovesSourceFromInMemoryIndexImmediately() {
        service.addToIndex(0, "stale community post", new float[]{1.0f, 0.0f},
                "doc-post", null, null, "post_42", null);

        service.clearDocumentVectorsStrict("post_42");

        assertTrue(service.searchSimilar("query").isEmpty());
        verify(repository).deleteBySourceId("post_42");
        verify(repository).flush();
    }

    @Test
    void strictClearPropagatesRepositoryFailure() {
        doThrow(new IllegalStateException("sqlite unavailable"))
                .when(repository)
                .deleteBySourceId("post_42");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.clearDocumentVectorsStrict("post_42")
        );

        assertEquals("sqlite unavailable", error.getMessage());
    }

    @Test
    void migratesLegacyConversationVectorsAndDeletesOnlySemanticDuplicates() {
        Map<Long, VectorStore> database = new LinkedHashMap<>();
        VectorStore oldest = vector(1L, "legacy-1", "用户: hello\n助手: world",
                "user-a", "conversation-a", null);
        oldest.setTimestamp(LocalDateTime.of(2026, 1, 1, 8, 0));
        VectorStore duplicate = vector(2L, "legacy-2", "用户: hello\n助手: world",
                "user-a", "conversation-a", null);
        duplicate.setTimestamp(LocalDateTime.of(2026, 1, 2, 8, 0));
        VectorStore otherUser = vector(3L, "legacy-3", "用户: hello\n助手: world",
                "user-b", "conversation-a", null);
        VectorStore otherConversation = vector(4L, "legacy-4", "用户: hello\n助手: world",
                "user-a", "conversation-b", null);
        VectorStore publicVector = vector(5L, "public-doc", "public knowledge",
                null, null, "guide-1");
        database.put(1L, oldest);
        database.put(2L, duplicate);
        database.put(3L, otherUser);
        database.put(4L, otherConversation);
        database.put(5L, publicVector);

        when(repository.findConversationVectors()).thenAnswer(invocation -> database.values().stream()
                .filter(vector -> vector.getConversationId() != null
                        && !vector.getConversationId().isBlank())
                .filter(vector -> vector.getSourceId() == null || vector.getSourceId().isBlank())
                .toList());
        doAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            for (Long id : ids) {
                database.remove(id);
            }
            return null;
        }).when(repository).deleteAllByIdInBatch(any());
        when(repository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<VectorStore> vectors = invocation.getArgument(0);
            List<VectorStore> saved = new ArrayList<>();
            for (VectorStore vector : vectors) {
                database.put(vector.getId(), vector);
                saved.add(vector);
            }
            return saved;
        });
        when(repository.findAll()).thenAnswer(invocation -> List.copyOf(database.values()));

        int deleted = service.cleanupDuplicateConversationVectors();

        assertEquals(1, deleted);
        assertEquals(4, database.size());
        verify(repository).deleteAllByIdInBatch(List.of(2L));
        VectorStore migrated = database.get(1L);
        assertEquals(64, migrated.getDocumentId().length());
        assertNotEquals("legacy-1", migrated.getDocumentId());

        VectorStore migratedOtherUser = database.get(3L);
        assertEquals(64, migratedOtherUser.getDocumentId().length());
        assertNotEquals(migrated.getDocumentId(), migratedOtherUser.getDocumentId());
        assertEquals(64, database.get(4L).getDocumentId().length());
        assertEquals("public-doc", database.get(5L).getDocumentId());

        assertEquals(0, service.cleanupDuplicateConversationVectors());
        verify(repository, times(1)).deleteAllByIdInBatch(any());
    }

    @Test
    void vectorFloorAdmitsPublicChunkThatOldThresholdRejected() {
        // 这正是一批零召回样本的成因：期望文档排在第 1、2 名，余弦却卡在 0.5 以下
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.45);
        service.addToIndex(0, "浇水与积水：黄叶、掉叶、烂根早期", vectorAtCosine(0.47),
                "doc-near", null, null, "kb_plant_watering", null);

        assertEquals(List.of("浇水与积水：黄叶、掉叶、烂根早期"),
                service.searchSimilar("绿萝叶子发黄", "user-a", "conversation-a"));

        ReflectionTestUtils.setField(service, "similarityThreshold", 0.5);
        assertTrue(service.searchSimilar("绿萝叶子发黄", "user-a", "conversation-a").isEmpty());
    }

    @Test
    void keywordChannelAdmitsPublicChunkWithStrongTermOverlap() {
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.45);
        ReflectionTestUtils.setField(service, "keywordFloor", 0.45);
        ReflectionTestUtils.setField(service, "keywordEnabled", true);
        // 余弦只有 0.30，远在任何合理阈值之下，只能靠词面命中进来
        service.addToIndex(0, "仓鼠出现湿尾和严重腹泻是危急信号", vectorAtCosine(0.30),
                "doc-keyword", null, null, "kb_pet_small_pet_abnormal", null);

        assertEquals(List.of("仓鼠出现湿尾和严重腹泻是危急信号"),
                service.searchSimilar("仓鼠湿尾和严重腹泻怎么办", "user-a", "conversation-a"));
    }

    @Test
    void keywordChannelDoesNotAdmitPublicChunkWithoutTermOverlap() {
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.45);
        ReflectionTestUtils.setField(service, "keywordFloor", 0.45);
        ReflectionTestUtils.setField(service, "keywordEnabled", true);
        service.addToIndex(0, "浇水与积水：黄叶、掉叶、烂根早期", vectorAtCosine(0.30),
                "doc-near", null, null, "kb_plant_watering", null);

        assertTrue(service.searchSimilar("仓鼠湿尾", "user-a", "conversation-a").isEmpty());
    }

    @Test
    void keywordChannelIsIgnoredWhenDisabled() {
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.45);
        ReflectionTestUtils.setField(service, "keywordFloor", 0.45);
        ReflectionTestUtils.setField(service, "keywordEnabled", false);
        service.addToIndex(0, "仓鼠出现湿尾和严重腹泻是危急信号", vectorAtCosine(0.30),
                "doc-keyword", null, null, "kb_pet_small_pet_abnormal", null);

        // 关掉关键词通道就是纯向量检索，这正是回滚路径
        assertTrue(service.searchSimilar("仓鼠湿尾和严重腹泻怎么办", "user-a", "conversation-a").isEmpty());
    }

    @Test
    void keywordChannelDoesNotApplyToConversationMemory() {
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.45);
        ReflectionTestUtils.setField(service, "keywordFloor", 0.45);
        ReflectionTestUtils.setField(service, "keywordEnabled", true);
        // 同一会话的旧轮次与当前问题词面天然重合，所以对话记忆刻意不接关键词通道
        service.addToIndex(0, "仓鼠湿尾和严重腹泻", vectorAtCosine(0.30),
                "doc-memory", "user-a", "conversation-a", null, null);

        assertTrue(service.searchSimilar("仓鼠湿尾和严重腹泻怎么办", "user-a", "conversation-a").isEmpty());
    }

    @Test
    void recordsAdmittingChannelAndKeywordScoreInAuditLog() {
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.45);
        ReflectionTestUtils.setField(service, "keywordFloor", 0.45);
        ReflectionTestUtils.setField(service, "keywordEnabled", true);
        service.addToIndex(0, "仓鼠出现湿尾和严重腹泻是危急信号", vectorAtCosine(0.30),
                "doc-keyword", null, null, "kb_pet_small_pet_abnormal", null);

        service.searchSimilarWithMetadata("仓鼠湿尾和严重腹泻怎么办", "user-a", "conversation-a");

        ArgumentCaptor<String> resultsJson = ArgumentCaptor.forClass(String.class);
        verify(retrievalLogService).record(
                anyString(), eq("user-a"), eq("conversation-a"), anyString(), eq("public_only"),
                eq(5), eq(0.45), eq(1), anyLong(), resultsJson.capture());

        assertTrue(resultsJson.getValue().contains("\"channel\":\"keyword\""));
        assertTrue(resultsJson.getValue().contains("keywordScore"));
        assertFalse(resultsJson.getValue().contains("仓鼠"), "命中正文不得进入审计日志");
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

    /**
     * 构造一个与查询向量 {1,0} 夹角余弦恰好为 {@code cosine} 的文档向量。
     * 现有测试一直用恒定向量，相似度恒为 1.0，阈值边界从来没被覆盖过。
     */
    private static float[] vectorAtCosine(double cosine) {
        return new float[]{(float) cosine, (float) Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine))};
    }
}
