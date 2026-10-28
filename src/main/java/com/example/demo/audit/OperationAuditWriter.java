package com.example.demo.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 审计写库。失败只记日志，不影响业务请求。
 */
@Component
public class OperationAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditWriter.class);

    private static final String INSERT_SQL = """
            INSERT INTO operation_audit_log
                (trace_id, user_id, client_ip, action, outcome, target_type, target_id,
                 detail, request_method, request_path, user_agent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public OperationAuditWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void write(AuditRecord record) {
        try {
            jdbcTemplate.update(INSERT_SQL,
                    record.traceId(),
                    record.userId(),
                    record.clientIp(),
                    record.action(),
                    record.outcome(),
                    record.targetType(),
                    record.targetId(),
                    record.detail(),
                    record.requestMethod(),
                    record.requestPath(),
                    record.userAgent());
        } catch (Exception e) {
            log.warn("[Audit] Failed to persist audit record action={}, outcome={}: {}",
                    record.action(), record.outcome(), e.getMessage());
        }
    }
}
