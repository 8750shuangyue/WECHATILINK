package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.entity.sqlite.VectorStore;
import com.example.demo.chat.repository.sqlite.VectorStoreRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
@DependsOn("vectorStoreSchemaMigration")
public class VectorStoreService {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreService.class);

    private static final String ORIGIN_PUBLIC_KB = "public_kb";
    private static final String ORIGIN_CONVERSATION = "conversation_memory";

    private final VectorStoreRepository vectorStoreRepository;
    private final EmbeddingService embeddingService;
    private final RagRetrievalLogService ragRetrievalLogService;

    @Value("${chat.vectorstore.top-k:5}")
    private int topK;

    @Value("${chat.vectorstore.similarity-threshold:0.5}")
    private double similarityThreshold;

    private final List<float[]> vectorIndex = new ArrayList<>();
    private final Map<Integer, IndexEntry> indexEntries = new ConcurrentHashMap<>();
    private final AtomicBoolean indexReady = new AtomicBoolean(false);
    private final ReadWriteLock indexLock = new ReentrantReadWriteLock();

    public VectorStoreService(VectorStoreRepository vectorStoreRepository,
                              EmbeddingService embeddingService,
                              RagRetrievalLogService ragRetrievalLogService) {
        this.vectorStoreRepository = vectorStoreRepository;
        this.embeddingService = embeddingService;
        this.ragRetrievalLogService = ragRetrievalLogService;
    }

    @PostConstruct
    public void init() {
        try {
            loadFromSQLite();
        } catch (Exception e) {
            logger.warn("Vector index initialization failed, will retry on first use: {}", e.getMessage());
            indexReady.set(false);
        }
    }

    private void loadFromSQLite() {
        indexLock.writeLock().lock();
        try {
            List<VectorStore> allVectors = vectorStoreRepository.findAll();
            vectorIndex.clear();
            indexEntries.clear();
            for (VectorStore vs : allVectors) {
                if (vs.getId() == null) {
                    continue;
                }
                float[] vector = deserializeVector(vs.getVector());
                if (vector.length > 0) {
                    int idx = vs.getId().intValue();
                    while (vectorIndex.size() <= idx) {
                        vectorIndex.add(null);
                    }
                    vectorIndex.set(idx, vector);
                    indexEntries.put(idx, new IndexEntry(
                            vs.getDocumentId(),
                            vs.getContent(),
                            vs.getUserId(),
                            vs.getConversationId(),
                            vs.getSourceId(),
                            vs.getMetadataJson()
                    ));
                }
            }
            indexReady.set(true);
            logger.info("Vector index loaded with {} vectors", indexEntries.size());
        } catch (Exception e) {
            logger.error("Failed to load vector index from SQLite", e);
            indexReady.set(false);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    public void saveMessage(String conversationId, String userMessage, String assistantReply) {
        saveMessage(null, conversationId, userMessage, assistantReply);
    }

    /**
     * 保存一轮对话的向量记忆，显式带用户身份，避免不同用户之间互相检索。
     */
    public void saveMessage(String userId, String conversationId, String userMessage, String assistantReply) {
        try {
            logger.info("[VectorStore] saveMessage start, userId: {}, conversationId: {}, content length: {}",
                    userId, conversationId, (userMessage + assistantReply).length());
            String docId = buildMessageDocumentId(userId, conversationId, userMessage, assistantReply);
            if (vectorStoreRepository.findByDocumentId(docId).isPresent()) {
                logger.debug("[VectorStore] Duplicate message vector skipped, userId: {}, conversationId: {}, docId: {}",
                        userId, conversationId, docId);
                return;
            }

            String combinedContent = "用户: " + userMessage + "\n助手: " + assistantReply;
            float[] embedding = embeddingService.embed(combinedContent);

            if (embedding.length == 0) {
                logger.warn("[VectorStore] Embedding is empty, skipping save");
                return;
            }

            byte[] vectorBytes = serializeVector(embedding);

            VectorStore vs = new VectorStore(docId, combinedContent, vectorBytes);
            vs.setUserId(userId);
            vs.setConversationId(conversationId);
            VectorStore saved;
            try {
                saved = vectorStoreRepository.save(vs);
            } catch (DataIntegrityViolationException e) {
                logger.info("[VectorStore] Concurrent duplicate message vector skipped, userId: {}, conversationId: {}, docId: {}",
                        userId, conversationId, docId);
                return;
            }

            if (saved.getId() != null) {
                addToIndex(saved.getId().intValue(), combinedContent, embedding,
                        saved.getDocumentId(), userId, conversationId, null, null);
                logger.info("[VectorStore] Saved message vector, rowId: {}, userId: {}, conversationId: {}",
                        saved.getId(), userId, conversationId);
            }
        } catch (Exception e) {
            logger.error("[VectorStore] Failed to save vector, cause: {}", e.getMessage(), e);
        }
    }

    private String buildMessageDocumentId(String userId, String conversationId,
                                          String userMessage, String assistantReply) {
        StringBuilder identity = new StringBuilder();
        appendLengthPrefixed(identity, userId);
        appendLengthPrefixed(identity, conversationId);
        appendLengthPrefixed(identity, userMessage);
        appendLengthPrefixed(identity, assistantReply);
        return sha256(identity.toString());
    }

    private void appendLengthPrefixed(StringBuilder builder, String value) {
        String normalized = value == null ? "" : value;
        builder.append(normalized.length()).append(':').append(normalized);
    }

    public void saveDocument(String sourceId, String content, Map<String, Object> metadata) {
        try {
            float[] embedding = embeddingService.embed(content);

            if (embedding.length == 0) {
                logger.warn("Embedding is empty for sourceId: {}, skipping save", sourceId);
                return;
            }

            String docId = UUID.randomUUID().toString();
            byte[] vectorBytes = serializeVector(embedding);

            VectorStore vectorStore = new VectorStore(docId, content, vectorBytes);
            vectorStore.setSourceId(sourceId);
            if (metadata != null) {
                vectorStore.setMetadataJson(JSON.toJSONString(metadata));
            }
            vectorStore.setTimestamp(LocalDateTime.now());

            VectorStore saved = vectorStoreRepository.save(vectorStore);

            if (saved.getId() != null) {
                addToIndex(saved.getId().intValue(), content, embedding,
                        saved.getDocumentId(), null, null, sourceId, saved.getMetadataJson());
                logger.info("Saved public knowledge vector, sourceId: {}, docId: {}, rowId: {}, vector dim: {}",
                        sourceId, docId, saved.getId(), embedding.length);
            }
        } catch (Exception e) {
            logger.error("Failed to save document vector to SQLite", e);
        }
    }

    public synchronized void addToIndex(int rowId, String content, float[] vector) {
        addToIndex(rowId, content, vector, null, null, null, null, null);
    }

    public void addToIndex(int rowId, String content, float[] vector,
                           String documentId, String userId, String conversationId,
                           String sourceId, String metadataJson) {
        indexLock.writeLock().lock();
        try {
            while (vectorIndex.size() <= rowId) {
                vectorIndex.add(null);
            }
            vectorIndex.set(rowId, vector);
            indexEntries.put(rowId, new IndexEntry(
                    documentId, content, userId, conversationId, sourceId, metadataJson));
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    public List<String> searchSimilar(String query) {
        return searchSimilar(query, null, null);
    }

    public List<String> searchSimilar(String query, String conversationId) {
        return searchSimilar(query, null, conversationId);
    }

    public List<String> searchSimilar(String query, String userId, String conversationId) {
        List<SearchResult> results = searchInternal(query, userId, conversationId);
        List<String> contents = new ArrayList<>(results.size());
        for (SearchResult result : results) {
            contents.add(result.getContent());
        }
        return contents;
    }

    public List<SearchResult> searchSimilarWithMetadata(String query) {
        return searchSimilarWithMetadata(query, null, null);
    }

    public List<SearchResult> searchSimilarWithMetadata(String query, String conversationId) {
        return searchSimilarWithMetadata(query, null, conversationId);
    }

    public List<SearchResult> searchSimilarWithMetadata(String query, String userId, String conversationId) {
        return searchInternal(query, userId, conversationId);
    }

    private List<SearchResult> searchInternal(String query, String userId, String conversationId) {
        long startNanos = System.nanoTime();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        List<SearchResult> results = new ArrayList<>();
        try {
            indexLock.readLock().lock();
            try {
                if (!indexReady.get()) {
                    logger.warn("[RAG] Vector index not ready, traceId: {}", traceId);
                } else if (query == null || query.isBlank()) {
                    logger.debug("[RAG] Empty query, traceId: {}", traceId);
                } else {
                    float[] queryEmbedding = embeddingService.embed(query);
                    if (queryEmbedding.length == 0) {
                        logger.warn("[RAG] Query embedding is empty, traceId: {}", traceId);
                    } else {
                        collectSimilar(queryEmbedding, userId, conversationId, results);
                    }
                }
            } finally {
                indexLock.readLock().unlock();
            }

            results.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
            if (results.size() > topK) {
                results = new ArrayList<>(results.subList(0, topK));
            }
            logger.info("[RAG] traceId={}, userId={}, conversationId={}, retrieved={}, topK={}",
                    traceId, userId, conversationId, results.size(), topK);
        } catch (Exception e) {
            logger.error("[RAG] Vector search failed, traceId: {}", traceId, e);
            results = new ArrayList<>();
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            recordRetrievalLog(traceId, userId, conversationId, query, results, durationMs);
        }
        return results;
    }

    private void collectSimilar(float[] queryEmbedding, String userId, String conversationId,
                                List<SearchResult> results) {
        for (Map.Entry<Integer, IndexEntry> entry : indexEntries.entrySet()) {
            int rowId = entry.getKey();
            IndexEntry indexEntry = entry.getValue();
            if (indexEntry == null || !isVisible(indexEntry, userId, conversationId)) {
                continue;
            }
            if (rowId < 0 || rowId >= vectorIndex.size()) {
                continue;
            }
            float[] storedVector = vectorIndex.get(rowId);
            if (storedVector == null || storedVector.length != queryEmbedding.length) {
                continue;
            }
            double similarity = cosineSimilarity(queryEmbedding, storedVector);
            if (similarity >= similarityThreshold) {
                JSONObject metadata = null;
                if (indexEntry.metadataJson != null && !indexEntry.metadataJson.isEmpty()) {
                    metadata = JSON.parseObject(indexEntry.metadataJson);
                }
                results.add(new SearchResult(
                        indexEntry.documentId,
                        indexEntry.sourceId,
                        indexEntry.content,
                        similarity,
                        metadata,
                        isPublicKnowledge(indexEntry) ? ORIGIN_PUBLIC_KB : ORIGIN_CONVERSATION
                ));
            }
        }
    }

    /**
     * 可见性规则：
     * 1. 公共知识库（有 sourceId 且无 conversationId）对所有用户可见；
     * 2. 对话记忆必须命中当前会话；
     * 3. 只返回匹配 userId 的对话记忆，未携带用户身份时默认拒绝会话记忆。
     */
    private boolean isVisible(IndexEntry entry, String userId, String conversationId) {
        if (isPublicKnowledge(entry)) {
            return true;
        }
        if (userId == null || userId.isEmpty() || conversationId == null || conversationId.isEmpty()) {
            return false;
        }
        if (!conversationId.equals(entry.conversationId)) {
            return false;
        }
        return userId.equals(entry.userId);
    }

    private boolean isPublicKnowledge(IndexEntry entry) {
        return entry.sourceId != null && !entry.sourceId.isEmpty()
                && (entry.conversationId == null || entry.conversationId.isEmpty());
    }

    private void recordRetrievalLog(String traceId, String userId, String conversationId,
                                    String query, List<SearchResult> results, long durationMs) {
        try {
            JSONArray array = new JSONArray();
            for (SearchResult result : results) {
                JSONObject item = new JSONObject();
                item.put("documentId", result.getDocumentId());
                item.put("sourceId", result.getSourceId());
                item.put("similarity", Math.round(result.getSimilarity() * 10000.0) / 10000.0);
                item.put("origin", result.getOrigin());
                array.add(item);
            }
            ragRetrievalLogService.record(
                    traceId,
                    userId,
                    conversationId,
                    sha256(query),
                    resolveScope(results),
                    topK,
                    similarityThreshold,
                    results.size(),
                    durationMs,
                    array.toJSONString()
            );
        } catch (Exception e) {
            logger.warn("[RAG] Failed to build retrieval log, traceId: {}, cause: {}", traceId, e.getMessage());
        }
    }

    private String resolveScope(List<SearchResult> results) {
        boolean hasPublic = false;
        boolean hasConversation = false;
        for (SearchResult result : results) {
            if (ORIGIN_PUBLIC_KB.equals(result.getOrigin())) {
                hasPublic = true;
            } else if (ORIGIN_CONVERSATION.equals(result.getOrigin())) {
                hasConversation = true;
            }
        }
        if (hasPublic && hasConversation) {
            return "mixed";
        }
        if (hasPublic) {
            return "public_only";
        }
        if (hasConversation) {
            return "conversation";
        }
        return "none";
    }

    private String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            logger.warn("[RAG] Failed to calculate SHA-256 hash: {}", e.getMessage());
            return null;
        }
    }

    private double cosineSimilarity(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private byte[] serializeVector(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (float f : vector) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    private float[] deserializeVector(byte[] bytes) {
        if (bytes == null || bytes.length % 4 != 0) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    public void clearConversationVectors(String conversationId) {
        try {
            vectorStoreRepository.deleteByConversationId(conversationId);
            loadFromSQLite();
            logger.info("Cleared vectors for conversation: {}", conversationId);
        } catch (Exception e) {
            logger.error("Failed to clear conversation vectors", e);
        }
    }

    public void clearDocumentVectors(String sourceId) {
        try {
            vectorStoreRepository.deleteBySourceId(sourceId);
            loadFromSQLite();
            logger.info("Cleared vectors for sourceId: {}", sourceId);
        } catch (Exception e) {
            logger.error("Failed to clear document vectors", e);
        }
    }

    /**
     * 列出公共知识库文档（按 sourceId 分组，跳过对话向量）。
     */
    public List<Map<String, Object>> listDocuments() {
        List<VectorStore> all = vectorStoreRepository.findAll();
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (VectorStore vs : all) {
            if (vs.getSourceId() == null || vs.getSourceId().isEmpty()
                    || (vs.getConversationId() != null && !vs.getConversationId().isEmpty())) {
                continue;
            }
            Map<String, Object> g = grouped.computeIfAbsent(vs.getSourceId(), k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("sourceId", k);
                m.put("count", 0);
                m.put("sample", "");
                m.put("timestamp", vs.getTimestamp());
                return m;
            });
            g.put("count", ((Integer) g.get("count")) + 1);
            if (((String) g.get("sample")).isEmpty()) {
                g.put("sample", vs.getContent());
            }
            if (vs.getTimestamp() != null) {
                g.put("timestamp", vs.getTimestamp());
            }
        }
        return new ArrayList<>(grouped.values());
    }

    public long countVectors() {
        return indexEntries.size();
    }

    public long countVectorsBySource(String sourceId) {
        return vectorStoreRepository.countBySourceId(sourceId);
    }

    private static class IndexEntry {
        final String documentId;
        final String content;
        final String userId;
        final String conversationId;
        final String sourceId;
        final String metadataJson;

        IndexEntry(String documentId, String content, String userId, String conversationId,
                   String sourceId, String metadataJson) {
            this.documentId = documentId;
            this.content = content;
            this.userId = userId;
            this.conversationId = conversationId;
            this.sourceId = sourceId;
            this.metadataJson = metadataJson;
        }
    }

    public static class SearchResult {
        private final String documentId;
        private final String sourceId;
        private final String content;
        private final double similarity;
        private final JSONObject metadata;
        private final String origin;

        public SearchResult(String documentId, String sourceId, String content, double similarity, JSONObject metadata) {
            this(documentId, sourceId, content, similarity, metadata, null);
        }

        public SearchResult(String documentId, String sourceId, String content, double similarity,
                            JSONObject metadata, String origin) {
            this.documentId = documentId;
            this.sourceId = sourceId;
            this.content = content;
            this.similarity = similarity;
            this.metadata = metadata;
            this.origin = origin;
        }

        public String getDocumentId() {
            return documentId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getContent() {
            return content;
        }

        public double getSimilarity() {
            return similarity;
        }

        public JSONObject getMetadata() {
            return metadata;
        }

        public String getOrigin() {
            return origin;
        }
    }
}
