package com.example.demo.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 审计日志保留周期清理（MySQL，与 SQLite 的 RAG 治理任务相互独立）。
 */
@Service
public class OperationAuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditRetentionService.class);

    private static final int BATCH_SIZE = 5000;
    private static final int MAX_BATCHES = 20;

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.audit.enabled:true}")
    private boolean enabled = true;

    @Value("${app.audit.retention-days:365}")
    private long retentionDays = 365;

    public OperationAuditRetentionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${app.audit.cleanup-cron:0 45 4 * * *}")
    public void scheduledCleanup() {
        cleanup(LocalDateTime.now());
    }

    int cleanup(LocalDateTime now) {
        if (!enabled || retentionDays <= 0) {
            return 0;
        }
        LocalDateTime cutoff = now.minusDays(retentionDays);
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted;
            try {
                deleted = jdbcTemplate.update(
                        "DELETE FROM operation_audit_log WHERE created_at < ? LIMIT " + BATCH_SIZE,
                        Timestamp.valueOf(cutoff));
            } catch (Exception e) {
                log.warn("[Audit] Retention cleanup failed: {}", e.getMessage());
                break;
            }
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("[Audit] Retention cleanup removed {} rows before {}", total, cutoff);
        }
        return total;
    }
}
