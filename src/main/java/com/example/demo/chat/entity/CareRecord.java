package com.example.demo.chat.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity(name = "LegacyCareRecord")
@Table(name = "care_record", indexes = {
    @Index(name = "idx_target_type_id", columnList = "target_type, target_id"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
public class CareRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", length = 10, nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "record_type", length = 50)
    private String recordType;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public CareRecord() {
    }

    public CareRecord(String targetType, Long targetId, String recordType, String content) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.recordType = recordType;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}