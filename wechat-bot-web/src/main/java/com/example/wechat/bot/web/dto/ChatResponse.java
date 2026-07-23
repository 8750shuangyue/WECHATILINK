package com.example.wechat.bot.web.dto;

public record ChatResponse(
        boolean success,
        String reply,
        String error,
        String cause
) {
    public static ChatResponse success(String reply) {
        return new ChatResponse(true, reply, null, null);
    }

    public static ChatResponse failure(String error, String cause) {
        return new ChatResponse(false, null, error, cause);
    }
}
