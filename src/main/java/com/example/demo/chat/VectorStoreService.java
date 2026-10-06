package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.entity.sqlite.VectorStore;
import com.example.demo.chat.repository.sqlite.VectorStoreRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class VectorStoreService {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreService.class);

    private final VectorStoreRepository vectorStoreRepository;
    private final EmbeddingService embeddingService;

    @Value("${chat.vectorstore.top-k:5}")
    private int topK;

    @Value("${chat.vectorstore.similarity-threshold:0.5}")
    private double similarityThreshold;

    private final List<float[]> vectorIndex = new ArrayList<>();
    private final Map<Integer, String> indexToContent = new ConcurrentHashMap<>();
    private final AtomicBoolean indexReady = new AtomicBoolean(false);
    private final ReadWriteLock indexLock = new ReentrantReadWriteLock();

    public VectorStoreService(VectorStoreRepository vectorStoreRepository, EmbeddingService embeddingService) {
        this.vectorStoreRepository = vectorStoreRepository;
        this.embeddingService = embeddingService;
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
            for (VectorStore vs : allVectors) {
                float[] vector = deserializeVector(vs.getVector());
                if (vector.length > 0) {
                    int idx = vs.getId().intValue();
                    while (vectorIndex.size() <= idx) {
                        vectorIndex.add(null);
                    }
                    vectorIndex.set(idx, vector);
                    indexToContent.put(idx, vs.getContent());
                }
            }
            indexReady.set(true);
            logger.info("Vector index loaded with {} vectors", indexToContent.size());
        } catch (Exception e) {
            logger.error("Failed to load vector index from SQLite", e);
            indexReady.set(false);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    public void saveMessage(String conversationId, String userMessage, String assistantReply) {
        try {
            logger.info("[VectorStore] saveMessage start, conversationId: {}, content length: {}", conversationId, (userMessage + assistantReply).length());
            String combinedContent = "用户: " + userMessage + "\n助手: " + assistantReply;
            logger.info("[VectorStore] Calling embed() for content: {} chars", combinedContent.length());
            float[] embedding = embeddingService.embed(combinedContent);
            logger.info("[VectorStore] embed() returned, vector dim: {}", embedding.length);

            if (embedding.length == 0) {
                logger.warn("[VectorStore] Embedding is empty, skipping save");
                return;
            }

            String docId = UUID.randomUUID().toString();
            byte[] vectorBytes = serializeVector(embedding);
            logger.info("[VectorStore] Serialized vector to {} bytes", vectorBytes.length);

            VectorStore vs = new VectorStore(docId, combinedContent, vectorBytes);
            vs.setConversationId(conversationId);
            VectorStore saved = vectorStoreRepository.save(vs);
            logger.info("[VectorStore] Saved to DB, id: {}", saved.getId());

            if (saved.getId() != null) {
                addToIndex(saved.getId().intValue(), combinedContent, embedding);
                logger.info("[VectorStore] Index updated for rowId: {}, vector dim: {}", saved.getId(), embedding.length);
            }

        } catch (Exception e) {
            logger.error("[VectorStore] Failed to save vector, cause: {}", e.getMessage(), e);
        }
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
                addToIndex(saved.getId().intValue(), content, embedding);
                logger.info("Saved document vector, sourceId: {}, docId: {}, rowId: {}, vector dim: {}", 
                    sourceId, docId, saved.getId(), embedding.length);
            }

        } catch (Exception e) {
            logger.error("Failed to save document vector to SQLite", e);
        }
    }

    public synchronized void addToIndex(int rowId, String content, float[] vector) {
        indexLock.writeLock().lock();
        try {
            while (vectorIndex.size() <= rowId) {
                vectorIndex.add(null);
            }
            vectorIndex.set(rowId, vector);
            indexToContent.put(rowId, content);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    public List<String> searchSimilar(String query) {
        return searchSimilar(query, null);
    }

    public List<String> searchSimilar(String query, String conversationId) {
        indexLock.readLock().lock();
        try {
            if (!indexReady.get()) {
                logger.warn("Vector index not ready");
                return List.of();
            }

            float[] queryEmbedding = embeddingService.embed(query);
            if (queryEmbedding.length == 0) {
                logger.warn("Query embedding is empty");
                return List.of();
            }

            List<SimilarityResult> similarityResults = new ArrayList<>();

            for (int i = 0; i < vectorIndex.size(); i++) {
                float[] storedVector = vectorIndex.get(i);
                if (storedVector == null) {
                    continue;
                }

                if (storedVector.length != queryEmbedding.length) {
                    continue;
                }

                double similarity = cosineSimilarity(queryEmbedding, storedVector);
                if (similarity >= similarityThreshold) {
                    similarityResults.add(new SimilarityResult(similarity, indexToContent.get(i)));
                }
            }

            similarityResults.sort((a, b) -> Double.compare(b.similarity, a.similarity));

            List<String> results = new ArrayList<>();
            int count = 0;
            for (SimilarityResult sr : similarityResults) {
                if (count >= topK) {
                    break;
                }
                results.add(sr.content);
                count++;
            }

            logger.info("Search found {} results (top {} requested)", results.size(), topK);
            return results;

        } catch (Exception e) {
            logger.error("Failed to search vector", e);
            return List.of();
        } finally {
            indexLock.readLock().unlock();
        }
    }

    public List<SearchResult> searchSimilarWithMetadata(String query) {
        return searchSimilarWithMetadata(query, null);
    }

    public List<SearchResult> searchSimilarWithMetadata(String query, String conversationId) {
        indexLock.readLock().lock();
        try {
            if (!indexReady.get()) {
                logger.warn("Vector index not ready");
                return List.of();
            }

            float[] queryEmbedding = embeddingService.embed(query);
            if (queryEmbedding.length == 0) {
                logger.warn("Query embedding is empty");
                return List.of();
            }

            List<VectorStore> allVectors;
            if (conversationId != null && !conversationId.isEmpty()) {
                allVectors = vectorStoreRepository.findByConversationId(conversationId);
            } else {
                allVectors = vectorStoreRepository.findAll();
            }

            List<SearchResult> similarityResults = new ArrayList<>();
            for (VectorStore vs : allVectors) {
                float[] storedVector = deserializeVector(vs.getVector());
                if (storedVector.length != queryEmbedding.length) {
                    continue;
                }

                double similarity = cosineSimilarity(queryEmbedding, storedVector);
                if (similarity >= similarityThreshold) {
                    JSONObject metadata = null;
                    if (vs.getMetadataJson() != null && !vs.getMetadataJson().isEmpty()) {
                        metadata = JSON.parseObject(vs.getMetadataJson());
                    }
                    similarityResults.add(new SearchResult(
                        vs.getDocumentId(),
                        vs.getSourceId(),
                        vs.getContent(),
                        similarity,
                        metadata
                    ));
                }
            }

            similarityResults.sort((a, b) -> Double.compare(b.similarity, a.similarity));

            List<SearchResult> results = new ArrayList<>();
            int count = 0;
            for (SearchResult sr : similarityResults) {
                if (count >= topK) {
                    break;
                }
                results.add(sr);
                count++;
            }

            logger.info("Search found {} results with metadata (top {} requested)", results.size(), topK);
            return results;

        } catch (Exception e) {
            logger.error("Failed to search vector with metadata", e);
            return List.of();
        } finally {
            indexLock.readLock().unlock();
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
     * 列出知识库文档（按 sourceId 分组，仅统计显式入库的文档，跳过对话向量）。
     */
    public List<Map<String, Object>> listDocuments() {
        List<VectorStore> all = vectorStoreRepository.findAll();
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (VectorStore vs : all) {
            if (vs.getSourceId() == null || vs.getSourceId().isEmpty()) {
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
        return indexToContent.size();
    }

    public long countVectorsBySource(String sourceId) {
        return vectorStoreRepository.countBySourceId(sourceId);
    }

    private static class SimilarityResult {
        final double similarity;
        final String content;

        SimilarityResult(double similarity, String content) {
            this.similarity = similarity;
            this.content = content;
        }
    }

    public static class SearchResult {
        private final String documentId;
        private final String sourceId;
        private final String content;
        private final double similarity;
        private final JSONObject metadata;

        public SearchResult(String documentId, String sourceId, String content, double similarity, JSONObject metadata) {
            this.documentId = documentId;
            this.sourceId = sourceId;
            this.content = content;
            this.similarity = similarity;
            this.metadata = metadata;
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
    }
}
