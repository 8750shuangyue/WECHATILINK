package com.example.demo.chat;

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
            repository.save(log);
        } catch (Exception e) {
            logger.warn("[RAG] Failed to persist retrieval log, traceId: {}, cause: {}", traceId, e.getMessage());
        }
    }
}
