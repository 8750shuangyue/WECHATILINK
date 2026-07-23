package com.example.wechat.bot.core.model.dto;

import java.util.concurrent.CompletableFuture;

public record MessageResponse(
        ResponseType type,
        String text,
        byte[] imageBytes,
        String fileName,
        byte[] audioBytes,
        Integer audioDurationMs,
        CompletableFuture<MessageResponse> deferredResponse
) {
    public enum ResponseType {
        TEXT,
        IMAGE,
        VOICE,
        DEFERRED,
        NONE
    }

    public static MessageResponse text(String text) {
        return new MessageResponse(ResponseType.TEXT, text, null, null, null, null, null);
    }

    public static MessageResponse image(byte[] imageBytes, String fileName) {
        return new MessageResponse(ResponseType.IMAGE, null, imageBytes, fileName, null, null, null);
    }

    public static MessageResponse voice(byte[] audioBytes, Integer audioDurationMs) {
        return new MessageResponse(ResponseType.VOICE, null, null, null, audioBytes, audioDurationMs, null);
    }

    public static MessageResponse none() {
        return new MessageResponse(ResponseType.NONE, null, null, null, null, null, null);
    }

    /**
     * 创建一个延迟响应：先发送 pendingText 给用户，异步生成完成后再发送 finalResponse。
     */
    public static MessageResponse deferred(String pendingText,
                                           CompletableFuture<MessageResponse> future) {
        return new MessageResponse(ResponseType.DEFERRED, pendingText, null, null, null, null, future);
    }
}
