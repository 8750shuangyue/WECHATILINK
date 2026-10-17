package com.example.demo.chat.lifecycle;

import com.example.demo.chat.entity.ConversationSummary;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.repository.mysql.ConversationSummaryRepository;
import com.example.demo.chat.repository.mysql.MessageArchiveRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 摘要提交事务：归档 -> 删除主表已覆盖消息 -> CAS 更新摘要 -> 写入兼容旧链路的 system 镜像行。
 * 任一步失败整体回滚，不会出现“消息删了但摘要没更新”的中间状态。
 */
@Component
public class SummaryCompactionWriter {

    private static final Logger logger = LoggerFactory.getLogger(SummaryCompactionWriter.class);
    private static final String SUMMARY_PREFIX = "【对话摘要】";

    private final ConversationSummaryRepository summaryRepository;
    private final MessageRepository messageRepository;
    private final MessageArchiveRepository archiveRepository;

    public SummaryCompactionWriter(ConversationSummaryRepository summaryRepository,
                                   MessageRepository messageRepository,
                                   MessageArchiveRepository archiveRepository) {
        this.summaryRepository = summaryRepository;
        this.messageRepository = messageRepository;
        this.archiveRepository = archiveRepository;
    }

    @Transactional
    public void apply(SummaryClaim claim, String summary, long newWatermark,
                      int candidateCount, LocalDateTime now) {
        String conversationId = claim.conversationId();
        ConversationSummary row = summaryRepository.findForUpdate(conversationId)
                .orElseThrow(() -> new SummaryApplyException(
                        conversationId, SummaryApplyException.Reason.CLEARED));
        if (row.getVersion() != claim.version()) {
            throw new SummaryApplyException(
                    conversationId, SummaryApplyException.Reason.CONFLICT);
        }

        long activeCount = messageRepository.countActiveBetween(
                conversationId, claim.coveredUntilId(), newWatermark);
        if (activeCount < candidateCount) {
            throw new SummaryApplyException(
                    conversationId, SummaryApplyException.Reason.STALE);
        }

        int archived = archiveRepository.archiveThrough(conversationId, newWatermark, now);
        int deleted = messageRepository.deleteCompactedThrough(conversationId, newWatermark);
        int updated = summaryRepository.markReady(
                conversationId, claim.version(), summary, newWatermark, now);
        if (updated == 0) {
            throw new SummaryApplyException(
                    conversationId, SummaryApplyException.Reason.CONFLICT);
        }

        // 兼容旧 AgentService / 旧 Prompt 的镜像行；新链路优先读 conversation_summaries。
        messageRepository.saveAndFlush(new Message(
                conversationId, "system", SUMMARY_PREFIX + summary, now, null));

        logger.info("summary_updated cid={} watermark={} candidates={} archived={} deleted={}",
                conversationId, newWatermark, candidateCount, archived, deleted);
    }
}
