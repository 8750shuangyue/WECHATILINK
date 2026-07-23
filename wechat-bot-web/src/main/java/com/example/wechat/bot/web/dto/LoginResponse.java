package com.example.wechat.bot.web.dto;

public record LoginResponse(
        boolean success,
        String qrCodeBase64,
        String qrContent,
        String error
) {
    public static LoginResponse success(String qrCodeBase64, String qrContent) {
        return new LoginResponse(true, qrCodeBase64, qrContent, null);
    }

    public static LoginResponse failure(String error) {
        return new LoginResponse(false, null, null, error);
    }
}
