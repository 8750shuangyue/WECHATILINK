package com.example.demo.chat.entity.sqlite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * RAG 只读观测接口的访问审计。
 * 只记录访问目标和授权结果，不记录查询明文或命中的文档正文。
 */
@Entity
@Table(name = "rag_observability_access_log", indexes = {
    @Index(name = "idx_rag_obs_access_user", columnList = "user_id"),
    @Index(name = "idx_rag_obs_access_trace", columnList = "target_trace_id"),
    @Index(name = "idx_rag_obs_access_conversation", columnList = "conversation_id"),
    @Index(name = "idx_rag_obs_access_created", columnList = "created_at")
})
public class RagObservabilityAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "access_action", length = 32, nullable = false)
    private String action;

    @Column(name = "access_decision", length = 32, nullable = false)
    private String decision;

    @Column(name = "reason", length = 64)
    private String reason;

    @Column(name = "target_trace_id", length = 64)
    private String targetTraceId;

    @Column(name = "conversation_id", length = 255)
    private String conversationId;

    @Column(name = "result_count")
    private Integer resultCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public RagObservabilityAccessLog() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTargetTraceId() {
        return targetTraceId;
    }

    public void setTargetTraceId(String targetTraceId) {
        this.targetTraceId = targetTraceId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Integer getResultCount() {
        return resultCount;
    }

    public void setResultCount(Integer resultCount) {
        this.resultCount = resultCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
