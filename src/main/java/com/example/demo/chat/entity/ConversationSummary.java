package com.example.demo.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 每个会话一条增量摘要状态。
 *
 * <p>covered_until_id 表示摘要已经覆盖到的最大 messages.id；
 * version 由仓库层显式 CAS 递增，用于抑制并发摘要和重复事件；
 * PENDING + last_attempt_at 是租约，防止多实例同时调用 LLM。</p>
 */
@Entity
@Table(name = "conversation_summaries", indexes = {
        @Index(name = "idx_cs_retry", columnList = "status,next_retry_at")
})
public class ConversationSummary {

    @Id
    @Column(name = "conversation_id", length = 255, nullable = false)
    private String conversationId;

    @Column(name = "summary_text", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String summaryText;

    @Column(name = "covered_until_id", nullable = false)
    private long coveredUntilId;

    @Column(name = "version", nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private SummaryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ConversationSummary() {
    }

    public ConversationSummary(String conversationId, String summaryText, long coveredUntilId,
                                long version, SummaryStatus status, int attemptCount,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.conversationId = conversationId;
        this.summaryText = summaryText;
        this.coveredUntilId = coveredUntilId;
        this.version = version;
        this.status = status;
        this.attemptCount = attemptCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

    public long getCoveredUntilId() {
        return coveredUntilId;
    }

    public void setCoveredUntilId(long coveredUntilId) {
        this.coveredUntilId = coveredUntilId;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public SummaryStatus getStatus() {
        return status;
    }

    public void setStatus(SummaryStatus status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(LocalDateTime lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
