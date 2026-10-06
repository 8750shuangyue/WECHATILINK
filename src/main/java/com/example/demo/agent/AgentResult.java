package com.example.demo.agent;

public class AgentResult {

    private String reply;
    private boolean success;
    private String errorMessage;
    private String imageUrl;
    private String audioFilePath;
    private String progressMessage;

    private AgentResult() {
    }

    public static AgentResult success(String reply) {
        AgentResult result = new AgentResult();
        result.success = true;
        result.reply = reply;
        return result;
    }

    public static AgentResult successWithImage(String reply, String imageUrl) {
        AgentResult result = new AgentResult();
        result.success = true;
        result.reply = reply;
        result.imageUrl = imageUrl;
        return result;
    }

    public static AgentResult successWithAudio(String reply, String audioFilePath) {
        AgentResult result = new AgentResult();
        result.success = true;
        result.reply = reply;
        result.audioFilePath = audioFilePath;
        return result;
    }

    public static AgentResult failure(String errorMessage) {
        AgentResult result = new AgentResult();
        result.success = false;
        result.errorMessage = errorMessage;
        return result;
    }
    
    public static AgentResult progress(String message) {
        AgentResult result = new AgentResult();
        result.success = false;
        result.progressMessage = message;
        return result;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getAudioFilePath() {
        return audioFilePath;
    }

    public void setAudioFilePath(String audioFilePath) {
        this.audioFilePath = audioFilePath;
    }
    
    public String getProgressMessage() {
        return progressMessage;
    }
    
    public void setProgressMessage(String progressMessage) {
        this.progressMessage = progressMessage;
    }
    
    public boolean hasProgressMessage() {
        return progressMessage != null && !progressMessage.isEmpty();
    }
}