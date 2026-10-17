package com.example.demo.chat.lifecycle;

import com.example.demo.chat.entity.ConversationSummary;
import com.example.demo.chat.entity.SummaryStatus;
import com.example.demo.chat.repository.mysql.ConversationSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 摘要状态的短事务：claim 租约、失败落库、无候选释放。
 * LLM 调用和归档写入都不在这里，避免长事务。
 */
@Component
public class SummaryStateStore {

    private static final Logger logger = LoggerFactory.getLogger(SummaryStateStore.class);

    private final ConversationSummaryRepository summaryRepository;

    public SummaryStateStore(ConversationSummaryRepository summaryRepository) {
        this.summaryRepository = summaryRepository;
    }

    public long coveredUntilId(String conversationId) {
        return summaryRepository.findById(conversationId)
                .map(ConversationSummary::getCoveredUntilId)
                .orElse(0L);
    }

    /**
     * 获取摘要租约。返回 null 表示另一个 worker 正在处理（PENDING 且租约未过期）。
     */
    @Transactional
    public SummaryClaim claim(String conversationId, LocalDateTime now, Duration lease) {
        Optional<ConversationSummary> existing = summaryRepository.findForUpdate(conversationId);
        if (existing.isPresent()) {
            ConversationSummary summary = existing.get();
            if (summary.getStatus() == SummaryStatus.PENDING
                    && summary.getLastAttemptAt() != null
                    && summary.getLastAttemptAt().isAfter(now.minus(lease))) {
                logger.debug("Summary claim busy for conversation {}", conversationId);
                return null;
            }
            if (summary.getStatus() == SummaryStatus.FAILED
                    && summary.getNextRetryAt() != null
                    && summary.getNextRetryAt().isAfter(now)) {
                logger.debug("Summary claim in backoff for conversation {}", conversationId);
                return null;
            }
            summary.setStatus(SummaryStatus.PENDING);
            summary.setAttemptCount(summary.getAttemptCount() + 1);
            summary.setLastAttemptAt(now);
            summary.setNextRetryAt(null);
            summary.setVersion(summary.getVersion() + 1);
            summary.setUpdatedAt(now);
            summaryRepository.saveAndFlush(summary);
            return new SummaryClaim(conversationId, summary.getSummaryText(),
                    summary.getCoveredUntilId(), summary.getVersion(), summary.getAttemptCount());
        }

        ConversationSummary created = new ConversationSummary(
                conversationId, "", 0L, 0L, SummaryStatus.PENDING, 1, now, now);
        created.setLastAttemptAt(now);
        summaryRepository.saveAndFlush(created);
        return new SummaryClaim(conversationId, "", 0L, 0L, 1);
    }

    @Transactional
    public void markNoCandidate(SummaryClaim claim, LocalDateTime now) {
        int updated = summaryRepository.markNoCandidate(
                claim.conversationId(), claim.version(), now);
        if (updated == 0) {
            logger.debug("Summary claim changed before NO_CANDIDATE for conversation {}",
                    claim.conversationId());
        }
    }

    @Transactional
    public void markFailed(SummaryClaim claim, String error, LocalDateTime now,
                           LocalDateTime nextRetryAt) {
        String safeError = error == null ? "unknown" : error;
        if (safeError.length() > 1000) {
            safeError = safeError.substring(0, 1000);
        }
        int updated = summaryRepository.markFailed(
                claim.conversationId(), claim.version(), safeError, now, nextRetryAt);
        if (updated == 0) {
            logger.debug("Summary claim changed before failure mark for conversation {}",
                    claim.conversationId());
        }
    }

    @Transactional
    public int markStalePendingFailed(LocalDateTime staleBefore, LocalDateTime now,
                                      LocalDateTime nextRetryAt) {
        return summaryRepository.markStalePendingFailed(staleBefore, now, nextRetryAt);
    }
}
