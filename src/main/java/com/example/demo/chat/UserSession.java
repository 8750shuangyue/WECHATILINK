package com.example.demo.chat;

import java.time.LocalDateTime;

public class UserSession {
    private String userId;
    private String pendingImageBase64;
    private String imageDescription;
    private boolean imageAnalyzed;
    private String pendingFileUrl;
    private String pendingFileName;
    private boolean fileAnalyzed;
    private LocalDateTime lastUpdateTime;

    public UserSession() {
    }

    public UserSession(String userId, String pendingImageBase64) {
        this.userId = userId;
        this.pendingImageBase64 = pendingImageBase64;
        this.imageAnalyzed = false;
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

    public boolean hasPendingFile() {
        return pendingFileUrl != null && !pendingFileUrl.isEmpty();
    }

    public boolean hasUnanalyzedFile() {
        return hasPendingFile() && !fileAnalyzed;
    }

    public void clearPendingFile() {
        this.pendingFileUrl = null;
        this.pendingFileName = null;
        this.fileAnalyzed = false;
        this.lastUpdateTime = LocalDateTime.now();
    }

    public LocalDateTime getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(LocalDateTime lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    public boolean isImageAnalyzed() {
        return imageAnalyzed;
    }

    public void setImageAnalyzed(boolean imageAnalyzed) {
        this.imageAnalyzed = imageAnalyzed;
    }

    public boolean hasPendingImage() {
        return pendingImageBase64 != null && !pendingImageBase64.isEmpty();
    }

    public boolean hasUnanalyzedImage() {
        return hasPendingImage() && !imageAnalyzed;
    }

    public void clearPendingImage() {
        this.pendingImageBase64 = null;
        this.imageAnalyzed = false;
        this.lastUpdateTime = LocalDateTime.now();
    }
}