package com.example.demo.chat.lifecycle;

import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.entity.Conversation;
import com.example.demo.chat.repository.mysql.ConversationRepository;
import com.example.demo.chat.repository.mysql.ConversationSummaryRepository;
import com.example.demo.chat.repository.mysql.MessageArchiveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 生命周期后台任务：摘要失败重试/丢失事件对账 + 180 天归档清理。
 * 独立开关 app.message-lifecycle.enabled；与 app.data-governance.enabled 完全隔离。
 */
@Component
public class MessageLifecycleScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MessageLifecycleScheduler.class);

    private final ConversationSummaryRepository summaryRepository;
    private final MessageArchiveRepository archiveRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMemoryService chatMemoryService;
    private final Executor summaryExecutor;

    @Value("${app.message-lifecycle.enabled:false}")
    private boolean enabled = false;

    @Value("${chat.memory.summary-keep-recent:5}")
    private int summaryKeepRecent = 5;

    @Value("${app.message-lifecycle.summary-lock-ttl-seconds:600}")
    private long summaryLockTtlSeconds = 600;

    @Value("${app.message-lifecycle.summary-retry-limit:50}")
    private int retryLimit = 50;

    @Value("${app.message-lifecycle.archive-retention-days:180}")
    private long archiveRetentionDays = 180;

    @Value("${app.message-lifecycle.archive-cleanup-batch-size:1000}")
    private int archiveBatchSize = 1000;

    @Value("${app.message-lifecycle.archive-cleanup-max-batches:10}")
    private int archiveMaxBatches = 10;

    public MessageLifecycleScheduler(ConversationSummaryRepository summaryRepository,
                                     MessageArchiveRepository archiveRepository,
                                     ConversationRepository conversationRepository,
                                     ChatMemoryService chatMemoryService,
                                     @Qualifier("summaryTaskExecutor") Executor summaryExecutor) {
        this.summaryRepository = summaryRepository;
        this.archiveRepository = archiveRepository;
        this.conversationRepository = conversationRepository;
        this.chatMemoryService = chatMemoryService;
        this.summaryExecutor = summaryExecutor;
    }

    @Scheduled(cron = "${app.message-lifecycle.summary-retry-cron:0 */10 * * * *}")
    public void retrySummaries() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusSeconds(summaryLockTtlSeconds);
        LocalDateTime nextRetryAt = now.plusSeconds(60);
        int stale = summaryRepository.markStalePendingFailed(staleBefore, now, nextRetryAt);
        if (stale > 0) {
            logger.warn("summary_stale_released count={}", stale);
        }

        int window = Math.max(2, summaryKeepRecent * 2);
        Set<String> conversationIds = new LinkedHashSet<>();
        conversationIds.addAll(summaryRepository.findDueRetries(now, retryLimit));
        conversationIds.addAll(summaryRepository.findReconciliationCandidates(window, now, retryLimit));

        int submitted = 0;
        for (String conversationId : conversationIds) {
            Optional<Conversation> conversation = conversationRepository.findById(conversationId);
            if (conversation.isEmpty()
                    || conversation.get().getUserId() == null
                    || conversation.get().getUserId().isBlank()) {
                continue;
            }
            String userId = conversation.get().getUserId();
            try {
                summaryExecutor.execute(
                        () -> chatMemoryService.checkAndUpdateSummary(userId, conversationId));
                submitted++;
            } catch (RejectedExecutionException exception) {
                logger.warn("summary_retry_rejected cid={} reason=executor_full", conversationId);
                break;
            }
        }
        if (!conversationIds.isEmpty()) {
            logger.info("summary_retry_sweep candidates={} submitted={}", conversationIds.size(), submitted);
        }
    }

    @Scheduled(cron = "${app.message-lifecycle.archive-cleanup-cron:0 40 3 * * *}")
    public void cleanupArchive() {
        if (!enabled || archiveRetentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(archiveRetentionDays);
        long total = 0;
        int batches = 0;
        for (int batch = 0; batch < archiveMaxBatches; batch++) {
            int deleted = archiveRepository.deleteExpired(cutoff, archiveBatchSize);
            total += deleted;
            batches++;
            if (deleted < archiveBatchSize) {
                break;
            }
        }
        logger.info("archive_cleanup cutoff={} batches={} deleted={}", cutoff, batches, total);
    }
}
