package com.example.wechat.bot.core.model.dto;

public record ChatApiStatus(
        long successCount,
        long failureCount,
        long lastSuccessAt,
        long lastFailureAt,
        String lastErrorMessage,
        boolean lastCallOk) { }
