package com.example.wechat.bot.web.dto;

public record QRCodeResponse(
        boolean success,
        String qrCodeBase64,
        String error
) {
    public static QRCodeResponse success(String qrCodeBase64) {
        return new QRCodeResponse(true, qrCodeBase64, null);
    }

    public static QRCodeResponse failure(String error) {
        return new QRCodeResponse(false, null, error);
    }
}
