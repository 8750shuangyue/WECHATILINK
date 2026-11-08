package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.example.demo.chat.entity.sqlite.VectorStore;
import com.example.demo.chat.repository.sqlite.VectorStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 向量写入：对话记忆与公共知识文档的保存/整体替换。不负责清理与清空事务。 */
@Slf4j
final class VectorDocumentStore {

    private final VectorStoreRepository repository;
    private final EmbeddingService embeddingService;
    private final VectorIndex index;
    private final Set<String> clearedConversationKeys;
    private final Object conversationMutationLock;
    private final PlatformTransactionManager transactionManager;

    VectorDocumentStore(VectorStoreRepository repository,
                        EmbeddingService embeddingService,
                        VectorIndex index,
                        Set<String> clearedConversationKeys,
                        Object conversationMutationLock,
                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.index = index;
        this.clearedConversationKeys = clearedConversationKeys;
        this.conversationMutationLock = conversationMutationLock;
        this.transactionManager = transactionManager;
    }

    void saveMessage(String userId, String conversationId, String userMessage, String assistantReply) {
        try {
            String conversationKey = VectorDocuments.conversationKey(userId, conversationId);
            if (clearedConversationKeys.contains(conversationKey)) {
                log.debug("[VectorStore] Conversation was cleared, skipping stale vector write, userId: {}, conversationId: {}",
                        userId, conversationId);
                return;
            }
            log.info("[VectorStore] saveMessage start, userId: {}, conversationId: {}, content length: {}",
                    userId, conversationId, (userMessage + assistantReply).length());
            String docId = VectorDocuments.messageDocumentId(userId, conversationId, userMessage, assistantReply);
            if (repository.findByDocumentId(docId).isPresent()) {
                log.debug("[VectorStore] Duplicate message vector skipped, userId: {}, conversationId: {}, docId: {}",
                        userId, conversationId, docId);
                return;
            }

            String combinedContent = "用户: " + userMessage + "\n助手: " + assistantReply;
            float[] embedding = embeddingService.embed(combinedContent);

            if (embedding.length == 0) {
                log.warn("[VectorStore] Embedding is empty, skipping save");
                return;
            }

            byte[] vectorBytes = VectorDocuments.serializeVector(embedding);

            VectorStore vs = new VectorStore(docId, combinedContent, vectorBytes);
            vs.setUserId(userId);
            vs.setConversationId(conversationId);
            synchronized (conversationMutationLock) {
                if (clearedConversationKeys.contains(conversationKey)) {
                    log.debug("[VectorStore] Conversation was cleared during embedding, skipping stale vector write, userId: {}, conversationId: {}",
                            userId, conversationId);
                    return;
                }

                VectorStore saved;
                try {
                    saved = repository.save(vs);
                } catch (DataIntegrityViolationException e) {
                    log.info("[VectorStore] Concurrent duplicate message vector skipped, userId: {}, conversationId: {}, docId: {}",
                            userId, conversationId, docId);
                    return;
                }

                if (saved.getId() != null) {
                    index.add(saved.getId().intValue(), combinedContent, embedding,
                            saved.getDocumentId(), userId, conversationId, null, null);
                    log.info("[VectorStore] Saved message vector, rowId: {}, userId: {}, conversationId: {}",
                            saved.getId(), userId, conversationId);
                }
            }
        } catch (Exception e) {
            log.error("[VectorStore] Failed to save vector, cause: {}", e.getMessage(), e);
        }
    }

    void saveDocument(String sourceId, String content, Map<String, Object> metadata) {
        try {
            String docId = VectorDocuments.chunkDocumentId(sourceId, 0, content);
            if (repository.findByDocumentId(docId).isPresent()) {
                log.debug("Duplicate public knowledge vector skipped, sourceId: {}, docId: {}",
                        sourceId, docId);
                return;
            }

            float[] embedding;
            try {
                embedding = embeddingService.embed(content);
            } catch (IOException e) {
                throw new IllegalStateException("embedding failed for sourceId " + sourceId, e);
            }

            if (embedding.length == 0) {
                log.warn("Embedding is empty for sourceId: {}, skipping save", sourceId);
                return;
            }

            byte[] vectorBytes = VectorDocuments.serializeVector(embedding);

            VectorStore vectorStore = new VectorStore(docId, content, vectorBytes);
            vectorStore.setSourceId(sourceId);
            if (metadata != null) {
                vectorStore.setMetadataJson(JSON.toJSONString(metadata));
            }
            vectorStore.setTimestamp(LocalDateTime.now());

            VectorStore saved = repository.save(vectorStore);

            if (saved.getId() != null) {
                index.add(saved.getId().intValue(), content, embedding,
                        saved.getDocumentId(), null, null, sourceId, saved.getMetadataJson());
                log.info("Saved public knowledge vector, sourceId: {}, docId: {}, rowId: {}, vector dim: {}",
                        sourceId, docId, saved.getId(), embedding.length);
            }
        } catch (Exception e) {
            log.error("Failed to save document vector to SQLite", e);
        }
    }

    int replaceDocument(String sourceId, List<String> chunks, Map<String, Object> metadata) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i) == null || chunks.get(i).isBlank()) {
                throw new IllegalArgumentException("chunk content must not be blank");
            }
        }

        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedBatch(chunks);
        } catch (IOException exception) {
            throw new IllegalStateException("embedding failed for sourceId " + sourceId, exception);
        }
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new IllegalStateException("embedding count mismatch for sourceId " + sourceId);
        }

        List<VectorStore> vectors = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i);
            float[] embedding = embeddings.get(i);
            if (embedding == null || embedding.length == 0) {
                throw new IllegalStateException("embedding is empty for chunk " + (i + 1));
            }

            Map<String, Object> mergedMetadata = metadata == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(metadata);
            mergedMetadata.putIfAbsent("sourceId", sourceId);
            mergedMetadata.put("chunkIndex", i);
            mergedMetadata.put("chunkCount", chunks.size());

            VectorStore vectorStore = new VectorStore(
                    VectorDocuments.chunkDocumentId(sourceId, i, content),
                    content,
                    VectorDocuments.serializeVector(embedding)
            );
            vectorStore.setSourceId(sourceId);
            vectorStore.setMetadataJson(JSON.toJSONString(mergedMetadata));
            vectorStore.setTimestamp(LocalDateTime.now());
            vectors.add(vectorStore);
        }

        int savedCount = transactionManager == null
                ? persistReplacedDocument(sourceId, vectors)
                : new TransactionTemplate(transactionManager)
                        .execute(status -> persistReplacedDocument(sourceId, vectors));

        log.info("Replaced public knowledge sourceId: {}, chunks: {}",
                sourceId, savedCount);
        return savedCount;
    }

    private int persistReplacedDocument(String sourceId, List<VectorStore> vectors) {
        repository.deleteBySourceId(sourceId);
        // 正文未变的文档会生成相同的 documentId；必须先落删除，否则本次插入会撞旧行的唯一索引。
        repository.flush();
        List<VectorStore> saved = repository.saveAll(vectors);
        repository.flush();
        reloadIndex();
        return saved == null ? vectors.size() : saved.size();
    }

    private void reloadIndex() {
        index.load(repository.findAll());
    }
}
