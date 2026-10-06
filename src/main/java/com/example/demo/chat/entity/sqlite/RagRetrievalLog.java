package com.example.demo.chat.entity.sqlite;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 一次 RAG 检索的可审计记录。
 * 只保存文档标识、来源、相似度和耗时，不保存命中的完整正文。
 */
@Entity
@Table(name = "rag_retrieval_log", indexes = {
    @Index(name = "idx_rag_log_trace", columnList = "trace_id"),
    @Index(name = "idx_rag_log_user", columnList = "user_id"),
    @Index(name = "idx_rag_log_conversation", columnList = "conversation_id"),
    @Index(name = "idx_rag_log_created", columnList = "created_at")
})
public class RagRetrievalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "conversation_id", length = 255)
    private String conversationId;

    /** 查询文本的 SHA-256 摘要，避免明文落盘。 */
    @Column(name = "query_hash", length = 64)
    private String queryHash;

    /** public_only / conversation / mixed / none */
    @Column(name = "scope", length = 32)
    private String scope;

    @Column(name = "top_k")
    private Integer topK;

    @Column(name = "similarity_threshold")
    private Double similarityThreshold;

    @Column(name = "retrieved_count")
    private Integer retrievedCount;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** JSON 数组，仅包含 documentId/sourceId/similarity/origin。 */
    @Column(name = "results_json", columnDefinition = "TEXT")
    private String resultsJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public RagRetrievalLog() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getQueryHash() {
        return queryHash;
    }

    public void setQueryHash(String queryHash) {
        this.queryHash = queryHash;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(Double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public Integer getRetrievedCount() {
        return retrievedCount;
    }

    public void setRetrievedCount(Integer retrievedCount) {
        this.retrievedCount = retrievedCount;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getResultsJson() {
        return resultsJson;
    }

    public void setResultsJson(String resultsJson) {
        this.resultsJson = resultsJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
