package com.example.demo.chat;

import com.example.demo.chat.repository.sqlite.RagObservabilityAccessLogRepository;
import com.example.demo.chat.repository.sqlite.RagRetrievalLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RagDataGovernanceService {

    private static final Logger logger = LoggerFactory.getLogger(RagDataGovernanceService.class);

    private final VectorStoreService vectorStoreService;
    private final RagRetrievalLogRepository retrievalLogRepository;
    private final RagObservabilityAccessLogRepository observabilityAccessLogRepository;

    @Value("${chat.vectorstore.conversation-retention-days:90}")
    private long conversationRetentionDays;

    @Value("${rag.retention.retrieval-log-days:180}")
    private long retrievalLogRetentionDays;

    @Value("${rag.retention.observability-access-log-days:180}")
    private long observabilityAccessLogRetentionDays;

    @Value("${app.data-governance.enabled:false}")
    private boolean governanceEnabled;

    public RagDataGovernanceService(
            VectorStoreService vectorStoreService,
            RagRetrievalLogRepository retrievalLogRepository,
            RagObservabilityAccessLogRepository observabilityAccessLogRepository) {
        this.vectorStoreService = vectorStoreService;
        this.retrievalLogRepository = retrievalLogRepository;
        this.observabilityAccessLogRepository = observabilityAccessLogRepository;
    }

    @Scheduled(cron = "${chat.vectorstore.duplicate-cleanup-cron:0 0 4 * * *}")
    public int cleanupDuplicateConversationVectors() {
        if (!governanceEnabled) {
            return 0;
        }
        int deleted = vectorStoreService.cleanupDuplicateConversationVectors();
        if (deleted > 0) {
            logger.info("Scheduled duplicate conversation vector cleanup removed {} rows", deleted);
        }
        return deleted;
    }

    @Scheduled(cron = "${chat.vectorstore.conversation-retention-cleanup-cron:0 15 4 * * *}")
    public int cleanupExpiredConversationVectors() {
        if (!governanceEnabled) {
            return 0;
        }
        return cleanupExpiredConversationVectors(LocalDateTime.now());
    }

    int cleanupExpiredConversationVectors(LocalDateTime now) {
        long retentionDays = positiveRetentionDays(
                conversationRetentionDays, "chat.vectorstore.conversation-retention-days");
        if (retentionDays == 0) {
            return 0;
        }

        LocalDateTime cutoff = now.minusDays(retentionDays);
        int deleted = vectorStoreService.deleteExpiredConversationVectors(cutoff);
        if (deleted > 0) {
            logger.info("Scheduled conversation vector retention cleanup removed {} rows before {}",
                    deleted, cutoff);
        }
        return deleted;
    }

    @Scheduled(cron = "${rag.retention.cleanup-cron:0 30 4 * * *}")
    @Transactional(transactionManager = "sqliteTransactionManager")
    public int cleanupExpiredAuditLogs() {
        if (!governanceEnabled) {
            return 0;
        }
        return cleanupExpiredAuditLogs(LocalDateTime.now());
    }

    int cleanupExpiredAuditLogs(LocalDateTime now) {
        long retrievalDays = positiveRetentionDays(
                retrievalLogRetentionDays, "rag.retention.retrieval-log-days");
        long accessDays = positiveRetentionDays(
                observabilityAccessLogRetentionDays, "rag.retention.observability-access-log-days");

        int deleted = 0;
        if (retrievalDays > 0) {
            deleted += retrievalLogRepository.deleteCreatedBefore(now.minusDays(retrievalDays));
        }
        if (accessDays > 0) {
            deleted += observabilityAccessLogRepository.deleteCreatedBefore(now.minusDays(accessDays));
        }
        if (deleted > 0) {
            logger.info("Scheduled RAG audit retention cleanup removed {} rows", deleted);
        }
        return deleted;
    }

    private long positiveRetentionDays(long configuredDays, String propertyName) {
        if (configuredDays > 0) {
            return configuredDays;
        }
        logger.warn("Retention cleanup disabled because {} is {}", propertyName, configuredDays);
        return 0;
    }
}
