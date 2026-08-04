package com.example.demo.chat.entity.sqlite;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vector_store", indexes = {
    @Index(name = "idx_source_id", columnList = "source_id"),
    @Index(name = "idx_conversation_id", columnList = "conversation_id"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
public class VectorStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", length = 36, nullable = false)
    private String documentId;

    @Column(name = "source_id", length = 255)
    private String sourceId;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "vector", columnDefinition = "BLOB", nullable = false)
    private byte[] vector;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "conversation_id", length = 255)
    private String conversationId;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    public VectorStore() {
        this.timestamp = LocalDateTime.now();
    }

    public VectorStore(String documentId, String content, byte[] vector) {
        this.documentId = documentId;
        this.content = content;
        this.vector = vector;
        this.timestamp = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public byte[] getVector() {
        return vector;
    }

    public void setVector(byte[] vector) {
        this.vector = vector;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}