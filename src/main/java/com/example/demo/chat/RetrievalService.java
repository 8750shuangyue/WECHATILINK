package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.monitor.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 检索编排：查询向量、余弦/关键词准入、重排选择与检索日志。 */
final class RetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingService embeddingService;
    private final VectorIndex index;
    private final RagRetrievalLogService ragRetrievalLogService;
    private volatile RerankService rerankService;
    private volatile MetricsService metricsService;

    RetrievalService(EmbeddingService embeddingService, VectorIndex index,
                     RagRetrievalLogService ragRetrievalLogService, RerankService rerankService) {
        this.embeddingService = embeddingService;
        this.index = index;
        this.ragRetrievalLogService = ragRetrievalLogService;
        this.rerankService = rerankService;
    }

    void setRerankService(RerankService rerankService) {
        this.rerankService = rerankService;
    }

    void setMetricsService(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    VectorStoreService.SearchOutcome search(String query, String userId, String conversationId, RetrievalConfig config) {
        long startNanos = System.nanoTime();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        List<VectorStoreService.SearchResult> results = new ArrayList<>();
        int admittedCount = 0;
        RetrievalTraceStats stats = baseStats(0, 0, config);
        try {
            if (!index.isReady()) {
                logger.warn("[RAG] Vector index not ready, traceId: {}", traceId);
            } else if (query == null || query.isBlank()) {
                logger.debug("[RAG] Empty query, traceId: {}", traceId);
            } else {
                float[] queryEmbedding = embeddingService.embed(query);
                if (queryEmbedding.length == 0) {
                    logger.warn("[RAG] Query embedding is empty, traceId: {}", traceId);
                } else {
                    collectSimilar(queryEmbedding, query, userId, conversationId, results, config);
                }
            }

            results = sortByCosine(results);
            admittedCount = results.size();
            stats.admittedCount(admittedCount);
            results = selectResults(query, userId, results, stats, config);
            stats.returnedCount(results.size());
            logger.info("[RAG] traceId={}, userId={}, conversationId={}, admitted={}, retrieved={}, topK={}, reranked={}",
                    traceId, userId, conversationId, admittedCount, results.size(), config.topK(),
                    Boolean.TRUE.equals(stats.toJson().getBoolean("rerankApplied")));
        } catch (Exception e) {
            logger.error("[RAG] Vector search failed, traceId: {}", traceId, e);
            results = new ArrayList<>();
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            stats.returnedCount(results.size());
            recordRetrievalLog(traceId, userId, conversationId, query, results, stats, durationMs, config);
            if (metricsService != null) {
                metricsService.recordRetrieval(durationMs,
                        Boolean.TRUE.equals(stats.toJson().getBoolean("rerankApplied")));
            }
        }
        return new VectorStoreService.SearchOutcome(traceId, List.copyOf(results), stats);
    }

    /**
     * 选择最终返回结果：
     * <ul>
     *   <li>重排关闭/未装配/灰度未命中 —— 与旧行为完全一致的混合余弦 config.topK()；</li>
     *   <li>重排开启 —— 公共知识池重排取 config.topK()，对话记忆单独预留最多 config.memoryTopK() 条；</li>
     *   <li>任何重排失败 —— 回退到旧的混合余弦 config.topK()。</li>
     * </ul>
     */
    private List<VectorStoreService.SearchResult> selectResults(String query, String userId,
                                             List<VectorStoreService.SearchResult> admitted,
                                             RetrievalTraceStats stats, RetrievalConfig config) {
        stats.rerankEnabled(config.rerankEnabled());
        if (rerankService == null || !config.rerankEnabled()) {
            stats.rerankApplied(false);
            return cosineTopK(admitted, config);
        }
        if (!FeatureRollout.includes(userId, config.rerankCanaryUsers())) {
            stats.rerankApplied(false).rerankFallbackReason("canary_excluded");
            return cosineTopK(admitted, config);
        }
        return rerankAndSelect(query, admitted, stats, config);
    }

    private List<VectorStoreService.SearchResult> rerankAndSelect(String query, List<VectorStoreService.SearchResult> admitted,
                                               RetrievalTraceStats stats, RetrievalConfig config) {
        Map<VectorStoreService.SearchResult, Integer> cosineRankByResult = new java.util.IdentityHashMap<>();
        List<VectorStoreService.SearchResult> publicCandidates = new ArrayList<>();
        List<VectorStoreService.SearchResult> memoryCandidates = new ArrayList<>();
        for (int i = 0; i < admitted.size(); i++) {
            VectorStoreService.SearchResult result = admitted.get(i);
            cosineRankByResult.put(result, i + 1);
            if (VectorStoreService.ORIGIN_PUBLIC_KB.equals(result.getOrigin())) {
                publicCandidates.add(result);
            } else {
                memoryCandidates.add(result);
            }
        }

        int poolSize = Math.min(Math.max(0, config.rerankCandidatePoolSize()), publicCandidates.size());
        List<VectorStoreService.SearchResult> pool = new ArrayList<>(publicCandidates.subList(0, poolSize));
        stats.rerankPoolSize(config.rerankCandidatePoolSize())
                .rerankCandidateCount(pool.size())
                .rerankTimeoutMs(rerankService.getTimeoutMs());

        if (pool.size() < config.rerankMinCandidates()) {
            stats.rerankApplied(false).rerankFallbackReason("pool_too_small");
            return cosineTopK(admitted, config);
        }

        List<String> documents = new ArrayList<>(pool.size());
        for (VectorStoreService.SearchResult candidate : pool) {
            documents.add(candidate.getContent());
        }
        RerankService.RerankOutcome outcome = rerankService.rerank(query, documents);
        stats.rerankModel(outcome.model()).rerankDurationMs(outcome.durationMs());
        if (!outcome.applied()) {
            stats.rerankApplied(false).rerankFallbackReason(outcome.fallbackReason());
            return cosineTopK(admitted, config);
        }

        List<Double> scores = outcome.scoresByIndex();
        List<Integer> order = new ArrayList<>(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer index) -> scores.get(index)).reversed());

        int publicLimit = Math.max(0, config.topK());
        List<VectorStoreService.SearchResult> selectedPublic = new ArrayList<>(Math.min(publicLimit, order.size()));
        for (int i = 0; i < order.size() && selectedPublic.size() < publicLimit; i++) {
            int index = order.get(i);
            VectorStoreService.SearchResult candidate = pool.get(index);
            selectedPublic.add(candidate.withRanking(
                    0,
                    cosineRankByResult.getOrDefault(candidate, 0),
                    scores.get(index)));
        }

        int memoryLimit = selectedPublic.isEmpty()
                ? Math.min(Math.max(0, config.topK()), memoryCandidates.size())
                : Math.min(Math.max(0, config.memoryTopK()), memoryCandidates.size());
        List<VectorStoreService.SearchResult> finalResults = new ArrayList<>(selectedPublic);
        if (memoryLimit > 0) {
            finalResults.addAll(memoryCandidates.subList(0, memoryLimit));
        }

        List<VectorStoreService.SearchResult> ranked = new ArrayList<>(finalResults.size());
        for (int i = 0; i < finalResults.size(); i++) {
            VectorStoreService.SearchResult result = finalResults.get(i);
            ranked.add(result.withRanking(i + 1, result.getCosineRank(), result.getRerankScore()));
        }
        stats.rerankApplied(true);
        return ranked;
    }

    /** 与旧行为完全一致：混合余弦排序后截断 config.topK()。 */
    private List<VectorStoreService.SearchResult> cosineTopK(List<VectorStoreService.SearchResult> admitted, RetrievalConfig config) {
        List<VectorStoreService.SearchResult> truncated = admitted.size() > config.topK()
                ? new ArrayList<>(admitted.subList(0, config.topK()))
                : new ArrayList<>(admitted);
        List<VectorStoreService.SearchResult> ranked = new ArrayList<>(truncated.size());
        for (int i = 0; i < truncated.size(); i++) {
            ranked.add(truncated.get(i).withRanking(i + 1, i + 1, -1));
        }
        return ranked;
    }

    /**
     * 余弦降序；documentId 作为稳定的次级键，避免 Map 遍历顺序导致同分结果漂移。
     * 重排关闭时这就是最终顺序。
     */
    private static List<VectorStoreService.SearchResult> sortByCosine(List<VectorStoreService.SearchResult> results) {
        List<VectorStoreService.SearchResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator
                .comparingDouble(VectorStoreService.SearchResult::getSimilarity).reversed()
                .thenComparing(VectorStoreService.SearchResult::getDocumentId,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    private RetrievalTraceStats baseStats(int admittedCount, int returnedCount, RetrievalConfig config) {
        return RetrievalTraceStats.create()
                .admittedCount(admittedCount)
                .returnedCount(returnedCount)
                .topK(config.topK())
                .memoryTopK(config.memoryTopK())
                .similarityThreshold(config.similarityThreshold())
                .keywordFloor(config.keywordFloor())
                .keywordEnabled(config.keywordEnabled());
    }

    /**
     * 双通道准入：
     * <ul>
     *   <li>向量通道——公共知识与对话记忆都走，门槛 {@link #config.similarityThreshold()}；</li>
     *   <li>关键词通道——<b>只对公共知识开放</b>，门槛 {@link #config.keywordFloor()}。</li>
     * </ul>
     * 对话记忆刻意不接关键词通道：同一会话的旧轮次与当前问题词面天然高度重合，
     * 接进来会把历史对话大量灌入上下文。
     *
     * <p>两条通道只决定「谁有资格进来」，排序仍然只按余弦，
     * 所以低余弦的关键词命中会排在后面，由 config.topK() 自然截断。
     */
    private void collectSimilar(float[] queryEmbedding, String query, String userId,
                                String conversationId, List<VectorStoreService.SearchResult> results,
                                RetrievalConfig config) {
        Set<String> queryTokens = config.keywordEnabled() ? RetrievalScorer.tokens(query) : Set.of();
        Map<String, Double> idf = resolvePublicKnowledgeIdf(config);

        for (VectorIndex.IndexedVector indexed : index.snapshot()) {
            IndexEntry indexEntry = indexed.entry();
            float[] storedVector = indexed.vector();
            if (indexEntry == null || !isVisible(indexEntry, userId, conversationId)) {
                continue;
            }
            if (storedVector == null || storedVector.length != queryEmbedding.length) {
                continue;
            }
            double similarity = cosineSimilarity(queryEmbedding, storedVector);
            boolean publicKnowledge = indexEntry.isPublicKnowledge();
            double keywordScore = publicKnowledge && config.keywordEnabled()
                    ? RetrievalScorer.keywordScore(queryTokens, indexEntry.tokens, idf)
                    : 0.0;

            if (!RetrievalScorer.admits(similarity, keywordScore, publicKnowledge,
                    config.keywordEnabled(), config.similarityThreshold(), config.keywordFloor())) {
                continue;
            }
            boolean admittedByVector = similarity >= config.similarityThreshold();
            boolean admittedByKeyword = publicKnowledge && config.keywordEnabled()
                    && keywordScore >= config.keywordFloor();

            JSONObject metadata = null;
            if (indexEntry.metadataJson != null && !indexEntry.metadataJson.isEmpty()) {
                metadata = JSON.parseObject(indexEntry.metadataJson);
            }
            results.add(new VectorStoreService.SearchResult(
                    indexEntry.documentId,
                    indexEntry.sourceId,
                    indexEntry.content,
                    similarity,
                    metadata,
                    publicKnowledge ? VectorStoreService.ORIGIN_PUBLIC_KB : VectorStoreService.ORIGIN_CONVERSATION,
                    keywordScore,
                    resolveChannel(admittedByVector, admittedByKeyword)
            ));
        }
    }

    /**
     * IDF 只按公共知识块统计：对话记忆是每用户私有的短文本，
     * 纳入统计既没有意义，也会随本地测试数据膨胀而漂移。
     * 公共块只有二十来个，每次检索现算的开销远小于向量扫描本身。
     */
    private Map<String, Double> resolvePublicKnowledgeIdf(RetrievalConfig config) {
        if (!config.keywordEnabled()) {
            return Map.of();
        }
        List<Set<String>> publicTokens = new ArrayList<>();
        for (IndexEntry entry : index.publicEntries()) {
            publicTokens.add(entry.tokens);
        }
        return RetrievalScorer.idf(publicTokens);
    }

    private static String resolveChannel(boolean admittedByVector, boolean admittedByKeyword) {
        if (admittedByVector && admittedByKeyword) {
            return VectorStoreService.CHANNEL_BOTH;
        }
        return admittedByKeyword ? VectorStoreService.CHANNEL_KEYWORD : VectorStoreService.CHANNEL_VECTOR;
    }

    /**
     * 可见性规则：
     * 1. 公共知识库（有 sourceId 且无 conversationId）对所有用户可见；
     * 2. 对话记忆必须命中当前会话；
     * 3. 只返回匹配 userId 的对话记忆，未携带用户身份时默认拒绝会话记忆。
     */
    private boolean isVisible(IndexEntry entry, String userId, String conversationId) {
        if (entry.isPublicKnowledge()) {
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


    private void recordRetrievalLog(String traceId, String userId, String conversationId,
                                    String query, List<VectorStoreService.SearchResult> results,
                                    RetrievalTraceStats stats, long durationMs, RetrievalConfig config) {
        try {
            JSONArray array = new JSONArray();
            for (VectorStoreService.SearchResult result : results) {
                JSONObject item = new JSONObject();
                item.put("documentId", result.getDocumentId());
                item.put("sourceId", result.getSourceId());
                item.put("similarity", Math.round(result.getSimilarity() * 10000.0) / 10000.0);
                item.put("origin", result.getOrigin());
                item.put("keywordScore", Math.round(result.getKeywordScore() * 10000.0) / 10000.0);
                item.put("channel", result.getChannel());
                item.put("rank", result.getRank());
                item.put("cosineRank", result.getCosineRank());
                if (result.getRerankScore() >= 0) {
                    item.put("rerankScore", Math.round(result.getRerankScore() * 10000.0) / 10000.0);
                }
                array.add(item);
            }
            ragRetrievalLogService.record(
                    traceId,
                    userId,
                    conversationId,
                    VectorDocuments.sha256(query),
                    resolveScope(results),
                    config.topK(),
                    config.similarityThreshold(),
                    results.size(),
                    durationMs,
                    array.toJSONString(),
                    stats == null ? null : stats.toJson().toJSONString()
            );
        } catch (Exception e) {
            logger.warn("[RAG] Failed to build retrieval log, traceId: {}, cause: {}", traceId, e.getMessage());
        }
    }

    private String resolveScope(List<VectorStoreService.SearchResult> results) {
        boolean hasPublic = false;
        boolean hasConversation = false;
        for (VectorStoreService.SearchResult result : results) {
            if (VectorStoreService.ORIGIN_PUBLIC_KB.equals(result.getOrigin())) {
                hasPublic = true;
            } else if (VectorStoreService.ORIGIN_CONVERSATION.equals(result.getOrigin())) {
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
}
