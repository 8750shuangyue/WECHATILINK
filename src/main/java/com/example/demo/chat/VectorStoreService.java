package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.entity.sqlite.VectorStore;
import com.example.demo.chat.repository.sqlite.VectorStoreRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@DependsOn("vectorStoreSchemaMigration")
public class VectorStoreService {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreService.class);

    public static final String ORIGIN_PUBLIC_KB = "public_kb";
    public static final String ORIGIN_CONVERSATION = "conversation_memory";
    public static final String CHANNEL_VECTOR = "vector";
    public static final String CHANNEL_KEYWORD = "keyword";
    public static final String CHANNEL_BOTH = "both";
    private static final String LEGACY_CONVERSATION_PREFIX = "用户: ";
    private static final String LEGACY_CONVERSATION_SEPARATOR = "\n助手: ";
    private static final int MAX_CLEARED_CONVERSATION_KEYS = 10_000;

    private final VectorStoreRepository vectorStoreRepository;
    private final EmbeddingService embeddingService;
    private final RagRetrievalLogService ragRetrievalLogService;
    private final PlatformTransactionManager sqliteTransactionManager;
    private RerankService rerankService;

    @Value("${chat.vectorstore.top-k:5}")
    private int topK;

    /**
     * 重排开启时对话记忆单独预留的名额上限；重排关闭时仍沿用旧的混合 topK 行为。
     */
    @Value("${chat.vectorstore.memory-top-k:2}")
    private int memoryTopK;

    /**
     * 向量通道的准入下限。注意它不再是唯一门槛：公共知识还可以经关键词通道准入，
     * 见 {@link #keywordFloor}。排序仍然只按余弦，关键词只负责「把该进来的放进来」。
     */
    @Value("${chat.vectorstore.similarity-threshold:0.5}")
    private double similarityThreshold;

    /**
     * 关键词通道的准入门槛（IDF 加权查询覆盖率，取值 [0,1]）。
     */
    @Value("${chat.vectorstore.keyword-floor:0.45}")
    private double keywordFloor;

    /**
     * 关键词通道的总开关，也是回滚开关：设为 false 即退化为纯向量检索。
     */
    @Value("${chat.vectorstore.keyword.enabled:true}")
    private boolean keywordEnabled;

    /** 重排总开关（回滚开关）；默认关闭，灰度名单为空时开启即全量。 */
    @Value("${chat.vectorstore.rerank.enabled:false}")
    private boolean rerankEnabled;

    @Value("${chat.vectorstore.rerank.candidate-pool-size:20}")
    private int rerankCandidatePoolSize;

    @Value("${chat.vectorstore.rerank.min-candidates:3}")
    private int rerankMinCandidates;

    @Value("${chat.vectorstore.rerank.canary-users:}")
    private String rerankCanaryUsers;

    /** 可选：单测装配时不需要 Micrometer。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetricsService(com.example.demo.monitor.MetricsService metricsService) {
        this.retrievalService.setMetricsService(metricsService);
    }

    private final Set<String> clearedConversationKeys = ConcurrentHashMap.newKeySet();
    private final Object conversationMutationLock = new Object();
    private final VectorIndex index = new VectorIndex();
    private final VectorDocumentStore documentStore;
    private final RetrievalService retrievalService;

    /** 测试/旧装配兼容：没有事务模板与重排服务。 */
    public VectorStoreService(VectorStoreRepository vectorStoreRepository,
                              EmbeddingService embeddingService,
                              RagRetrievalLogService ragRetrievalLogService) {
        this(vectorStoreRepository, embeddingService, ragRetrievalLogService, null, null);
    }

    public VectorStoreService(VectorStoreRepository vectorStoreRepository,
                              EmbeddingService embeddingService,
                              RagRetrievalLogService ragRetrievalLogService,
                              PlatformTransactionManager sqliteTransactionManager) {
        this(vectorStoreRepository, embeddingService, ragRetrievalLogService,
                sqliteTransactionManager, null);
    }

    @Autowired
    public VectorStoreService(VectorStoreRepository vectorStoreRepository,
                              EmbeddingService embeddingService,
                              RagRetrievalLogService ragRetrievalLogService,
                              @Qualifier("sqliteTransactionManager")
                              PlatformTransactionManager sqliteTransactionManager,
                              RerankService rerankService) {
        this.vectorStoreRepository = vectorStoreRepository;
        this.embeddingService = embeddingService;
        this.ragRetrievalLogService = ragRetrievalLogService;
        this.sqliteTransactionManager = sqliteTransactionManager;
        this.rerankService = rerankService;
        this.documentStore = new VectorDocumentStore(vectorStoreRepository, embeddingService,
                index, clearedConversationKeys, conversationMutationLock, sqliteTransactionManager);
        this.retrievalService = new RetrievalService(embeddingService, index,
                ragRetrievalLogService, rerankService);
    }

    /** 测试注入用。 */
    void setRerankService(RerankService rerankService) {
        this.rerankService = rerankService;
        this.retrievalService.setRerankService(rerankService);
    }

    @PostConstruct
    public void init() {
        try {
            loadFromSQLite();
        } catch (Exception e) {
            logger.warn("Vector index initialization failed, will retry on first use: {}", e.getMessage());
            index.markNotReady();
        }
    }

    private void loadFromSQLite() {
        try {
            index.load(vectorStoreRepository.findAll());
        } catch (Exception e) {
            logger.error("Failed to load vector index from SQLite", e);
            index.markNotReady();
        }
    }

    public void saveMessage(String conversationId, String userMessage, String assistantReply) {
        saveMessage(null, conversationId, userMessage, assistantReply);
    }

    /**
     * 保存一轮对话的向量记忆，显式带用户身份，避免不同用户之间互相检索。
     */
    public void saveMessage(String userId, String conversationId, String userMessage, String assistantReply) {
        documentStore.saveMessage(userId, conversationId, userMessage, assistantReply);
    }


    /**
     * 将阶段 0.2 之前的 UUID 对话向量迁移为稳定 documentId，并删除同一轮对话的重复副本。
     * 只处理带 conversationId、且没有 sourceId 的对话向量，不触碰公共知识库。
     */
    @Transactional(transactionManager = "sqliteTransactionManager")
    public int cleanupDuplicateConversationVectors() {
        List<VectorStore> conversationVectors = vectorStoreRepository.findConversationVectors();
        if (conversationVectors.isEmpty()) {
            return 0;
        }

        Map<String, List<VectorStore>> groups = new LinkedHashMap<>();
        int skipped = 0;
        for (VectorStore vector : conversationVectors) {
            String stableDocumentId = stableDocumentIdForLegacyConversationVector(vector);
            if (stableDocumentId == null) {
                skipped++;
                continue;
            }
            groups.computeIfAbsent(stableDocumentId, ignored -> new ArrayList<>()).add(vector);
        }

        List<Long> duplicateIds = new ArrayList<>();
        List<VectorStore> migratedKeepers = new ArrayList<>();
        for (Map.Entry<String, List<VectorStore>> group : groups.entrySet()) {
            List<VectorStore> vectors = group.getValue();
            VectorStore keeper = selectConversationVectorKeeper(vectors, group.getKey());
            if (!group.getKey().equals(keeper.getDocumentId())) {
                keeper.setDocumentId(group.getKey());
                migratedKeepers.add(keeper);
            }
            for (VectorStore vector : vectors) {
                if (vector != keeper && vector.getId() != null) {
                    duplicateIds.add(vector.getId());
                }
            }
        }

        if (!duplicateIds.isEmpty()) {
            vectorStoreRepository.deleteAllByIdInBatch(duplicateIds);
            vectorStoreRepository.flush();
        }
        if (!migratedKeepers.isEmpty()) {
            vectorStoreRepository.saveAll(migratedKeepers);
            vectorStoreRepository.flush();
        }
        if (!duplicateIds.isEmpty() || !migratedKeepers.isEmpty()) {
            reloadIndexAfterMutation();
        }

        if (skipped > 0) {
            logger.warn("Skipped {} malformed legacy conversation vectors during duplicate cleanup", skipped);
        }
        if (!duplicateIds.isEmpty() || !migratedKeepers.isEmpty()) {
            logger.info("Cleaned duplicate conversation vectors, deleted: {}, migrated: {}",
                    duplicateIds.size(), migratedKeepers.size());
        }
        return duplicateIds.size();
    }

    private String stableDocumentIdForLegacyConversationVector(VectorStore vector) {
        String content = vector.getContent();
        String conversationId = vector.getConversationId();
        if (content == null || conversationId == null || conversationId.isBlank()
                || !content.startsWith(LEGACY_CONVERSATION_PREFIX)) {
            return null;
        }

        int separatorIndex = content.indexOf(
                LEGACY_CONVERSATION_SEPARATOR, LEGACY_CONVERSATION_PREFIX.length());
        if (separatorIndex < 0) {
            return null;
        }

        String userMessage = content.substring(LEGACY_CONVERSATION_PREFIX.length(), separatorIndex);
        String assistantReply = content.substring(
                separatorIndex + LEGACY_CONVERSATION_SEPARATOR.length());
        if (assistantReply.isEmpty()) {
            return null;
        }
        return VectorDocuments.messageDocumentId(vector.getUserId(), conversationId, userMessage, assistantReply);
    }

    private VectorStore selectConversationVectorKeeper(List<VectorStore> vectors, String stableDocumentId) {
        for (VectorStore vector : vectors) {
            if (stableDocumentId.equals(vector.getDocumentId())) {
                return vector;
            }
        }

        Comparator<VectorStore> order = Comparator
                .comparing(VectorStore::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VectorStore::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        return vectors.stream().min(order).orElseThrow();
    }

    @Transactional(transactionManager = "sqliteTransactionManager")
    public int deleteExpiredConversationVectors(LocalDateTime cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff must not be null");
        }

        int deleted = vectorStoreRepository.deleteExpiredConversationVectors(cutoff);
        if (deleted > 0) {
            reloadIndexAfterMutation();
            logger.info("Deleted {} expired conversation vectors before {}", deleted, cutoff);
        }
        return deleted;
    }

    private void reloadIndexAfterMutation() {
        loadFromSQLite();
        if (!index.isReady()) {
            throw new IllegalStateException("vector index reload failed after data mutation");
        }
    }


    public void saveDocument(String sourceId, String content, Map<String, Object> metadata) {
        documentStore.saveDocument(sourceId, content, metadata);
    }

    /**
     * 以 sourceId 为单位整体替换公共知识向量，避免同一文档重复上传后追加旧片段。
     */
    /**
     * 以 sourceId 为单位整体替换公共知识向量。
     *
     * <p>Embedding 在事务外批量完成；只有 SQLite 写入和索引重载放在短事务里。
     * embedding 失败时旧向量完全不变。</p>
     */
    public int replaceDocument(String sourceId, List<String> chunks, Map<String, Object> metadata) {
        return documentStore.replaceDocument(sourceId, chunks, metadata);
    }

    public synchronized void addToIndex(int rowId, String content, float[] vector) {
        index.add(rowId, content, vector, null, null, null, null, null);
    }

    public void addToIndex(int rowId, String content, float[] vector,
                           String documentId, String userId, String conversationId,
                           String sourceId, String metadataJson) {
        index.add(rowId, content, vector, documentId, userId, conversationId, sourceId, metadataJson);
    }

    public boolean isIndexReady() {
        return index.isReady();
    }

    public List<String> searchSimilar(String query) {
        return searchSimilar(query, null, null);
    }

    public List<String> searchSimilar(String query, String conversationId) {
        return searchSimilar(query, null, conversationId);
    }

    public List<String> searchSimilar(String query, String userId, String conversationId) {
        List<SearchResult> results = searchSimilarWithMetadataAndTrace(query, userId, conversationId).results();
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
        return searchSimilarWithMetadataAndTrace(query, userId, conversationId).results();
    }

    public SearchOutcome searchSimilarWithMetadataAndTrace(
            String query, String userId, String conversationId) {
        return retrievalService.search(query, userId, conversationId, retrievalConfig());
    }

    private RetrievalConfig retrievalConfig() {
        return new RetrievalConfig(topK, memoryTopK, similarityThreshold, keywordFloor, keywordEnabled,
                rerankEnabled, rerankCandidatePoolSize, rerankMinCandidates, rerankCanaryUsers);
    }




    @Transactional(transactionManager = "sqliteTransactionManager")
    public void clearConversationVectors(String userId, String conversationId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }

        String conversationKey = VectorDocuments.conversationKey(userId, conversationId);
        synchronized (conversationMutationLock) {
            boolean newlyMarked = clearedConversationKeys.add(conversationKey);
            if (clearedConversationKeys.size() > MAX_CLEARED_CONVERSATION_KEYS) {
                clearedConversationKeys.clear();
                clearedConversationKeys.add(conversationKey);
                newlyMarked = true;
            }
            try {
                vectorStoreRepository.deleteByUserIdAndConversationId(userId, conversationId);
                loadFromSQLite();
                logger.info("Cleared vectors for user: {}, conversation: {}", userId, conversationId);
            } catch (Exception e) {
                if (newlyMarked) {
                    clearedConversationKeys.remove(conversationKey);
                }
                throw e;
            }
        }
    }


    @Transactional(transactionManager = "sqliteTransactionManager")
    public void clearDocumentVectors(String sourceId) {
        try {
            clearDocumentVectorsStrict(sourceId);
            logger.info("Cleared vectors for sourceId: {}", sourceId);
        } catch (Exception e) {
            logger.error("Failed to clear document vectors", e);
        }
    }

    /**
     * 严格清理公共知识向量，供业务删除链路使用；清理失败会直接抛出并触发上游事务回滚。
     */
    @Transactional(transactionManager = "sqliteTransactionManager")
    public void clearDocumentVectorsStrict(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }

        vectorStoreRepository.deleteBySourceId(sourceId);
        vectorStoreRepository.flush();
        removeSourceFromIndex(sourceId);
        logger.info("Strictly cleared vectors for sourceId: {}", sourceId);
    }

    public List<String> listPublicSourceIds() {
        return vectorStoreRepository.findDistinctPublicSourceIds();
    }

    private void removeSourceFromIndex(String sourceId) {
        index.removeBySource(sourceId);
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
                m.put("fileName", resolveDocumentFileName(vs, k));
                m.put("title", resolveDocumentTitle(vs, k));
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

    private String resolveDocumentTitle(VectorStore vectorStore, String sourceId) {
        if (vectorStore.getMetadataJson() == null || vectorStore.getMetadataJson().isBlank()) {
            return sourceId;
        }
        try {
            JSONObject metadata = JSON.parseObject(vectorStore.getMetadataJson());
            String title = metadata.getString("title");
            if (title != null && !title.isBlank()) {
                return title;
            }
            String fileName = metadata.getString("fileName");
            if (fileName != null && !fileName.isBlank()) {
                String stripped = fileName.replaceFirst("(?i)\\.(md|markdown)$", "").trim();
                return stripped.isEmpty() ? sourceId : stripped;
            }
        } catch (Exception e) {
            logger.debug("Failed to parse vector metadata for sourceId: {}, cause: {}",
                    sourceId, e.getMessage());
        }
        return sourceId;
    }

    private String resolveDocumentFileName(VectorStore vectorStore, String sourceId) {
        if (vectorStore.getMetadataJson() == null || vectorStore.getMetadataJson().isBlank()) {
            return sourceId;
        }
        try {
            JSONObject metadata = JSON.parseObject(vectorStore.getMetadataJson());
            String fileName = metadata.getString("fileName");
            if (fileName != null && !fileName.isBlank()) {
                return fileName;
            }
            String title = metadata.getString("title");
            if (title != null && !title.isBlank()) {
                return title;
            }
        } catch (Exception e) {
            logger.debug("Failed to parse vector metadata for sourceId: {}, cause: {}",
                    sourceId, e.getMessage());
        }
        return sourceId;
    }

    public long countVectors() {
        return index.size();
    }

    public long countVectorsBySource(String sourceId) {
        return vectorStoreRepository.countBySourceId(sourceId);
    }


    public static class SearchResult {
        private final String documentId;
        private final String sourceId;
        private final String content;
        private final double similarity;
        private final JSONObject metadata;
        private final String origin;
        private final double keywordScore;
        private final String channel;
        /** 最终返回顺序（1 开始）。 */
        private final int rank;
        /** 重排前的余弦顺序（1 开始）。 */
        private final int cosineRank;
        /** 重排分数；未重排时为 -1，落盘时省略。 */
        private final double rerankScore;

        public SearchResult(String documentId, String sourceId, String content, double similarity, JSONObject metadata) {
            this(documentId, sourceId, content, similarity, metadata, null, 0.0, null, 0, 0, -1);
        }

        public SearchResult(String documentId, String sourceId, String content, double similarity,
                            JSONObject metadata, String origin) {
            this(documentId, sourceId, content, similarity, metadata, origin, 0.0, null, 0, 0, -1);
        }

        public SearchResult(String documentId, String sourceId, String content, double similarity,
                            JSONObject metadata, String origin, double keywordScore, String channel) {
            this(documentId, sourceId, content, similarity, metadata, origin, keywordScore, channel, 0, 0, -1);
        }

        public SearchResult(String documentId, String sourceId, String content, double similarity,
                            JSONObject metadata, String origin, double keywordScore, String channel,
                            int rank, int cosineRank, double rerankScore) {
            this.documentId = documentId;
            this.sourceId = sourceId;
            this.content = content;
            this.similarity = similarity;
            this.metadata = metadata;
            this.origin = origin;
            this.keywordScore = keywordScore;
            this.channel = channel;
            this.rank = rank;
            this.cosineRank = cosineRank;
            this.rerankScore = rerankScore;
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

        public double getKeywordScore() {
            return keywordScore;
        }

        public String getChannel() {
            return channel;
        }

        public int getRank() {
            return rank;
        }

        public int getCosineRank() {
            return cosineRank;
        }

        public double getRerankScore() {
            return rerankScore;
        }

        public SearchResult withRanking(int newRank, int newCosineRank, double newRerankScore) {
            return new SearchResult(documentId, sourceId, content, similarity, metadata, origin,
                    keywordScore, channel, newRank, newCosineRank, newRerankScore);
        }

        public SearchResult withContent(String newContent) {
            return new SearchResult(documentId, sourceId, newContent, similarity, metadata, origin,
                    keywordScore, channel, rank, cosineRank, rerankScore);
        }
    }

    public record SearchOutcome(String traceId, List<SearchResult> results,
                                RetrievalTraceStats stats) {
        public SearchOutcome {
            results = results == null ? List.of() : List.copyOf(results);
        }

        public SearchOutcome(String traceId, List<SearchResult> results) {
            this(traceId, results, null);
        }
    }
}
