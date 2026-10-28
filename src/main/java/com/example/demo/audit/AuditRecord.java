package com.example.demo.audit;

public record AuditRecord(
        String traceId,
        String userId,
        String clientIp,
        String action,
        String outcome,
        String targetType,
        String targetId,
        String detail,
        String requestMethod,
        String requestPath,
        String userAgent) {
}
