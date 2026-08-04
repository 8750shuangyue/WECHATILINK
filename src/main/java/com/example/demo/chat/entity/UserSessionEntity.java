package com.example.demo.chat.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_sessions", indexes = {
    @Index(name = "idx_user_id", columnList = "user_id")
})
public class UserSessionEntity {

    @Id
    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "pending_image_base64", columnDefinition = "LONGTEXT")
    private String pendingImageBase64;

    @Column(name = "image_description", columnDefinition = "TEXT")
    private String imageDescription;

    @Column(name = "image_analyzed")
    private boolean imageAnalyzed;

    @Column(name = "pending_file_url", length = 500)
    private String pendingFileUrl;

    @Column(name = "pending_file_name", length = 255)
    private String pendingFileName;

    @Column(name = "file_analyzed")
    private boolean fileAnalyzed;

    @Column(name = "last_update_time")
    private LocalDateTime lastUpdateTime;

    public UserSessionEntity() {
    }

    public UserSessionEntity(String userId) {
        this.userId = userId;
        this.lastUpdateTime = LocalDateTime.now();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPendingImageBase64() {
        return pendingImageBase64;
    }

    public void setPendingImageBase64(String pendingImageBase64) {
        this.pendingImageBase64 = pendingImageBase64;
    }

    public String getImageDescription() {
        return imageDescription;
    }

    public void setImageDescription(String imageDescription) {
        this.imageDescription = imageDescription;
    }

    public boolean isImageAnalyzed() {
        return imageAnalyzed;
    }

    public void setImageAnalyzed(boolean imageAnalyzed) {
        this.imageAnalyzed = imageAnalyzed;
    }

    public String getPendingFileUrl() {
        return pendingFileUrl;
    }

    public void setPendingFileUrl(String pendingFileUrl) {
        this.pendingFileUrl = pendingFileUrl;
    }

    public String getPendingFileName() {
        return pendingFileName;
    }

    public void setPendingFileName(String pendingFileName) {
        this.pendingFileName = pendingFileName;
    }

    public boolean isFileAnalyzed() {
        return fileAnalyzed;
    }

    public void setFileAnalyzed(boolean fileAnalyzed) {
        this.fileAnalyzed = fileAnalyzed;
    }

    public LocalDateTime getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(LocalDateTime lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    @PreUpdate
    public void preUpdate() {
        this.lastUpdateTime = LocalDateTime.now();
    }
}