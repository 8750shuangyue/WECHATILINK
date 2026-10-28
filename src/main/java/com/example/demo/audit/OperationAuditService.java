package com.example.demo.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * 关键操作审计入口。记录内容不含请求体、密码、token、文件内容或完整提示词。
 */
@Service
public class OperationAuditService {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditService.class);

    private final OperationAuditWriter writer;
    private final Executor executor;
    private final boolean enabled;
    private final int maxDetailLength;

    public OperationAuditService(OperationAuditWriter writer,
                                 @Qualifier("auditTaskExecutor") Executor executor,
                                 @Value("${app.audit.enabled:true}") boolean enabled,
                                 @Value("${app.audit.max-detail-length:1000}") int maxDetailLength) {
        this.writer = writer;
        this.executor = executor;
        this.enabled = enabled;
        this.maxDetailLength = Math.max(0, maxDetailLength);
    }

    public void record(HttpServletRequest request, String action, String outcome,
                       String targetType, String targetId, String detail) {
        if (!enabled) {
            return;
        }
        try {
            AuditRecord record = build(request, action, outcome, targetType, targetId, detail);
            executor.execute(() -> writer.write(record));
        } catch (Exception e) {
            log.warn("[Audit] Failed to enqueue audit record action={}: {}", action, e.getMessage());
        }
    }

    AuditRecord build(HttpServletRequest request, String action, String outcome,
                      String targetType, String targetId, String detail) {
        return new AuditRecord(
                MDC.get(AuditContextFilter.MDC_TRACE_ID),
                resolveUser(request),
                ClientIpResolver.resolve(request),
                action,
                outcome,
                truncate(targetType, 32),
                truncate(targetId, 128),
                sanitize(detail),
                request == null ? null : truncate(request.getMethod(), 10),
                request == null ? null : truncate(request.getRequestURI(), 255),
                request == null ? null : truncate(sanitize(request.getHeader("User-Agent")), 255));
    }

    private String resolveUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object attribute = request.getAttribute("userName");
        if (attribute instanceof String userName && !userName.isBlank()) {
            return truncate(userName, 100);
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object user = session.getAttribute("user");
            if (user instanceof String userName && !userName.isBlank()) {
                return truncate(userName, 100);
            }
        }
        return null;
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n\\t]", " ");
        return truncate(cleaned, maxDetailLength);
    }

    private String truncate(String value, int max) {
        if (value == null || max <= 0) {
            return value;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
