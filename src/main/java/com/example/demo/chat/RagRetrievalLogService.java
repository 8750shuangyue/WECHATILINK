package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.entity.sqlite.RagRetrievalLog;
import com.example.demo.chat.repository.sqlite.RagRetrievalLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RagRetrievalLogService {

    private static final Logger logger = LoggerFactory.getLogger(RagRetrievalLogService.class);

    private final RagRetrievalLogRepository repository;

    public RagRetrievalLogService(RagRetrievalLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一次检索。日志失败只告警，不能影响主回答链路。
     */
    public void record(String traceId,
                       String userId,
                       String conversationId,
                       String queryHash,
                       String scope,
                       int topK,
                       double similarityThreshold,
                       int retrievedCount,
                       long durationMs,
                       String resultsJson) {
        record(traceId, userId, conversationId, queryHash, scope, topK,
                similarityThreshold, retrievedCount, durationMs, resultsJson, null);
    }

    public void record(String traceId,
                       String userId,
                       String conversationId,
                       String queryHash,
                       String scope,
                       int topK,
                       double similarityThreshold,
                       int retrievedCount,
                       long durationMs,
                       String resultsJson,
                       String statsJson) {
        try {
            RagRetrievalLog log = new RagRetrievalLog();
            log.setTraceId(traceId);
            log.setUserId(userId);
            log.setConversationId(conversationId);
            log.setQueryHash(queryHash);
            log.setScope(scope);
            log.setTopK(topK);
            log.setSimilarityThreshold(similarityThreshold);
            log.setRetrievedCount(retrievedCount);
            log.setDurationMs(durationMs);
            log.setResultsJson(resultsJson);
            log.setStatsJson(statsJson);
            repository.save(log);
        } catch (Exception e) {
            logger.warn("[RAG] Failed to persist retrieval log, traceId: {}, cause: {}", traceId, e.getMessage());
        }
    }

    /**
     * 上下文压缩发生在检索日志落盘之后，这里把压缩统计合并进已有的 stats_json。
     * 失败只告警，不能影响回答链路。
     */
    public void updateCompressionStats(String traceId, RetrievalTraceStats stats) {
        if (traceId == null || traceId.isBlank() || stats == null) {
            return;
        }
        try {
            RagRetrievalLog log = repository.findByTraceId(traceId).orElse(null);
            if (log == null) {
                return;
            }
            JSONObject merged = new JSONObject();
            String existing = log.getStatsJson();
            if (existing != null && !existing.isBlank()) {
                JSONObject parsed = JSON.parseObject(existing);
                if (parsed != null) {
                    merged.putAll(parsed);
                }
            }
            merged.putAll(stats.toJson());
            log.setStatsJson(merged.toJSONString());
            repository.save(log);
        } catch (Exception e) {
            logger.warn("[RAG] Failed to update compression stats, traceId: {}, cause: {}",
                    traceId, e.getMessage());
        }
    }
}
