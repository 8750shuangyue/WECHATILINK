package com.example.demo.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 已被摘要覆盖并从 messages 移出的历史消息。
 * id 复用原 messages.id，归档与删除在同一事务中完成，主键天然幂等。
 */
@Entity
@Table(name = "messages_archive", indexes = {
        @Index(name = "idx_msg_archive_conv_id", columnList = "conversation_id,id"),
        @Index(name = "idx_msg_archive_archived_at", columnList = "archived_at")
})
public class MessageArchive {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "conversation_id", length = 255, nullable = false)
    private String conversationId;

    @Column(name = "role", length = 20, nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "sources_json", columnDefinition = "MEDIUMTEXT")
    private String sourcesJson;

    @Column(name = "archived_at", nullable = false)
    private LocalDateTime archivedAt;

    public MessageArchive() {
    }

    public MessageArchive(Long id, String conversationId, String role, String content,
                          LocalDateTime timestamp, String sourcesJson, LocalDateTime archivedAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
        this.sourcesJson = sourcesJson;
        this.archivedAt = archivedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = sourcesJson;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }
}
